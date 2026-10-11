#include "net_server.h"
#include "app_config.h"
#include "csi_collector.h"
#include "ping_tx.h"
#include "status_io.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <ctype.h>
#include <inttypes.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "lwip/sockets.h"
#include "lwip/inet.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_mac.h"
#include "sdkconfig.h"

static const char *TAG = "net";

#define MAX_CLIENTS 3
#define CMD_LINE_MAX    160

typedef struct {
    int  fd;              // -1 = free slot
    bool streaming;       // receives CSI frames (status frames go to everyone)
    char line[CMD_LINE_MAX];  // partial command line
    int  line_len;
} client_t;

static client_t          s_clients[MAX_CLIENTS];
static SemaphoreHandle_t s_lock;      // guards s_clients AND serialises all send() calls
static esp_netif_t      *s_netif;
static wifi_interface_t  s_ifx;
static volatile uint32_t s_pings_rx;

static void lock(void)   { xSemaphoreTake(s_lock, portMAX_DELAY); }
static void unlock(void) { xSemaphoreGive(s_lock); }

static bool send_all(int fd, const uint8_t *buf, size_t len)
{
    while (len > 0) {
        int n = send(fd, buf, len, 0);
        if (n <= 0) return false;
        buf += n;
        len -= n;
    }
    return true;
}

// Header and payload go out in a single send() so TCP_NODELAY yields one segment per frame.
static bool send_frame(int fd, uint8_t type, const void *payload, uint16_t len)
{
    uint8_t buf[6 + len];
    buf[0] = WR_FRAME_MAGIC & 0xff;
    buf[1] = WR_FRAME_MAGIC >> 8;
    buf[2] = type;
    buf[3] = WR_PROTO_VERSION;
    buf[4] = len & 0xff;
    buf[5] = len >> 8;
    memcpy(&buf[6], payload, len);
    return send_all(fd, buf, sizeof(buf));
}

static void client_close(client_t *c)
{
    if (c->fd >= 0) {
        ESP_LOGI(TAG, "client fd=%d closed", c->fd);
        close(c->fd);
    }
    c->fd = -1;
    c->streaming = false;
    c->line_len = 0;
}

static void refresh_led(void)
{
    bool any = false;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (s_clients[i].fd >= 0 && s_clients[i].streaming) any = true;
    }
    status_io_set_streaming(any);
}

static void reply(client_t *c, const char *fmt, ...)
{
    char buf[640];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if (n >= (int)sizeof(buf)) n = sizeof(buf) - 1;
    if (!send_frame(c->fd, WR_FRAME_TEXT, buf, n)) client_close(c);
}

static int build_info_json(char *out, size_t cap)
{
    uint8_t mac[6] = { 0 };
    esp_wifi_get_mac(s_ifx, mac);
    esp_netif_ip_info_t ip = { 0 };
    if (s_netif) esp_netif_get_ip_info(s_netif, &ip);
    wr_csi_stats_t st;
    csi_collector_get_stats(&st);
    char filt[20] = "none";
    if (g_cfg.filter_enabled) snprintf(filt, sizeof(filt), MACSTR, MAC2STR(g_cfg.filter_mac));

    return snprintf(out, cap,
        "{\"fw\":\"%s\",\"proto\":%d,\"role\":\"%s\",\"mac\":\"" MACSTR "\",\"ip\":\"" IPSTR "\","
        "\"ssid\":\"%s\",\"channel\":%u,\"tx_rate_hz\":%u,"
        "\"csi\":{\"lltf\":%d,\"htltf\":%d,\"stbc\":%d},\"filter\":\"%s\","
        "\"stats\":{\"total\":%" PRIu32 ",\"dropped\":%" PRIu32 ",\"filtered\":%" PRIu32
        ",\"pings_rx\":%" PRIu32 ",\"tx_count\":%" PRIu32 "},"
        "\"heap\":%" PRIu32 ",\"uptime_s\":%" PRIu32 "}",
        WR_FW_VERSION, WR_PROTO_VERSION, wr_role_name(g_cfg.role), MAC2STR(mac), IP2STR(&ip.ip),
        g_cfg.ssid, g_cfg.channel, g_cfg.tx_rate_hz,
        g_cfg.csi_lltf, g_cfg.csi_htltf, g_cfg.csi_stbc, filt,
        st.total, st.dropped, st.filtered, s_pings_rx,
        g_cfg.role == WR_ROLE_TX ? ping_tx_get_count() : 0,
        (uint32_t)esp_get_free_heap_size(), (uint32_t)(esp_timer_get_time() / 1000000));
}

static void build_status(wr_status_t *st)
{
    memset(st, 0, sizeof(*st));
    wr_csi_stats_t cs;
    csi_collector_get_stats(&cs);
    st->uptime_s     = esp_timer_get_time() / 1000000;
    st->csi_total    = cs.total;
    st->csi_dropped  = cs.dropped;
    st->csi_filtered = cs.filtered;
    st->pings_rx     = s_pings_rx;
    st->tx_count     = g_cfg.role == WR_ROLE_TX ? ping_tx_get_count() : 0;
    st->free_heap    = esp_get_free_heap_size();
    st->role         = g_cfg.role;
    st->tx_rate_hz   = g_cfg.tx_rate_hz;
    uint8_t ch; wifi_second_chan_t sc;
    if (esp_wifi_get_channel(&ch, &sc) == ESP_OK) st->channel = ch;
    if (g_cfg.role == WR_ROLE_RX) {
        wifi_ap_record_t ap;
        if (esp_wifi_sta_get_ap_info(&ap) == ESP_OK) st->rssi = ap.rssi;
    } else {
        wifi_sta_list_t list;
        if (esp_wifi_ap_get_sta_list(&list) == ESP_OK) st->sta_count = list.num;
    }
}

// ---- Command handling (phone -> ESP32, one text line per command) -----------

static void handle_command(client_t *c, char *line)
{
    char *save = NULL;
    char *cmd = strtok_r(line, " \t", &save);
    if (!cmd) return;
    for (char *p = cmd; *p; p++) *p = toupper((unsigned char)*p);
    const char *a1 = strtok_r(NULL, " \t", &save);
    const char *a2 = strtok_r(NULL, " \t", &save);
    const char *a3 = strtok_r(NULL, " \t", &save);

    if (!strcmp(cmd, "INFO")) {
        char json[560];
        build_info_json(json, sizeof(json));
        reply(c, "INFO %s", json);
    } else if (!strcmp(cmd, "START")) {
        c->streaming = true;
        refresh_led();
        reply(c, "OK START");
    } else if (!strcmp(cmd, "STOP")) {
        c->streaming = false;
        refresh_led();
        reply(c, "OK STOP");
    } else if (!strcmp(cmd, "SET_ROLE")) {
        wr_role_t r;
        if (!a1 || !wr_role_parse(a1, &r)) { reply(c, "ERR SET_ROLE expected single|tx|rx"); return; }
        g_cfg.role = r;
        app_config_save();
        reply(c, "OK SET_ROLE %s (reboot to apply)", wr_role_name(r));
    } else if (!strcmp(cmd, "SET_RATE")) {
        int hz = a1 ? atoi(a1) : 0;
        if (hz < 1 || hz > 500) { reply(c, "ERR SET_RATE expected 1..500"); return; }
        g_cfg.tx_rate_hz = hz;
        app_config_save();
        if (g_cfg.role == WR_ROLE_TX) ping_tx_set_rate(hz);
        reply(c, "OK SET_RATE %d", hz);
    } else if (!strcmp(cmd, "SET_WIFI")) {
        size_t sl = a1 ? strlen(a1) : 0, pl = a2 ? strlen(a2) : 0;
        if (sl == 0 || sl > 32 || (pl != 0 && (pl < 8 || pl > 63))) {
            reply(c, "ERR SET_WIFI expected <ssid 1-32> [password 8-63]"); return;
        }
        strlcpy(g_cfg.ssid, a1, sizeof(g_cfg.ssid));
        strlcpy(g_cfg.password, a2 ? a2 : "", sizeof(g_cfg.password));
        app_config_save();
        reply(c, "OK SET_WIFI %s (reboot to apply)", g_cfg.ssid);
    } else if (!strcmp(cmd, "SET_CHANNEL")) {
        int ch = a1 ? atoi(a1) : 0;
        if (ch < 1 || ch > 13) { reply(c, "ERR SET_CHANNEL expected 1..13"); return; }
        g_cfg.channel = ch;
        app_config_save();
        reply(c, "OK SET_CHANNEL %d (reboot to apply)", ch);
    } else if (!strcmp(cmd, "SET_CSI")) {
        if (!a1 || !a2 || !a3) { reply(c, "ERR SET_CSI expected <lltf 0|1> <htltf 0|1> <stbc 0|1>"); return; }
        bool l = atoi(a1) != 0, h = atoi(a2) != 0, s = atoi(a3) != 0;
        if (!l && !h && !s) { reply(c, "ERR SET_CSI at least one LTF type must be enabled"); return; }
        g_cfg.csi_lltf = l; g_cfg.csi_htltf = h; g_cfg.csi_stbc = s;
        app_config_save();
        esp_err_t err = csi_collector_apply_config();
        if (err == ESP_OK) reply(c, "OK SET_CSI %d %d %d", l, h, s);
        else reply(c, "ERR SET_CSI %s", esp_err_to_name(err));
    } else if (!strcmp(cmd, "SET_FILTER")) {
        if (!a1) { reply(c, "ERR SET_FILTER expected <mac|none>"); return; }
        if (!strcasecmp(a1, "none")) {
            g_cfg.filter_enabled = false;
            csi_collector_set_filter(false, NULL);
        } else {
            uint8_t mac[6];
            if (!wr_parse_mac(a1, mac)) { reply(c, "ERR SET_FILTER bad MAC"); return; }
            g_cfg.filter_enabled = true;
            memcpy(g_cfg.filter_mac, mac, 6);
            csi_collector_set_filter(true, mac);
        }
        app_config_save();
        reply(c, "OK SET_FILTER %s", a1);
    } else if (!strcmp(cmd, "REBOOT")) {
        reply(c, "OK REBOOT");
        vTaskDelay(pdMS_TO_TICKS(200));
        esp_restart();
    } else {
        reply(c, "ERR unknown command %s", cmd);
    }
}

// ---- Tasks ------------------------------------------------------------------

static void server_task(void *arg)
{
    int ls = socket(AF_INET, SOCK_STREAM, IPPROTO_IP);
    int one = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port = htons(CONFIG_WR_TCP_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(ls, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(ls, 2) != 0) {
        ESP_LOGE(TAG, "TCP bind/listen failed: errno %d", errno);
        vTaskDelete(NULL);
        return;
    }
    ESP_LOGI(TAG, "TCP server listening on %d", CONFIG_WR_TCP_PORT);

    while (true) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(ls, &rfds);
        int maxfd = ls;
        lock();
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (s_clients[i].fd >= 0) {
                FD_SET(s_clients[i].fd, &rfds);
                if (s_clients[i].fd > maxfd) maxfd = s_clients[i].fd;
            }
        }
        unlock();

        struct timeval tv = { .tv_sec = 0, .tv_usec = 200000 };
        if (select(maxfd + 1, &rfds, NULL, NULL, &tv) <= 0) continue;

        if (FD_ISSET(ls, &rfds)) {
            struct sockaddr_in peer;
            socklen_t plen = sizeof(peer);
            int fd = accept(ls, (struct sockaddr *)&peer, &plen);
            if (fd >= 0) {
                lock();
                client_t *slot = NULL;
                for (int i = 0; i < MAX_CLIENTS && !slot; i++) if (s_clients[i].fd < 0) slot = &s_clients[i];
                if (!slot) {
                    ESP_LOGW(TAG, "too many clients, rejecting");
                    close(fd);
                } else {
                    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
                    // Bounds a stalled client. lwIP only checks this timeout on ACK arrival or
                    // its 1 s poll tick, so a dead peer blocks the stream task ~1-1.5 s before
                    // it is evicted; the ring buffer is sized to absorb that at 100 Hz.
                    struct timeval sto = { .tv_sec = 0, .tv_usec = 500000 };
                    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &sto, sizeof(sto));
                    slot->fd = fd;
                    slot->streaming = true;   // stream immediately; STOP opts out
                    slot->line_len = 0;
                    ESP_LOGI(TAG, "client %s connected fd=%d", inet_ntoa(peer.sin_addr), fd);
                    refresh_led();
                }
                unlock();
            }
        }

        for (int i = 0; i < MAX_CLIENTS; i++) {
            lock();
            client_t *c = &s_clients[i];
            if (c->fd >= 0 && FD_ISSET(c->fd, &rfds)) {
                char buf[128];
                // Non-blocking: the fd may have been closed by stream_task after select()
                // and already reused by accept() for a client that has not sent anything.
                int n = recv(c->fd, buf, sizeof(buf), MSG_DONTWAIT);
                if (n < 0 && (errno == EWOULDBLOCK || errno == EAGAIN)) {
                    // stale fd_set entry, nothing to read
                } else if (n <= 0) {
                    client_close(c);
                    refresh_led();
                } else {
                    for (int k = 0; k < n && c->fd >= 0; k++) {
                        char ch = buf[k];
                        if (ch == '\n') {
                            while (c->line_len > 0 && c->line[c->line_len - 1] == '\r') c->line_len--;
                            c->line[c->line_len] = 0;
                            if (c->line_len > 0) handle_command(c, c->line);
                            c->line_len = 0;
                        } else if (c->line_len < CMD_LINE_MAX - 1) {
                            c->line[c->line_len++] = ch;
                        }
                    }
                }
            }
            unlock();
        }
    }
}

static void stream_task(void *arg)
{
    int64_t next_status = esp_timer_get_time() + 1000000;
    while (true) {
        size_t len = 0;
        wr_csi_sample_t *s = csi_collector_receive(&len, 50);
        if (s) {
            lock();
            for (int i = 0; i < MAX_CLIENTS; i++) {
                client_t *c = &s_clients[i];
                if (c->fd >= 0 && c->streaming && !send_frame(c->fd, WR_FRAME_CSI, s, len)) {
                    client_close(c);
                    refresh_led();
                }
            }
            unlock();
            csi_collector_return(s);
        }

        int64_t now = esp_timer_get_time();
        if (now >= next_status) {
            next_status = now + 1000000;
            wr_status_t st;
            build_status(&st);
            lock();
            for (int i = 0; i < MAX_CLIENTS; i++) {
                client_t *c = &s_clients[i];
                if (c->fd >= 0 && !send_frame(c->fd, WR_FRAME_STATUS, &st, sizeof(st))) {
                    client_close(c);
                    refresh_led();
                }
            }
            unlock();
        }
    }
}

// 1 Hz JSON broadcast so the phone can find us without knowing our IP.
static void announce_task(void *arg)
{
    int s = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_BROADCAST, &one, sizeof(one));
    struct sockaddr_in dst = {
        .sin_family = AF_INET,
        .sin_port = htons(CONFIG_WR_ANNOUNCE_PORT),
        .sin_addr.s_addr = htonl(INADDR_BROADCAST),
    };
    char json[256];
    while (true) {
        esp_netif_ip_info_t ip = { 0 };
        if (s_netif && esp_netif_get_ip_info(s_netif, &ip) == ESP_OK && ip.ip.addr != 0) {
            uint8_t mac[6] = { 0 };
            esp_wifi_get_mac(s_ifx, mac);
            int n = snprintf(json, sizeof(json),
                "{\"name\":\"WiFiRadar\",\"role\":\"%s\",\"mac\":\"" MACSTR "\",\"ip\":\"" IPSTR "\","
                "\"port\":%d,\"fw\":\"%s\",\"ssid\":\"%s\"}",
                wr_role_name(g_cfg.role), MAC2STR(mac), IP2STR(&ip.ip), CONFIG_WR_TCP_PORT,
                WR_FW_VERSION, g_cfg.ssid);
            sendto(s, json, n, 0, (struct sockaddr *)&dst, sizeof(dst));
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

// Sink for the phone's traffic-generator datagrams. The content is irrelevant:
// the radio frame that carried it is what produced a CSI sample.
static void ping_rx_task(void *arg)
{
    int s = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port = htons(CONFIG_WR_PING_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(s, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        ESP_LOGE(TAG, "ping sink bind failed: errno %d", errno);
        vTaskDelete(NULL);
        return;
    }
    char buf[64];
    while (true) {
        int n = recvfrom(s, buf, sizeof(buf), 0, NULL, NULL);
        if (n > 0) s_pings_rx++;
    }
}

esp_err_t net_server_start(esp_netif_t *netif, wifi_interface_t ifx)
{
    s_netif = netif;
    s_ifx = ifx;
    s_lock = xSemaphoreCreateMutex();
    for (int i = 0; i < MAX_CLIENTS; i++) s_clients[i].fd = -1;

    xTaskCreate(server_task,   "tcp_server", 4096, NULL, 5, NULL);
    xTaskCreate(stream_task,   "csi_stream", 6144, NULL, 8, NULL);
    xTaskCreate(announce_task, "announce",   3072, NULL, 3, NULL);
    xTaskCreate(ping_rx_task,  "ping_sink",  2560, NULL, 4, NULL);
    return ESP_OK;
}

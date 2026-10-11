// Wi-Fi Radar firmware for ESP32-WROOM (ESP-IDF v5.x)
//
// Roles (stored in NVS, see app_config.h):
//   single : softAP. The phone joins, sends UDP "pings"; every frame it transmits
//            gives us one CSI sample, which we stream back over TCP.
//   tx     : softAP + ESP-NOW broadcast pinger at a fixed rate (the illuminator).
//   rx     : station on the TX's AP. Collects CSI only from the TX's frames and
//            streams it to the phone, which is also joined to the TX's AP.
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "sdkconfig.h"
#include "app_config.h"
#include "csi_collector.h"
#include "net_server.h"
#include "ping_tx.h"
#include "status_io.h"

static const char *TAG = "main";
static esp_timer_handle_t s_reconnect_timer;

// Reconnect from a timer, never from inside the event loop: sleeping in a handler
// would stall esp_netif's own disconnect handling and every other event.
static void reconnect_cb(void *arg)
{
    esp_wifi_connect();
}

static void on_wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT) {
        switch (id) {
        case WIFI_EVENT_STA_START:
            esp_wifi_connect();
            break;
        case WIFI_EVENT_STA_CONNECTED: {
            wifi_event_sta_connected_t *e = data;
            // ssid is not NUL-terminated when it is exactly 32 bytes long
            ESP_LOGI(TAG, "joined %.*s (" MACSTR ") on channel %u", e->ssid_len, e->ssid, MAC2STR(e->bssid), e->channel);
            // Only the TX's frames are radar illumination; ignore everything else
            // unless the user pinned an explicit filter MAC.
            if (!g_cfg.filter_enabled) csi_collector_set_filter(true, e->bssid);
            status_io_set_connected(true);
            break;
        }
        case WIFI_EVENT_STA_DISCONNECTED: {
            wifi_event_sta_disconnected_t *e = data;
            ESP_LOGW(TAG, "disconnected (reason %d), retrying", e->reason);
            status_io_set_connected(false);
            esp_timer_start_once(s_reconnect_timer, 500 * 1000);
            break;
        }
        case WIFI_EVENT_AP_STACONNECTED: {
            wifi_event_ap_staconnected_t *e = data;
            ESP_LOGI(TAG, "station " MACSTR " joined", MAC2STR(e->mac));
            break;
        }
        case WIFI_EVENT_AP_STADISCONNECTED: {
            wifi_event_ap_stadisconnected_t *e = data;
            ESP_LOGI(TAG, "station " MACSTR " left", MAC2STR(e->mac));
            break;
        }
        default:
            break;
        }
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *e = data;
        ESP_LOGI(TAG, "got ip " IPSTR, IP2STR(&e->ip_info.ip));
    }
}

static esp_netif_t *start_softap(void)
{
    esp_netif_t *netif = esp_netif_create_default_wifi_ap();
    wifi_config_t wc = { 0 };
    strlcpy((char *)wc.ap.ssid, g_cfg.ssid, sizeof(wc.ap.ssid));
    wc.ap.ssid_len = strlen(g_cfg.ssid);
    wc.ap.channel = g_cfg.channel;
    wc.ap.max_connection = 4;
    wc.ap.beacon_interval = 100;
    if (strlen(g_cfg.password) >= 8) {
        strlcpy((char *)wc.ap.password, g_cfg.password, sizeof(wc.ap.password));
        wc.ap.authmode = WIFI_AUTH_WPA2_PSK;
    } else {
        wc.ap.authmode = WIFI_AUTH_OPEN;
    }
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &wc));
    // Drop 802.11b rates: a DSSS frame has no OFDM training field, so it can never
    // produce CSI. Forcing OFDM makes every frame from the phone useful.
    if (esp_wifi_config_11b_rate(WIFI_IF_AP, true) != ESP_OK) ESP_LOGW(TAG, "could not disable 11b rates");
    ESP_ERROR_CHECK(esp_wifi_start());
    // 20 MHz keeps the CSI layout fixed (64 sub-carriers per LTF)
    ESP_ERROR_CHECK(esp_wifi_set_bandwidth(WIFI_IF_AP, WIFI_BW_HT20));
    ESP_LOGI(TAG, "softAP \"%s\" on channel %u (%s)", g_cfg.ssid, g_cfg.channel,
             wc.ap.authmode == WIFI_AUTH_OPEN ? "open" : "WPA2");
    return netif;
}

static esp_netif_t *start_station(void)
{
    esp_netif_t *netif = esp_netif_create_default_wifi_sta();
    wifi_config_t wc = { 0 };
    strlcpy((char *)wc.sta.ssid, g_cfg.ssid, sizeof(wc.sta.ssid));
    strlcpy((char *)wc.sta.password, g_cfg.password, sizeof(wc.sta.password));
    wc.sta.threshold.authmode = strlen(g_cfg.password) >= 8 ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN;
    wc.sta.channel = g_cfg.channel;   // scan starts here; other channels are still tried
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wc));
    if (esp_wifi_config_11b_rate(WIFI_IF_STA, true) != ESP_OK) ESP_LOGW(TAG, "could not disable 11b rates");
    ESP_ERROR_CHECK(esp_wifi_start());
    // Modem sleep would make us miss TX pings and also corrupts rx_ctrl.timestamp
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
    ESP_LOGI(TAG, "station joining \"%s\"", g_cfg.ssid);
    return netif;
}

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    app_config_load();
    ESP_LOGI(TAG, "Wi-Fi Radar firmware %s, role=%s", WR_FW_VERSION, wr_role_name(g_cfg.role));
    status_io_start();

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));   // our NVS config is the source of truth
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, &on_wifi_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, &on_wifi_event, NULL));

    const esp_timer_create_args_t rt = { .callback = reconnect_cb, .name = "reconnect" };
    ESP_ERROR_CHECK(esp_timer_create(&rt, &s_reconnect_timer));

    esp_netif_t *netif;
    wifi_interface_t ifx;
    if (g_cfg.role == WR_ROLE_RX) {
        status_io_set_connected(false);
        netif = start_station();
        ifx = WIFI_IF_STA;
    } else {
        netif = start_softap();
        ifx = WIFI_IF_AP;
    }

    ESP_ERROR_CHECK(csi_collector_start());                       // must follow esp_wifi_start()
    if (g_cfg.role == WR_ROLE_TX) {
        ESP_ERROR_CHECK(ping_tx_start(WIFI_IF_AP, g_cfg.tx_rate_hz));
    }
    ESP_ERROR_CHECK(net_server_start(netif, ifx));
    ESP_LOGI(TAG, "ready: TCP %d, discovery UDP %d, ping sink UDP %d",
             CONFIG_WR_TCP_PORT, CONFIG_WR_ANNOUNCE_PORT, CONFIG_WR_PING_PORT);
}

#include "ping_tx.h"
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_now.h"
#include "esp_timer.h"
#include "esp_log.h"
#include "esp_idf_version.h"

static const char *TAG = "ping_tx";

static const uint8_t BROADCAST_MAC[6] = { 0xff, 0xff, 0xff, 0xff, 0xff, 0xff };
static SemaphoreHandle_t   s_tick;
static esp_timer_handle_t  s_timer;
static volatile uint32_t   s_count;

typedef struct __attribute__((packed)) {
    uint32_t magic;   // 'WRPG'
    uint32_t seq;
} ping_payload_t;

// esp_timer callbacks run in the high-priority esp_timer task; we only wake the
// sender so that a slow esp_now_send() can never delay other timers.
static void timer_cb(void *arg)
{
    xSemaphoreGive(s_tick);
}

static void tx_task(void *arg)
{
    ping_payload_t p = { .magic = 0x47505257, .seq = 0 };
    while (true) {
        if (xSemaphoreTake(s_tick, portMAX_DELAY) != pdTRUE) continue;
        p.seq++;
        if (esp_now_send(BROADCAST_MAC, (const uint8_t *)&p, sizeof(p)) == ESP_OK) {
            s_count++;
        }
    }
}

esp_err_t ping_tx_start(wifi_interface_t ifx, uint16_t rate_hz)
{
    ESP_ERROR_CHECK(esp_now_init());

    esp_now_peer_info_t peer = {
        .channel = 0,        // 0 = current channel of ifx
        .ifidx   = ifx,
        .encrypt = false,
    };
    memcpy(peer.peer_addr, BROADCAST_MAC, 6);
    ESP_ERROR_CHECK(esp_now_add_peer(&peer));

    // ESP-NOW defaults to 1 Mbps DSSS (802.11b). 11b frames carry no OFDM training
    // field, so the receiver would never get CSI. Force HT20 MCS0: the frame then
    // contains both the legacy LTF and the HT-LTF.
#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(5, 1, 0)
    esp_now_rate_config_t rate_cfg = {
        .phymode = WIFI_PHY_MODE_HT20,
        .rate    = WIFI_PHY_RATE_MCS0_LGI,
    };
    ESP_ERROR_CHECK(esp_now_set_peer_rate_config(BROADCAST_MAC, &rate_cfg));
#else
    ESP_ERROR_CHECK(esp_wifi_config_espnow_rate(ifx, WIFI_PHY_RATE_MCS0_LGI));
#endif

    s_tick = xSemaphoreCreateBinary();
    xTaskCreate(tx_task, "ping_tx", 3072, NULL, 10, NULL);

    const esp_timer_create_args_t targs = { .callback = timer_cb, .name = "ping_tx" };
    ESP_ERROR_CHECK(esp_timer_create(&targs, &s_timer));
    ping_tx_set_rate(rate_hz);
    return ESP_OK;
}

void ping_tx_set_rate(uint16_t rate_hz)
{
    if (rate_hz < 1) rate_hz = 1;
    if (rate_hz > 500) rate_hz = 500;
    esp_timer_stop(s_timer);  // harmless if not running
    ESP_ERROR_CHECK(esp_timer_start_periodic(s_timer, 1000000ULL / rate_hz));
    ESP_LOGI(TAG, "broadcasting ESP-NOW pings at %u Hz", rate_hz);
}

uint32_t ping_tx_get_count(void)
{
    return s_count;
}

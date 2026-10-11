#include "csi_collector.h"
#include "app_config.h"
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/ringbuf.h"
#include "esp_wifi.h"
#include "esp_mac.h"
#include "esp_log.h"

static const char *TAG = "csi";

// ~200 LLTF-only samples (~2 s at 100 Hz). The TCP task normally drains it within a
// millisecond; the head-room covers the ~1.5 s it takes lwIP to time out a dead client.
#define RING_SIZE (32 * 1024)

static RingbufHandle_t  s_rb;
static wr_csi_stats_t   s_stats;
static uint32_t         s_seq;
static volatile bool    s_filter_enabled;
static uint8_t          s_filter_mac[6];
static uint8_t          s_cfg_flags;   // cached WR_CSI_FLAG_* for the current config

// Runs in the Wi-Fi driver task for every received frame that passed the MAC's
// address filter. Keep it short: copy out, enqueue, return. No logging here.
static void csi_rx_cb(void *ctx, wifi_csi_info_t *info)
{
    if (!info || !info->buf || info->len == 0) return;

    if (s_filter_enabled && memcmp(info->mac, s_filter_mac, 6) != 0) {
        s_stats.filtered++;
        return;
    }

    // Single writer (the Wi-Fi task), so a static staging buffer is safe.
    static wr_csi_sample_t sample;
    uint16_t len = info->len > WR_CSI_MAX_LEN ? WR_CSI_MAX_LEN : info->len;
    const wifi_pkt_rx_ctrl_t *rx = &info->rx_ctrl;

    sample.seq               = ++s_seq;
    sample.timestamp_us      = rx->timestamp;
    memcpy(sample.mac, info->mac, 6);
    sample.rssi              = rx->rssi;
    sample.noise_floor       = rx->noise_floor;
    sample.channel           = rx->channel;
    sample.secondary_channel = rx->secondary_channel;
    sample.sig_mode          = rx->sig_mode;
    sample.mcs               = rx->mcs;
    sample.cwb               = rx->cwb;
    sample.rate              = rx->rate;
    sample.stbc              = rx->stbc;
    sample.sgi               = rx->sgi;
    sample.ant               = rx->ant;
    sample.flags             = s_cfg_flags | (info->first_word_invalid ? WR_CSI_FLAG_FIRST_WORD_INVALID : 0);
    sample.csi_len           = len;
    memcpy(sample.csi, info->buf, len);

    if (xRingbufferSend(s_rb, &sample, WR_CSI_SAMPLE_HDR_LEN + len, 0) == pdTRUE) {
        s_stats.total++;
    } else {
        s_stats.dropped++;
    }
}

esp_err_t csi_collector_apply_config(void)
{
    wifi_csi_config_t csi_cfg = {
        .lltf_en           = g_cfg.csi_lltf,
        .htltf_en          = g_cfg.csi_htltf,
        .stbc_htltf2_en    = g_cfg.csi_stbc,
        .ltf_merge_en      = true,
        // Off: we want each sub-carrier's raw estimate, not a smoothed version.
        .channel_filter_en = false,
        .manu_scale        = false,
        .shift             = 0,
    };
    s_cfg_flags = (g_cfg.csi_lltf ? WR_CSI_FLAG_LLTF : 0)
                | (g_cfg.csi_htltf ? WR_CSI_FLAG_HTLTF : 0)
                | (g_cfg.csi_stbc ? WR_CSI_FLAG_STBC_HTLTF : 0)
                | WR_CSI_FLAG_LTF_MERGED;
    esp_err_t err = esp_wifi_set_csi_config(&csi_cfg);
    if (err != ESP_OK) ESP_LOGE(TAG, "set_csi_config failed: %s", esp_err_to_name(err));
    return err;
}

void csi_collector_set_filter(bool enabled, const uint8_t mac[6])
{
    if (enabled && mac) memcpy(s_filter_mac, mac, 6);
    s_filter_enabled = enabled;
    if (enabled) {
        ESP_LOGI(TAG, "MAC filter " MACSTR, MAC2STR(s_filter_mac));
    } else {
        ESP_LOGI(TAG, "MAC filter off");
    }
}

esp_err_t csi_collector_start(void)
{
    s_rb = xRingbufferCreate(RING_SIZE, RINGBUF_TYPE_NOSPLIT);
    if (!s_rb) return ESP_ERR_NO_MEM;

    // Only apply an explicit user filter here; the rx role may already have pinned
    // the TX's BSSID from the STA_CONNECTED event before we got this far.
    if (g_cfg.filter_enabled) csi_collector_set_filter(true, g_cfg.filter_mac);
    ESP_ERROR_CHECK(csi_collector_apply_config());
    ESP_ERROR_CHECK(esp_wifi_set_csi_rx_cb(csi_rx_cb, NULL));
    // Fails with ESP_ERR_NOT_SUPPORTED unless CONFIG_ESP_WIFI_CSI_ENABLED=y
    ESP_ERROR_CHECK(esp_wifi_set_csi(true));
    ESP_LOGI(TAG, "CSI collection enabled");
    return ESP_OK;
}

wr_csi_sample_t *csi_collector_receive(size_t *len, uint32_t timeout_ms)
{
    return (wr_csi_sample_t *)xRingbufferReceive(s_rb, len, pdMS_TO_TICKS(timeout_ms));
}

void csi_collector_return(wr_csi_sample_t *item)
{
    vRingbufferReturnItem(s_rb, item);
}

void csi_collector_get_stats(wr_csi_stats_t *out)
{
    *out = s_stats;
}

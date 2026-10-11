#include "app_config.h"
#include <string.h>
#include <stdio.h>
#include <strings.h>
#include "nvs.h"
#include "esp_log.h"
#include "sdkconfig.h"

static const char *TAG = "config";
static const char *NVS_NS = "wr";

wr_config_t g_cfg;

static void set_defaults(void)
{
    memset(&g_cfg, 0, sizeof(g_cfg));
    g_cfg.role = WR_ROLE_SINGLE;
    strlcpy(g_cfg.ssid, CONFIG_WR_DEFAULT_SSID, sizeof(g_cfg.ssid));
    strlcpy(g_cfg.password, CONFIG_WR_DEFAULT_PASSWORD, sizeof(g_cfg.password));
    g_cfg.channel = CONFIG_WR_DEFAULT_CHANNEL;
    g_cfg.tx_rate_hz = CONFIG_WR_DEFAULT_TX_RATE_HZ;
    // LLTF-only keeps the layout fixed at 64 sub-carriers (128 bytes) regardless
    // of whether the peer sends 11g or 11n frames. The phone can enable more.
    g_cfg.csi_lltf = true;
    g_cfg.csi_htltf = false;
    g_cfg.csi_stbc = false;
    g_cfg.filter_enabled = false;
}

esp_err_t app_config_load(void)
{
    set_defaults();
    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NS, NVS_READONLY, &h);
    if (err != ESP_OK) {
        ESP_LOGI(TAG, "no stored config, using defaults");
        return ESP_OK;
    }
    uint8_t u8; uint16_t u16; size_t len;
    if (nvs_get_u8(h, "role", &u8) == ESP_OK && u8 < WR_ROLE_COUNT) g_cfg.role = (wr_role_t)u8;
    len = sizeof(g_cfg.ssid);     nvs_get_str(h, "ssid", g_cfg.ssid, &len);
    len = sizeof(g_cfg.password); nvs_get_str(h, "pass", g_cfg.password, &len);
    if (nvs_get_u8(h, "chan", &u8) == ESP_OK && u8 >= 1 && u8 <= 13) g_cfg.channel = u8;
    if (nvs_get_u16(h, "rate", &u16) == ESP_OK && u16 >= 1 && u16 <= 500) g_cfg.tx_rate_hz = u16;
    if (nvs_get_u8(h, "csi", &u8) == ESP_OK) {
        g_cfg.csi_lltf = u8 & 1; g_cfg.csi_htltf = u8 & 2; g_cfg.csi_stbc = u8 & 4;
    }
    uint8_t filt[7]; len = sizeof(filt);
    if (nvs_get_blob(h, "filt", filt, &len) == ESP_OK && len == 7) {
        g_cfg.filter_enabled = filt[0] != 0;
        memcpy(g_cfg.filter_mac, &filt[1], 6);
    }
    nvs_close(h);
    ESP_LOGI(TAG, "loaded: role=%s ssid=%s chan=%u rate=%u csi=%d%d%d",
             wr_role_name(g_cfg.role), g_cfg.ssid, g_cfg.channel, g_cfg.tx_rate_hz,
             g_cfg.csi_lltf, g_cfg.csi_htltf, g_cfg.csi_stbc);
    return ESP_OK;
}

esp_err_t app_config_save(void)
{
    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NS, NVS_READWRITE, &h);
    if (err != ESP_OK) return err;
    nvs_set_u8(h, "role", (uint8_t)g_cfg.role);
    nvs_set_str(h, "ssid", g_cfg.ssid);
    nvs_set_str(h, "pass", g_cfg.password);
    nvs_set_u8(h, "chan", g_cfg.channel);
    nvs_set_u16(h, "rate", g_cfg.tx_rate_hz);
    nvs_set_u8(h, "csi", (g_cfg.csi_lltf ? 1 : 0) | (g_cfg.csi_htltf ? 2 : 0) | (g_cfg.csi_stbc ? 4 : 0));
    uint8_t filt[7] = { g_cfg.filter_enabled ? 1 : 0 };
    memcpy(&filt[1], g_cfg.filter_mac, 6);
    nvs_set_blob(h, "filt", filt, sizeof(filt));
    err = nvs_commit(h);
    nvs_close(h);
    return err;
}

const char *wr_role_name(wr_role_t role)
{
    switch (role) {
        case WR_ROLE_SINGLE: return "single";
        case WR_ROLE_TX:     return "tx";
        case WR_ROLE_RX:     return "rx";
        default:             return "?";
    }
}

bool wr_role_parse(const char *s, wr_role_t *out)
{
    if (!strcasecmp(s, "single")) { *out = WR_ROLE_SINGLE; return true; }
    if (!strcasecmp(s, "tx"))     { *out = WR_ROLE_TX;     return true; }
    if (!strcasecmp(s, "rx"))     { *out = WR_ROLE_RX;     return true; }
    return false;
}

bool wr_parse_mac(const char *s, uint8_t out[6])
{
    unsigned v[6];
    if (sscanf(s, "%2x:%2x:%2x:%2x:%2x:%2x", &v[0], &v[1], &v[2], &v[3], &v[4], &v[5]) != 6) return false;
    for (int i = 0; i < 6; i++) out[i] = (uint8_t)v[i];
    return true;
}

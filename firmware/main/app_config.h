#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#define WR_FW_VERSION    "1.0.0"
#define WR_PROTO_VERSION 1

// Device role. Stored in NVS, changed via the TCP "SET_ROLE" command or by
// holding the BOOT button for 3 s. A reboot applies the new role.
typedef enum {
    WR_ROLE_SINGLE = 0,  // softAP. CSI comes from whatever the phone transmits to us.
    WR_ROLE_TX     = 1,  // softAP + ESP-NOW broadcast pinger. Also collects CSI like SINGLE.
    WR_ROLE_RX     = 2,  // Station joined to the TX's AP. CSI only from the TX's frames.
    WR_ROLE_COUNT
} wr_role_t;

typedef struct {
    wr_role_t role;
    char      ssid[33];
    char      password[65];
    uint8_t   channel;         // 1..13, used by AP roles (the RX follows the AP)
    uint16_t  tx_rate_hz;      // ESP-NOW ping rate for the TX role
    bool      csi_lltf;        // capture legacy LTF (64 sub-carriers @20 MHz)
    bool      csi_htltf;       // capture HT-LTF (adds 64 more for 11n frames)
    bool      csi_stbc;        // capture STBC-HT-LTF
    bool      filter_enabled;  // only accept CSI from filter_mac
    uint8_t   filter_mac[6];
} wr_config_t;

extern wr_config_t g_cfg;

esp_err_t   app_config_load(void);
esp_err_t   app_config_save(void);
const char *wr_role_name(wr_role_t role);
bool        wr_role_parse(const char *s, wr_role_t *out);
bool        wr_parse_mac(const char *s, uint8_t out[6]);

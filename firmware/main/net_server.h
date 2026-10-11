#pragma once
#include <stdint.h>
#include "esp_err.h"
#include "esp_netif.h"
#include "esp_wifi.h"

// ---- Wire protocol (ESP32 -> phone), see docs/PROTOCOL.md -------------------
// Every message is a frame:  uint16 magic (0xC51A) | uint8 type | uint8 proto_version |
//                            uint16 payload_len | payload
#define WR_FRAME_MAGIC  0xC51A
#define WR_FRAME_CSI    1   // payload = wr_csi_sample_t header (28 B) + csi bytes
#define WR_FRAME_STATUS 2   // payload = wr_status_t, sent once per second
#define WR_FRAME_TEXT   3   // payload = UTF-8 text (command replies, "INFO {json}")

typedef struct __attribute__((packed)) {
    uint32_t uptime_s;
    uint32_t csi_total;
    uint32_t csi_dropped;
    uint32_t csi_filtered;
    uint32_t pings_rx;     // UDP traffic-generator packets received (single/tx roles)
    uint32_t tx_count;     // ESP-NOW pings sent (tx role)
    uint32_t free_heap;
    uint8_t  role;
    uint8_t  channel;
    int8_t   rssi;         // RSSI of the joined AP (rx role), else 0
    uint8_t  sta_count;    // associated stations (ap roles)
    uint16_t tx_rate_hz;
    uint16_t reserved;
} wr_status_t;             // 36 bytes

// Starts: TCP server (stream + newline-delimited text commands), the CSI stream
// task, a 1 Hz UDP discovery broadcast and a UDP sink for traffic-generator pings.
esp_err_t net_server_start(esp_netif_t *netif, wifi_interface_t ifx);

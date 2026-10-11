#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include "esp_err.h"

// Worst case for ESP32: HT40 + STBC with LLTF, HT-LTF and STBC-HT-LTF all enabled.
#define WR_CSI_MAX_LEN 640

// Flags byte in wr_csi_sample_t
#define WR_CSI_FLAG_FIRST_WORD_INVALID 0x01  // first 2 sub-carriers (4 bytes) are garbage
#define WR_CSI_FLAG_LLTF               0x02
#define WR_CSI_FLAG_HTLTF              0x04
#define WR_CSI_FLAG_STBC_HTLTF         0x08
#define WR_CSI_FLAG_LTF_MERGED         0x10

// One CSI sample exactly as it is streamed to the phone (little-endian, packed).
// csi[] holds int8 pairs: [imag, real] per sub-carrier, in the hardware's FFT order
// (sub-carrier 0..31 then -32..-1 for a 20 MHz LLTF). See docs/PROTOCOL.md.
typedef struct __attribute__((packed)) {
    uint32_t seq;               // monotonically increasing per boot
    uint32_t timestamp_us;      // Wi-Fi MAC local time of reception (32-bit microseconds)
    uint8_t  mac[6];            // transmitter address of the received frame
    int8_t   rssi;              // dBm
    int8_t   noise_floor;       // dBm
    uint8_t  channel;           // primary channel
    uint8_t  secondary_channel; // 0 none, 1 above, 2 below
    uint8_t  sig_mode;          // 0 non-HT (11g), 1 HT (11n), 3 VHT
    uint8_t  mcs;               // valid for HT frames
    uint8_t  cwb;               // 0 = 20 MHz, 1 = 40 MHz
    uint8_t  rate;              // PHY rate code, valid for non-HT frames
    uint8_t  stbc;
    uint8_t  sgi;
    uint8_t  ant;               // RX antenna
    uint8_t  flags;             // WR_CSI_FLAG_*
    uint16_t csi_len;           // number of valid bytes in csi[]
    int8_t   csi[WR_CSI_MAX_LEN];
} wr_csi_sample_t;

#define WR_CSI_SAMPLE_HDR_LEN offsetof(wr_csi_sample_t, csi)   // 28 bytes

typedef struct {
    uint32_t total;     // samples accepted into the ring buffer
    uint32_t dropped;   // samples lost because the consumer was too slow
    uint32_t filtered;  // frames rejected by the MAC filter
} wr_csi_stats_t;

esp_err_t        csi_collector_start(void);                 // call after esp_wifi_start()
esp_err_t        csi_collector_apply_config(void);          // re-reads lltf/htltf/stbc from g_cfg
void             csi_collector_set_filter(bool enabled, const uint8_t mac[6]);
// Blocks up to timeout_ms for the next sample. Returns NULL on timeout.
// The returned pointer must be handed back with csi_collector_return().
wr_csi_sample_t *csi_collector_receive(size_t *len, uint32_t timeout_ms);
void             csi_collector_return(wr_csi_sample_t *item);
void             csi_collector_get_stats(wr_csi_stats_t *out);

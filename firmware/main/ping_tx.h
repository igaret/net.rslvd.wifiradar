#pragma once
#include <stdint.h>
#include "esp_err.h"
#include "esp_wifi.h"

// TX role: broadcasts a tiny ESP-NOW frame at a fixed rate. Every frame the RX
// hears yields one CSI sample, so this sets the radar's sampling rate.
esp_err_t ping_tx_start(wifi_interface_t ifx, uint16_t rate_hz);
void      ping_tx_set_rate(uint16_t rate_hz);
uint32_t  ping_tx_get_count(void);

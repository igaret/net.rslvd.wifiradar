# Wi-Fi Radar ESP32 firmware

ESP-IDF v5.3 project. `idf.py set-target esp32 && idf.py build && idf.py -p PORT flash monitor`.

Roles (NVS-persisted; `SET_ROLE` over TCP or hold BOOT 3 s):
* `single` — softAP; CSI from whatever the phone transmits (the app sends UDP pings to port 5006).
* `tx` — softAP + ESP-NOW broadcast pings at `tx_rate_hz` (esp_timer paced); also collects CSI like `single`.
* `rx` — station on the TX's AP; CSI filtered to the TX's BSSID; streams to the phone (also on the TX's AP).

Ports: TCP 5000 (stream + commands), UDP 5005 (discovery broadcast), UDP 5006 (ping sink). See `../docs/PROTOCOL.md`.

Pre-built images in `prebuilt/` (bootloader @0x1000, partition table @0x8000, app @0x10000; merged image @0x0).

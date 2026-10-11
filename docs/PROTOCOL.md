# Wi-Fi Radar — ESP32 ↔ Android wire protocol (v1)

Transport: **TCP, port 5000** (ESP32 is the server). Discovery: **UDP broadcast, port 5005**.
Traffic generator (single-device mode): **UDP, port 5006** (phone → ESP32, content ignored).
All integers are **little-endian**.

## 1. Discovery announce (ESP32 → broadcast 255.255.255.255:5005, 1 Hz)
```json
{"name":"WiFiRadar","role":"single|tx|rx","mac":"aa:bb:cc:dd:ee:ff","ip":"192.168.4.1","port":5000,"fw":"1.0.0","ssid":"WiFiRadar"}
```

## 2. TCP frames (ESP32 → phone)
```
offset size  field
0      2     magic        0xC51A  (bytes on the wire: 1A C5)
2      1     type         1 = CSI, 2 = STATUS, 3 = TEXT
3      1     proto_ver    1
4      2     payload_len
6      n     payload
```

### 2.1 CSI payload (type 1) — 28-byte header + csi bytes
```
0   u32  seq                 per-boot counter
4   u32  timestamp_us        Wi-Fi MAC local clock (wraps every 71 min)
8   u8[6] mac                transmitter address of the received frame
14  i8   rssi                dBm
15  i8   noise_floor         dBm
16  u8   channel             primary channel
17  u8   secondary_channel   0 none, 1 above, 2 below
18  u8   sig_mode            0 = non-HT (11g), 1 = HT (11n), 3 = VHT
19  u8   mcs
20  u8   cwb                 0 = 20 MHz, 1 = 40 MHz
21  u8   rate                PHY rate code (non-HT only)
22  u8   stbc
23  u8   sgi
24  u8   ant
25  u8   flags               bit0 first_word_invalid, bit1 LLTF present, bit2 HT-LTF present,
                             bit3 STBC-HT-LTF present, bit4 LTF merge enabled
26  u16  csi_len             bytes that follow
28  i8[csi_len] csi          pairs of (imag, real) int8 per sub-carrier, FFT order
```
FFT order for a 20 MHz LTF of 64 entries: indices 0..31 = sub-carriers 0..31, indices 32..63 =
sub-carriers −32..−1. Segment layout when several LTF types are enabled (ESP32 TRM / ESP-IDF docs):

| cwb | sig_mode | stbc | LLTF | HT-LTF | STBC-HT-LTF | total bytes |
|-----|----------|------|------|--------|-------------|-------------|
| 20  | non-HT   | –    | 64   | –      | –           | 128 |
| 20  | HT       | 0    | 64   | 64     | –           | 256 |
| 20  | HT       | 1    | 64   | 64     | 64          | 384 |
| 40  | HT       | 0    | 64   | 128    | –           | 384 |
| 40  | HT       | 1    | 64   | 128    | 128         | 640 |

Default firmware config is **LLTF only** → always 128 bytes / 64 sub-carriers, of which ±1..±26
(52) carry energy; DC and the guard band are null.

### 2.2 STATUS payload (type 2, every second, 36 bytes)
```
0  u32 uptime_s     4  u32 csi_total   8  u32 csi_dropped  12 u32 csi_filtered
16 u32 pings_rx     20 u32 tx_count    24 u32 free_heap
28 u8 role (0 single,1 tx,2 rx)  29 u8 channel  30 i8 rssi  31 u8 sta_count
32 u16 tx_rate_hz   34 u16 reserved
```

### 2.3 TEXT payload (type 3)
UTF-8, one command reply: `OK <CMD> ...`, `ERR <CMD> <reason>` or `INFO {json}`.

## 3. Commands (phone → ESP32, ASCII lines terminated by `\n`)
| Command | Effect |
|---|---|
| `INFO` | reply `INFO {...}` with role, mac, ip, channel, rate, csi config, stats |
| `START` / `STOP` | enable / disable CSI frames for this connection (default: enabled) |
| `SET_ROLE single\|tx\|rx` | persist role; takes effect after `REBOOT` |
| `SET_RATE <1..500>` | TX ping rate in Hz (applied live in tx role) |
| `SET_WIFI <ssid> [password]` | AP credentials (ap roles) / target network (rx role); reboot to apply |
| `SET_CHANNEL <1..13>` | AP channel; reboot to apply |
| `SET_CSI <lltf> <htltf> <stbc>` | 0/1 each, applied live |
| `SET_FILTER <mac\|none>` | only stream CSI from this transmitter |
| `REBOOT` | restart |

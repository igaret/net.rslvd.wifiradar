# Wi-Fi Radar — CSI-based motion / presence sensing

Three deliverables in one tree:

| Dir | Deliverable | What it is |
|---|---|---|
| `firmware/` | 3a | ESP-IDF v5.3 firmware for ESP32-WROOM-32: CSI capture + TCP streaming, three roles (single / tx / rx). Pre-built binaries in `firmware/prebuilt/`. |
| `android/app-esp32/` | 2 + 3b | Android app (API 26+, non-rooted): discovers ESP32s, streams CSI over Wi-Fi/TCP, single or dual-ESP32 mode, live graph + waterfall, motion detector, CSV/JSONL export, **and** a USB-OTG firmware flasher (esptool ROM protocol in Kotlin, bundled `.bin`). |
| `android/app-rooted/` | 1 | Android app (API 26+, **rooted Broadcom phones with Nexmon CSI**): drives `nexutil`, receives the firmware's CSI UDP frames, same visualisation/detector/export. |
| `android/csi-core/` | shared | Frame model, DSP (amplitude/phase sanitising), motion detector, recorder/export, heatmap + line views, synthetic demo source. |
| `docs/PROTOCOL.md` | — | ESP32 ↔ Android wire protocol. |

## Quick start (ESP32 path)

1. **Flash** an ESP32-WROOM dev board. Either
   * on a PC: `cd firmware && idf.py -p /dev/ttyUSB0 flash` (ESP-IDF v5.3.x), or
     `esptool.py --chip esp32 write_flash 0x0 firmware/prebuilt/wifi_radar_merged_0x0.bin`, or
   * from the phone: open the ESP32 app → *ESP32 firmware flasher* → connect the board through a USB-OTG adapter → *Flash firmware*.
2. The board boots in **single** role as access point `WiFiRadar` / `wifiradar123` (channel 6).
   LED (GPIO 2): 1 blink per 2 s = single, 2 = tx, 3 = rx; fast blink = streaming.
3. In the ESP32 app: *Join ESP32 AP* (or join it from system Wi-Fi settings and tap *Bind Wi-Fi*),
   wait for the device to appear under *Discovered ESP32s*, tap it. Walk around: the waterfall ripples and the
   motion score rises.
4. **Dual mode** (better sensitivity): flash two boards. On one, send `SET_ROLE tx` then `REBOOT` from the
   device card (or hold BOOT for 3 s until the LED pattern changes to 2 blinks). On the other, `SET_ROLE rx`
   + `REBOOT`. The RX joins the TX's AP automatically. Switch the app to *Dual*, join `WiFiRadar`, connect to the
   device shown with role **RX**. The sample-rate slider now sets the TX's ESP-NOW ping rate.
5. *Start recording* → *Export last* to share a CSV / JSON-Lines capture.

## Building

```bash
# Firmware (ESP-IDF v5.3.x; CONFIG_ESP_WIFI_CSI_ENABLED is set in sdkconfig.defaults)
cd firmware && idf.py set-target esp32 && idf.py build
#   copy build/bootloader/bootloader.bin build/partition_table/partition-table.bin build/wifi_radar.bin
#   into android/app-esp32/src/main/assets/firmware/ to update the bundled image (keep manifest.json offsets)

# Android (JDK 17, Android SDK platform 35 / build-tools 35.0.0)
cd android && ./gradlew assembleDebug
#   app-esp32/build/outputs/apk/debug/app-esp32-debug.apk
#   app-rooted/build/outputs/apk/debug/app-rooted-debug.apk
```

## Dependencies

| Component | Dependency | Version |
|---|---|---|
| Firmware | ESP-IDF | v5.3.2 (any 5.1+ should work; `esp_now_set_peer_rate_config` guarded by version) |
| Android | Android Gradle Plugin / Gradle | 8.7.3 / 8.9 |
| Android | Kotlin | 2.0.21 |
| Android | androidx.core-ktx / appcompat / activity-ktx | 1.13.1 / 1.7.0 / 1.9.3 |
| Android | androidx.lifecycle (viewmodel-ktx, runtime-ktx) | 2.8.6 |
| Android | Material Components | 1.12.0 |
| Android | kotlinx-coroutines-android | 1.8.1 |
| app-esp32 | usb-serial-for-android (`com.github.mik3y`, JitPack) | 3.8.1 |
| app-rooted | Nexmon + nexmon_csi (installed on the phone, not bundled) | chip-specific patch, see table below |

## Rooted-app device compatibility

CSI extraction on a phone's own chipset requires firmware that exposes it. The only open-source route is
**Nexmon CSI** for a handful of Broadcom FullMAC chips:

| Chip | Device | nexmon_csi patch | API 26+ route |
|---|---|---|---|
| bcm4339 | Nexus 5 (hammerhead) | `bcm4339/6_37_34_43` | LineageOS 15.1/16 (stock tops out at Android 6) |
| bcm4358 | Nexus 6P (angler) | `bcm4358/7_112_300_14_sta` | stock Android 8.1 or LineageOS |
| bcm43455c0 | Raspberry Pi 3B+/4B | `bcm43455c0/7_45_189` | not Android (parser compatible) |
| bcm4366c0 | Asus RT-AC86U | `bcm4366c0/10_10_122_20` | not Android |

**Not supported, and no public tool exists:** every Qualcomm chip (WCN39xx/WCN68xx, QCA6x9x), modern Broadcom
(bcm4375/4389 in recent Pixels/Galaxies), MediaTek, Samsung Exynos Wi-Fi; use the ESP32 path instead. Installing Nexmon (patched `fw_bcmdhd.bin` + `nexutil`) is a
one-time manual step following the nexmon_csi README; the app checks for it (*Check device*) and does the rest.

## Hardware limitations / known issues

* **ESP32 CSI is amplitude-only in practice.** Phase has a random per-packet offset and slope (no CFO/SFO
  calibration, no RF chain phase lock). The apps show a *sanitised* phase (unwrapped, linear trend removed) which
  is still noisy; detection uses normalised amplitude variance.
* **AGC:** absolute amplitude depends on RSSI/gain. The detector normalises each frame by its mean amplitude.
* **Only OFDM frames carry CSI.** 802.11b (DSSS) frames have no OFDM training field. The firmware disables 11b
  rates on its interfaces and forces ESP-NOW pings to HT20 MCS0 for this reason.
* **ESP32 receives CSI only for frames addressed to it or broadcast.** Hence the illuminator designs: phone→ESP32
  UDP pings (single) or TX ESP-NOW broadcasts (dual). Beacons from the TX add ~10 extra samples/s in rx role.
* `first_word_invalid`: the ESP32 marks the first 4 CSI bytes (sub-carriers 0 and 1) as garbage on some frames;
  they are zeroed and excluded.
* **Sampling jitter:** the phone's UDP sender (single mode) is scheduled by Android — expect ±2–5 ms jitter, worse
  with the screen off. Dual mode with an esp_timer-paced TX is far more regular.
* 2.4 GHz only, 20 MHz forced (64 sub-carriers, 52 used) to keep the layout fixed. Enabling HT-LTF (`SET_CSI 1 1 0`)
  yields 128/256-byte frames; the apps display the LLTF segment and export everything.
* Android ≥ 10 routes traffic to mobile data when Wi-Fi has no internet; the ESP32 app binds its sockets to the
  Wi-Fi network (`bindProcessToNetwork`). Joining via *Join ESP32 AP* uses `WifiNetworkSpecifier` (system dialog).
* Flasher: no-stub ROM protocol only (no compression, no MD5 verify); ~25 s for the 800 KB image at 460800 baud.
  Boards without the DTR/RTS auto-reset circuit need BOOT held while tapping EN.

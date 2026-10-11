#include "status_io.h"
#include "app_config.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_system.h"
#include "sdkconfig.h"

static const char *TAG = "status_io";
static volatile bool s_streaming;
static volatile bool s_connected = true;

static void led_write(bool on)
{
#if CONFIG_WR_LED_GPIO >= 0
    gpio_set_level(CONFIG_WR_LED_GPIO, on);
#endif
}

static void io_task(void *arg)
{
    const TickType_t tick = pdMS_TO_TICKS(50);
    uint32_t t = 0;              // 50 ms ticks
    uint32_t held_ticks = 0;
    const uint32_t blinks = (uint32_t)g_cfg.role + 1;

    while (true) {
        // --- LED pattern ---
        bool on;
        if (s_streaming) {
            on = (t / 2) % 2;                       // 5 Hz
        } else if (!s_connected) {
            on = (t / 10) % 2;                      // slow 1 Hz blink: rx not yet joined
        } else {
            uint32_t phase = t % 40;                // 2 s cycle, 200 ms per blink slot
            on = (phase < blinks * 4) && ((phase % 4) < 2);
        }
        led_write(on);

        // --- Button: long press cycles role ---
#if CONFIG_WR_BUTTON_GPIO >= 0
        if (gpio_get_level(CONFIG_WR_BUTTON_GPIO) == 0) {
            if (++held_ticks == 60) {               // 3 s
                g_cfg.role = (g_cfg.role + 1) % WR_ROLE_COUNT;
                ESP_LOGW(TAG, "button: switching role to %s, rebooting", wr_role_name(g_cfg.role));
                app_config_save();
                for (int i = 0; i < 10; i++) { led_write(i & 1); vTaskDelay(pdMS_TO_TICKS(60)); }
                esp_restart();
            }
        } else {
            held_ticks = 0;
        }
#endif
        t++;
        vTaskDelay(tick);
    }
}

void status_io_start(void)
{
#if CONFIG_WR_LED_GPIO >= 0
    gpio_config_t led = { .pin_bit_mask = 1ULL << CONFIG_WR_LED_GPIO, .mode = GPIO_MODE_OUTPUT };
    gpio_config(&led);
#endif
#if CONFIG_WR_BUTTON_GPIO >= 0
    gpio_config_t btn = { .pin_bit_mask = 1ULL << CONFIG_WR_BUTTON_GPIO, .mode = GPIO_MODE_INPUT, .pull_up_en = GPIO_PULLUP_ENABLE };
    gpio_config(&btn);
#endif
    xTaskCreate(io_task, "status_io", 2560, NULL, 2, NULL);
}

void status_io_set_streaming(bool streaming) { s_streaming = streaming; }
void status_io_set_connected(bool connected) { s_connected = connected; }

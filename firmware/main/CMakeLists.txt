idf_component_register(
    SRCS "main.c" "app_config.c" "csi_collector.c" "net_server.c" "ping_tx.c" "status_io.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES nvs_flash esp_wifi esp_netif esp_event lwip driver esp_timer esp_ringbuf
)

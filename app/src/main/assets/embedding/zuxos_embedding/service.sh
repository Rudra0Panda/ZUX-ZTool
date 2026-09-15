#!/system/bin/sh

MODDIR=${0%/*}

# Module service script
while true; do
    # Periodically check if configuration file exists
    if [ ! -f "/data/system/zui/embedding/embedding_config.json" ]; then
        cp -f $MODDIR/embedding_config.json /data/system/zui/embedding/embedding_config.json
        chmod 0644 /data/system/zui/embedding/embedding_config.json
        chown system:system /data/system/zui/embedding/embedding_config.json
    fi
    sleep 60
done

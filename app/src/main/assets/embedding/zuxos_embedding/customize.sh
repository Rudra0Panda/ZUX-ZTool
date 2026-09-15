#!/system/bin/sh

# Magisk module installation script
MODDIR=${0%/*}

if ! command -v ui_print >/dev/null 2>&1; then
  ui_print() { echo "$1"; }
fi

ui_print "********************************"
ui_print "ZUXOS Parallel View Module Setup"
ui_print "Ported from [HyperOS Perfect Landscape Plan]"
ui_print "********************************"
ui_print "- Adapted app count: 3111"
ui_print "- Target path: /data/system/zui/embedding/"
ui_print "********************************"

# Create destination directory
TARGET_DIR="/data/system/zui/embedding"
mkdir -p $TARGET_DIR 2>/dev/null
# Set directory permissions
chmod 0755 $TARGET_DIR 2>/dev/null
chown system:system $TARGET_DIR 2>/dev/null
# Copy configuration file
cp -f $MODDIR/embedding_config.json $TARGET_DIR/embedding_config.json
# Set file permissions
chmod 0644 $TARGET_DIR/embedding_config.json 2>/dev/null
chown system:system $TARGET_DIR/embedding_config.json 2>/dev/null
ui_print "Configuration installed to: $TARGET_DIR/embedding_config.json"
ui_print "Installation complete! Takes effect after reboot."
#!/system/bin/sh

if ! command -v ui_print >/dev/null 2>&1; then
  ui_print() { echo "$1"; }
fi

ui_print "Uninstalling ZUXOS Parallel View configuration module..."

# Remove configuration file
rm -f /data/system/zui/embedding/embedding_config.json

ui_print "Uninstallation complete"

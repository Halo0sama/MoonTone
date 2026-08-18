#!/bin/bash
# Start the self-built audio-only Sunshine and open its Web UI.
# Because the tray is disabled for stability, use the browser at:
#   https://localhost:47990

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$SCRIPT_DIR/../dist/Sunshine-audioonly.app/Contents/MacOS/Sunshine"

# Stop any pre-built/old Sunshine instance
pkill -f "/Applications/Sunshine.app/Contents/MacOS/Sunshine" 2>/dev/null
pkill -f "$BIN" 2>/dev/null
sleep 1

nohup "$BIN" > /tmp/sunshine-patched.log 2>&1 &
echo "Sunshine 已启动（无托盘图标）"
echo "管理页：https://localhost:47990"
echo "日志：/tmp/sunshine-patched.log"

#!/usr/bin/env bash
# Copy a folder of your own videos onto the device (Movies/BootTV) over the debugging connection, and have
# Android index them so they show up in My Media straight away. No computer or internet needed afterwards.
#   tools/media.sh DIR [serial] [--replace]
# Lay DIR out the way Send to TV expects: Show/Season 1/S01E03 - Title.mp4, poster.jpg or Title.jpg beside
# the videos. Files already on the device with the same path are overwritten; --replace first removes
# everything under Movies/BootTV that came from earlier copies.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

[ $# -ge 1 ] || { sed -n '2,7p' "$0"; exit 1; }
MEDIA_DIR=""; REPLACE=0; SERIAL_ARG=""
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    --replace) REPLACE=1 ;;
    *) if [ -z "$MEDIA_DIR" ]; then MEDIA_DIR="$1"; else SERIAL_ARG="$1"; fi ;;
  esac
  shift
done
[ -d "$MEDIA_DIR" ] || die "$MEDIA_DIR is not a folder"

require_adb
select_device "$SERIAL_ARG"

need_kb="$(du -sk "$MEDIA_DIR" | awk '{print $1}')"
free_kb="$(dsh df /sdcard 2>/dev/null | awk 'NR==2 {print $4}')"
info "Copying $((need_kb / 1024)) MB from $MEDIA_DIR to $SERIAL (Movies/BootTV)${free_kb:+; $((free_kb / 1024)) MB free}"
if [ -n "$free_kb" ] && [ "$need_kb" -gt "$((free_kb - 300000))" ]; then
  die "Not enough room on the device. Copy a smaller set (the Xiaomi stick has about 5 GB free), or use the cloud library."
fi
if [ "$REPLACE" -eq 1 ]; then
  info "Removing earlier copies under Movies/BootTV"
  dsh rm -rf /sdcard/Movies/BootTV >/dev/null 2>&1 || true
fi
dsh mkdir -p /sdcard/Movies/BootTV >/dev/null 2>&1 || true
dadb push "$MEDIA_DIR/." /sdcard/Movies/BootTV/ | tail -1
# Android normally notices new files on the next reboot; ask it to index now.
if dsh content call --uri content://media/ --method scan_volume --arg external_primary >/dev/null 2>&1; then
  ok "Indexed. Open My Media on the TV: each folder is a section and can become a channel."
else
  dsh am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/BootTV >/dev/null 2>&1 || true
  warn "Copied; if the videos don't show in My Media within a minute, restart the device once."
fi

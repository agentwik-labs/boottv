#!/usr/bin/env bash
# Keeps the emulator usable: the Android Emulator's own window sometimes stops passing the Mac's keyboard and mouse
# through (its log then says "skipping QEventPoint ... no target window" as soon as you click it). The TV inside is
# fine; only the window is deaf. This watches the emulator log for that signature and restarts the emulator
# (tools/emulator.sh --restart), which is the only cure. It also restarts it if the emulator process disappears.
#
#   tools/emulator-watch.sh [--log FILE] [--once]      (run it in a second terminal and leave it)
#
# If your keys stop working: click the emulator screen once. The watcher sees the dead-window line and restarts
# the emulator within a few seconds; BootTV comes back on its own about a minute later.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$ROOT_DIR/build/emulator.log"; ONCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --log) [ $# -ge 2 ] || die "--log needs a file"; LOG="$2"; shift ;;
    --once) ONCE=1 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
  shift
done

SIGNATURE='skipping QEventPoint'
HANG='detected a hanging thread'

restart() {
  warn "Emulator window stopped taking input ($1). Restarting it..."
  "$ROOT_DIR/tools/emulator.sh" --restart --no-install >/dev/null 2>&1 || warn "emulator.sh --restart failed"
  LOG="$ROOT_DIR/build/emulator.log"
  # Start counting afresh from the new emulator's log (the old one may still be on disk for a moment).
  sleep 5
  seen=$(grep -c "$SIGNATURE" "$LOG" 2>/dev/null || true); seen=${seen:-0}
  hangs=$(grep -c "$HANG" "$LOG" 2>/dev/null || true); hangs=${hangs:-0}
  info "Emulator back. Keys should work again; if BootTV isn't showing, press Home."
}

info "Watching $LOG for a dead emulator window (Ctrl-C to stop)."
seen=0; hangs=0
while :; do
  if [ -f "$LOG" ]; then
    n=$(grep -c "$SIGNATURE" "$LOG" 2>/dev/null || true); n=${n:-0}
    h=$(grep -c "$HANG" "$LOG" 2>/dev/null || true); h=${h:-0}
    if [ "$n" -gt "$seen" ]; then
      restart "clicks are not reaching the TV"
      [ "$ONCE" -eq 1 ] && exit 0
      sleep 20; continue
    fi
    # A long freeze under build load ends the same way; treat several hangs as the same fault.
    if [ "$h" -ge $((hangs + 6)) ]; then
      restart "the emulator froze for a while"
      [ "$ONCE" -eq 1 ] && exit 0
      sleep 20; continue
    fi
    hangs=$h
  fi
  sleep 3
done

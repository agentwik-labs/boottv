#!/usr/bin/env bash
# Boot the BootTV_API34 Android TV emulator, wait for boot, then install & launch BootTV.
#   tools/emulator.sh [--headless] [--avd NAME] [--no-install] [--restart]
# --restart kills a running emulator first. Use it when the emulator window stops taking keyboard/mouse input
# (the emulator's own window layer gives up after a while; the TV inside is fine, only the window is deaf).
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

AVD="BootTV_API34"; HEADLESS=0; DO_INSTALL=1; RESTART=0
while [ $# -gt 0 ]; do
  case "$1" in
    --headless) HEADLESS=1 ;;
    --avd) [ $# -ge 2 ] || die "--avd needs a name"; AVD="$2"; shift ;;
    --no-install) DO_INSTALL=0 ;;
    --restart) RESTART=1 ;;
    -h|--help) sed -n '2,3p' "$0"; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
  shift
done

require_adb
command -v emulator >/dev/null 2>&1 || die "emulator not found on PATH (check env.sh / Android SDK)."
emulator -list-avds 2>/dev/null | grep -qx "$AVD" || die "AVD '$AVD' not found. Existing: $(emulator -list-avds 2>/dev/null | tr '\n' ' ')"

running_emulator() { adb devices | awk 'NR>1 && $1 ~ /^emulator-/ && $2=="device" {print $1; exit}'; }

SERIAL="$(running_emulator || true)"
if [ -n "$SERIAL" ] && [ "$RESTART" -eq 1 ]; then
  info "Stopping $SERIAL"
  adb -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  sleep 6; pkill -f qemu-system >/dev/null 2>&1 || true; sleep 2
  SERIAL=""
fi
if [ -n "$SERIAL" ]; then
  info "Emulator already running: $SERIAL"
else
  LOG="$ROOT_DIR/build/emulator.log"
  mkdir -p "$ROOT_DIR/build"
  # Let the Mac keyboard drive the TV (arrows = D-pad, Enter = OK, Esc = Back).
  CFG="$HOME/.android/avd/$AVD.avd/config.ini"
  if [ -f "$CFG" ] && ! grep -q '^hw.keyboard *= *yes' "$CFG"; then
    sed -i '' -e '/^hw.keyboard *=/d' "$CFG" && echo "hw.keyboard = yes" >> "$CFG"
  fi
  # Public DNS: the emulator's built-in resolver sometimes stops answering, which looks like "web apps don't load".
  ARGS="-avd $AVD -no-snapshot-save -no-boot-anim -dns-server 8.8.8.8,1.1.1.1"
  [ "$HEADLESS" -eq 1 ] && ARGS="$ARGS -no-window -no-audio"
  info "Starting emulator ($AVD$([ "$HEADLESS" -eq 1 ] && echo ', headless')); log: $LOG"
  # shellcheck disable=SC2086
  nohup emulator $ARGS >"$LOG" 2>&1 &
  EMU_PID=$!
  info "Waiting for the emulator to appear in adb (pid $EMU_PID)"
  for i in $(seq 1 120); do
    SERIAL="$(adb devices | awk 'NR>1 && $1 ~ /^emulator-/ {print $1; exit}')"
    [ -n "$SERIAL" ] && break
    kill -0 "$EMU_PID" 2>/dev/null || { tail -n 30 "$LOG" >&2; die "Emulator exited early (see $LOG)."; }
    sleep 1
  done
  [ -n "$SERIAL" ] || die "Emulator did not register with adb within 120s (see $LOG)."
fi

info "Waiting for $SERIAL to finish booting"
adb -s "$SERIAL" wait-for-device
booted=""
for i in $(seq 1 300); do
  booted="$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null </dev/null | tr -d '\r' || true)"
  [ "$booted" = "1" ] && break
  [ $((i % 15)) -eq 0 ] && echo "  ...still booting (${i}s)"
  sleep 1
done
[ "$booted" = "1" ] || die "Emulator did not finish booting within 300s."
ok "Emulator $SERIAL booted."
# Keep the screen on while testing.
adb -s "$SERIAL" shell svc power stayon true </dev/null >/dev/null 2>&1 || true

if [ "$DO_INSTALL" -eq 1 ]; then
  if [ ! -f "$APK_PATH" ]; then
    warn "$APK_PATH missing; building a debug APK first."
    "$TOOLS_DIR/build.sh" --debug
  fi
  "$TOOLS_DIR/install.sh" "$SERIAL"
fi
echo "Stop the emulator with: adb -s $SERIAL emu kill"

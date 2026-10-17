#!/usr/bin/env bash
# Connect to an Android TV / Google TV / Fire TV device over Wi-Fi ADB.
#   tools/connect.sh <ip>[:port]                 connect (default port 5555)
#   tools/connect.sh --pair <ip:port> <code>     pair first (Google TV 12+ "Wireless debugging"),
#                    [<ip>[:connect-port]]       then optionally connect
#   tools/connect.sh --help                      show how to enable debugging on the TV
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

print_help() {
  cat <<'EOF'
Usage:
  tools/connect.sh <ip>[:port]
  tools/connect.sh --pair <ip:pair-port> <6-digit-code> [<ip>:<connect-port>]

Find the TV's IP: Settings -> Network & Internet -> (your Wi-Fi) on Google TV,
or Settings -> My Fire TV -> About -> Network on Fire TV. Your Mac and TV must
be on the same network.

--- Google TV / Android TV (Onn 4K Pro, Google TV Streamer, Chromecast) ---
 1. Settings -> System -> About
 2. Scroll to "Android TV OS build" and click it 7 times
    ("You are now a developer!")
 3. Back to Settings -> System -> Developer options
 4. Turn on "USB debugging".
 5a. Older builds: also turn on "Network debugging" / "ADB over network"
     if shown, then run:   tools/connect.sh <tv-ip>
 5b. Google TV 12+ (Onn 4K Pro, Google TV Streamer): turn on
     "Wireless debugging", open it, choose "Pair device with pairing code".
     The TV shows an IP:PORT and a 6-digit code. Run:
        tools/connect.sh --pair <ip:pair-port> <code> <ip>:<connect-port>
     (The connect port is the one shown on the main Wireless debugging
      screen under "IP address & Port" — it differs from the pairing port.)
 6. Accept the "Allow debugging?" prompt on the TV (tick "Always allow").

--- Fire TV (Fire TV Stick 4K etc.) ---
 1. Settings -> My Fire TV -> About
 2. Click the device name (e.g. "Fire TV Stick 4K") 7 times
 3. Back -> Developer options -> turn on "ADB debugging"
    (also "Apps from Unknown Sources" if you sideload other APKs)
 4. Run:  tools/connect.sh <tv-ip>
 5. Accept the "Allow USB debugging?" prompt on the TV.
EOF
}

require_adb

PAIR_TARGET=""; PAIR_CODE=""; TARGET=""
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) print_help; exit 0 ;;
    --pair)
      [ $# -ge 3 ] || die "--pair needs <ip:port> <code>"
      PAIR_TARGET="$2"; PAIR_CODE="$3"; shift 3 ;;
    -*) die "Unknown option: $1 (see --help)" ;;
    *) [ -z "$TARGET" ] || die "Only one connect target allowed"; TARGET="$1"; shift ;;
  esac
done

if [ -z "$PAIR_TARGET" ] && [ -z "$TARGET" ]; then
  print_help; exit 1
fi

if [ -n "$PAIR_TARGET" ]; then
  case "$PAIR_TARGET" in *:*) ;; *) die "Pairing needs ip:port as shown on the TV's pairing dialog." ;; esac
  info "Pairing with $PAIR_TARGET"
  if ! out="$(adb pair "$PAIR_TARGET" "$PAIR_CODE" 2>&1)"; then
    printf '%s\n' "$out" >&2
    die "Pairing failed. Keep the pairing dialog open on the TV and double-check the port and code."
  fi
  printf '%s\n' "$out"
  printf '%s' "$out" | grep -qi "success" || die "Pairing did not report success."
  ok "Paired."
  if [ -z "$TARGET" ]; then
    echo "Now connect using the IP:PORT shown on the main 'Wireless debugging' screen:"
    echo "  tools/connect.sh <ip>:<connect-port>"
    exit 0
  fi
fi

case "$TARGET" in *:*) ;; *) TARGET="$TARGET:5555" ;; esac
info "Connecting to $TARGET"
out="$(adb connect "$TARGET" 2>&1 || true)"
printf '%s\n' "$out"
if printf '%s' "$out" | grep -qiE "failed|unable|refused|cannot|timed out"; then
  warn "Could not connect. Is debugging enabled and the TV on the same network? Run 'tools/connect.sh --help'."
  exit 1
fi

# Wait for authorization (first connection shows a prompt on the TV).
state=""
for i in $(seq 1 30); do
  state="$(adb -s "$TARGET" get-state 2>/dev/null || true)"
  [ "$state" = "device" ] && break
  if [ "$i" -eq 1 ]; then echo "Waiting for you to accept 'Allow debugging?' on the TV..."; fi
  sleep 1
done
if [ "$state" != "device" ]; then
  adb devices
  die "Device $TARGET is not authorized yet. Accept the prompt on the TV, then rerun."
fi

model="$(adb -s "$TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
maker="$(adb -s "$TARGET" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r' || true)"
ok "Connected to $maker $model ($TARGET)"
echo "Next: tools/install.sh $TARGET"

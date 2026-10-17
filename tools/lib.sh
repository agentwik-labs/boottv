# shellcheck shell=bash
# Shared helpers for BootTV tools. Sourced by the other scripts; not run directly.
# Compatible with macOS's stock bash 3.2 (no mapfile, no associative arrays).

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$TOOLS_DIR/.." && pwd)"
APK_PATH="$ROOT_DIR/dist/BootTV.apk"
PKG="dev.boottv"
HOME_ACTIVITY="dev.boottv/.MainActivity"
HOME_ACTIVITY_FULL="dev.boottv/dev.boottv.MainActivity"

if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_BLD=$'\033[1m'; C_RST=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_BLD=""; C_RST=""
fi

info() { printf '%s==>%s %s\n' "$C_BLD" "$C_RST" "$*"; }
ok()   { printf '%s[ok]%s %s\n' "$C_GRN" "$C_RST" "$*"; }
warn() { printf '%s[warn]%s %s\n' "$C_YEL" "$C_RST" "$*" >&2; }
die()  { printf '%s[error]%s %s\n' "$C_RED" "$C_RST" "$*" >&2; exit 1; }

load_env() {
  if [ -f "$ROOT_DIR/env.sh" ]; then
    # env.sh is not written for `set -u`; relax it while sourcing.
    set +u
    # shellcheck disable=SC1091
    . "$ROOT_DIR/env.sh"
    set -u
  fi
}

require_adb() {
  load_env
  command -v adb >/dev/null 2>&1 || die "adb not found. Run 'source env.sh' from the project root (or install Android platform-tools)."
}

# Picks the target device. Sets SERIAL. Arg: optional serial from the user.
select_device() {
  local requested="${1:-}"
  if [ -n "$requested" ]; then
    SERIAL="$requested"
  elif [ -n "${ANDROID_SERIAL:-}" ]; then
    SERIAL="$ANDROID_SERIAL"
  else
    local devices count
    devices="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
    count="$(printf '%s\n' "$devices" | grep -c . || true)"
    if [ "$count" -eq 0 ]; then
      die "No device connected. Use tools/connect.sh <tv-ip> or tools/emulator.sh first. ('adb devices' shows unauthorized? Accept the prompt on the TV.)"
    elif [ "$count" -gt 1 ]; then
      printf '%s\n' "$devices" >&2
      die "Several devices connected; pass the serial as an argument (one of the above)."
    fi
    SERIAL="$devices"
  fi
  local state
  state="$(adb -s "$SERIAL" get-state 2>/dev/null || true)"
  [ "$state" = "device" ] || die "Device '$SERIAL' is not ready (state: '${state:-not found}'). Check 'adb devices'."
  export ANDROID_SERIAL="$SERIAL"
}

# adb against the selected device.
dadb() { adb -s "$SERIAL" "$@"; }

# adb shell with CR stripped (older adb/devices emit \r\n).
# stdin is /dev/null so `adb shell` never swallows the input of a surrounding `while read` loop.
dsh() { adb -s "$SERIAL" shell "$@" </dev/null | tr -d '\r'; }

# Print lines in reverse order (macOS: tail -r, GNU: tac).
rev_lines() { if command -v tac >/dev/null 2>&1; then tac; else tail -r; fi; }

is_installed() { dsh pm list packages "$1" 2>/dev/null | grep -qx "package:$1"; }

# Returns 0 if HOME intent currently resolves to BootTV.
boottv_is_home() {
  local out
  out="$(dsh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null || true)"
  printf '%s\n' "$out" | tail -n 1 | grep -Eq "^[[:space:]]*${PKG}/"
}

# Returns 0 if BootTV holds the HOME role (Android 10+), i.e. it was chosen as the home app.
# On Android TV / Google TV the stock launcher declares a higher-priority HOME filter and
# still wins the Home button until it is disabled (tools/debloat.sh does that safely).
boottv_holds_home_role() {
  dsh cmd role get-role-holders android.app.role.HOME 2>/dev/null | tr ';' '\n' | grep -qx "$PKG"
}

# Returns 0 if BootTV is offered as a HOME activity at all.
boottv_is_home_candidate() {
  dsh cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
    | grep -Eq "^[[:space:]]*${PKG}/"
}

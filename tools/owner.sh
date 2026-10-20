#!/usr/bin/env bash
# Make BootTV the stick's device owner, so it runs the whole TV (see Settings › Device control).
#   tools/owner.sh [serial]
# Needs a freshly reset stick with no Google account signed in yet (Android's rule for device owners).
# To undo: BootTV › Settings › Device control › Release device control (or a factory reset).
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

case "${1:-}" in -h|--help) sed -n '2,5p' "$0"; exit 0 ;; esac
require_adb
select_device "${1:-}"

dsh pm path "$PKG" >/dev/null 2>&1 || die "BootTV isn't installed on $SERIAL. Run tools/install.sh first."

if dsh dumpsys device_policy 2>/dev/null | grep -A3 -i "Device Owner" | grep -q "$PKG"; then
  # Repair: Android can drop the owner's admin component (it stays "owner" but every policy call fails).
  dsh dpm set-active-admin "$PKG/.owner.BootAdminReceiver" >/dev/null 2>&1 || true
  ok "BootTV is the device owner of $SERIAL (checked and repaired if needed)."
  exit 0
fi

accounts="$(dsh dumpsys account 2>/dev/null | grep -c 'Account {' || true)"
if [ "${accounts:-0}" -gt 0 ]; then
  warn "This stick has $accounts account(s) signed in. Android only allows a device owner on a stick with none."
  cat <<'TXT'
   Either remove them (Settings > Accounts & sign-in), or factory reset the stick and, during setup,
   skip the Google sign-in. Then run tools/install.sh and this script again. You can sign in to
   Google Play afterwards; BootTV stays in control.
TXT
  exit 1
fi

info "Making BootTV the device owner of $SERIAL"
out="$(dsh dpm set-device-owner "$PKG/.owner.BootAdminReceiver" 2>&1 || true)"
printf '%s\n' "$out"
if printf '%s' "$out" | grep -qi "success"; then
  ok "Done. Home now always opens BootTV, and Google's home screen and ad services are hidden."
  echo "   Lockdown and the system update schedule are in BootTV > Settings > Device control."
else
  die "Android refused. Most often: an account is signed in, or setup created a second user. Factory reset and retry."
fi

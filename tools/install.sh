#!/usr/bin/env bash
# Install dist/BootTV.apk on the device, make it the home screen, grant mic, launch it.
#   tools/install.sh [serial]
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

case "${1:-}" in -h|--help) sed -n '2,3p' "$0"; exit 0 ;; esac

require_adb
[ -f "$APK_PATH" ] || die "$APK_PATH not found. Run tools/build.sh first."
select_device "${1:-}"

info "Installing BootTV on $SERIAL"
if ! out="$(dadb install -r "$APK_PATH" 2>&1)"; then
  printf '%s\n' "$out" >&2
  if printf '%s' "$out" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
    die "A BootTV signed with a different key is installed. Remove it with: adb -s $SERIAL uninstall $PKG"
  fi
  die "adb install failed."
fi
printf '%s' "$out" | grep -q "Success" || { printf '%s\n' "$out" >&2; die "adb install did not report Success."; }
ok "Installed."

info "Granting microphone permission (voice search)"
if dsh pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1; then
  ok "RECORD_AUDIO granted."
else
  warn "Could not grant RECORD_AUDIO (the app will ask the first time you use voice search)."
fi

info "Setting BootTV as the home screen"
set_out="$(dsh cmd package set-home-activity "$HOME_ACTIVITY" 2>&1 || true)"
[ -n "$set_out" ] && printf '%s\n' "$set_out"
# Android 10+: the HOME role (no-op if set-home-activity already did it).
dsh cmd role add-role-holder android.app.role.HOME "$PKG" 0 >/dev/null 2>&1 || true
if boottv_is_home; then
  ok "BootTV is now the default home app. Press Home on the remote to check."
elif boottv_holds_home_role; then
  ok "BootTV is selected as the home app."
  warn "This device's stock launcher has a higher-priority Home filter, so the Home button still"
  warn "opens it (normal on Android TV / Google TV). To make Home open BootTV:"
  warn "    tools/debloat.sh $SERIAL --dry-run    (preview)"
  warn "    tools/debloat.sh $SERIAL              (disables the stock launcher safely; reversible)"
else
  # Some builds want the full component name or a --user flag.
  dsh cmd package set-home-activity --user 0 "$HOME_ACTIVITY_FULL" >/dev/null 2>&1 || true
  if boottv_is_home || boottv_holds_home_role; then
    ok "BootTV is now the default home app."
  else
    warn "Could not set BootTV as home automatically. Fallbacks:"
    cat <<'EOF'
   * Google TV / Android TV: press Home on the remote. If a "Select a home app"
     chooser appears, pick BootTV -> "Always". Some builds show it under
     Settings -> Apps -> Default apps -> Home app.
   * Google TV with no chooser: the stock launcher (launcherx) always wins.
     After confirming BootTV runs, `tools/debloat.sh` can disable the stock
     launcher so Home goes to BootTV (it re-checks before doing so).
   * Fire TV: Amazon blocks replacing the launcher. Install "Button Mapper"
     from the Amazon Appstore and map a remote button (or double-press Home)
     to "Launch app -> BootTV".
EOF
  fi
fi

info "Launching BootTV"
if dsh am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$HOME_ACTIVITY" >/dev/null 2>&1 \
  || dsh monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; then
  ok "BootTV launched on $SERIAL."
else
  warn "Could not launch automatically; open BootTV from the apps list."
fi
echo "Optional: tools/debloat.sh $SERIAL --dry-run   (see what bloat/ads would be disabled)"

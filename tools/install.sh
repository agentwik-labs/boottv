#!/usr/bin/env bash
# Install dist/BootTV.apk on the device, make it the home screen, grant mic, add LocalSend, launch it.
#   tools/install.sh [serial] [--no-localsend]
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

WITH_LOCALSEND=1
ARGS=()
for arg in "$@"; do
  case "$arg" in
    -h|--help) sed -n '2,3p' "$0"; exit 0 ;;
    --no-localsend) WITH_LOCALSEND=0 ;;
    *) ARGS+=("$arg") ;;
  esac
done
set -- "${ARGS[@]+"${ARGS[@]}"}"

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

# LocalSend: drop videos onto the TV from a Mac or phone over Wi-Fi (they show up in BootTV's My Videos channel).
# Pinned release, checked against LocalSend's signing certificate before installing.
LOCALSEND_VERSION="1.18.2"
LOCALSEND_CERT_SHA256="3220c353a73cfbd0c2f3052471c445324cf452bcba26de1c473a52fe5c44e1d6"
if [ "$WITH_LOCALSEND" = 1 ]; then
  if dsh pm list packages 2>/dev/null | grep -q "org.localsend.localsend_app"; then
    ok "LocalSend is already installed."
  else
    abi="$(dsh getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
    case "$abi" in
      arm64*) variant="arm64v8" ;;
      armeabi*) variant="arm32v7" ;;
      x86_64) variant="x64" ;;
      *) variant="" ;;
    esac
    if [ -z "$variant" ]; then
      warn "Skipping LocalSend: no build for this device ($abi)."
    else
      info "Installing LocalSend $LOCALSEND_VERSION ($variant) for sending videos from your Mac or phone"
      ls_apk="$ROOT_DIR/dist/LocalSend-$LOCALSEND_VERSION-$variant.apk"
      [ -f "$ls_apk" ] || curl -fsSL -o "$ls_apk" \
        "https://github.com/localsend/localsend/releases/download/v$LOCALSEND_VERSION/LocalSend-$LOCALSEND_VERSION-android-$variant.apk" \
        || { warn "Couldn't download LocalSend; skipping."; ls_apk=""; }
      if [ -n "$ls_apk" ]; then
        apksigner="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | tail -n 1)apksigner"
        digest="$("$apksigner" verify --print-certs "$ls_apk" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1)"
        if [ "$digest" != "$LOCALSEND_CERT_SHA256" ]; then
          rm -f "$ls_apk"
          warn "LocalSend download isn't signed by LocalSend; not installing it."
        elif dadb install -r "$ls_apk" >/dev/null 2>&1; then
          ok "LocalSend installed. Open it on the TV, then send from LocalSend on your Mac or phone."
        else
          warn "Couldn't install LocalSend."
        fi
      fi
    fi
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

#!/usr/bin/env bash
# Disable (never uninstall) ads, sponsored rows, telemetry and bloat on Google TV / Android TV / Fire TV.
# Everything is reversible and logged to tools/debloat-<serial>-<date>.log.
#
#   tools/debloat.sh [serial] [options]
#
# Options:
#   --dry-run            show what would be disabled; change nothing
#   --yes                skip the typed confirmation
#   --include-apps       also disable optional preinstalled apps (Play Games, YouTube Music, ambient mode...)
#   --include-ota        Fire TV only: block Amazon system updates (NOT recommended: no security fixes)
#   --allow-launcher-swap  Google TV: disable the stock launcher even if BootTV is not yet the default
#                        home, as long as BootTV is installed and registered as a home app (it becomes
#                        the only one). Auto-rolls back if Home doesn't resolve to BootTV afterwards.
#   --restore            re-enable everything recorded in this device's debloat logs
#   --restore-all-known  re-enable every package on BootTV's lists that is currently disabled
#                        (use if the logs were lost)
#   --log FILE           with --restore: restore only from FILE
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

# ---------------------------------------------------------------------------
# Curated package lists: "package|tier|description"
#   tier ads      = ads / sponsored content / telemetry, disabled by default
#   tier launcher = stock launcher; only after BootTV is verified as home
#   tier apps     = optional preinstalled apps (--include-apps)
#   tier ota      = system updater (--include-ota, Fire TV only)
# Only packages that are actually installed AND enabled on the device are touched,
# so names absent on a given model are simply skipped.
#
# Deliberately NEVER touched: com.google.android.gms (Play Services), com.android.vending
# (Play Store), com.google.android.katniss (Assistant/voice search - keep for the mic
# button; optional to disable by hand), com.android.tv.settings, com.android.systemui,
# com.google.android.tvsetup / setup wizards other than setupwraith, keyboards (leanback IME
# / gboard), com.google.android.tv.remote.service, Bluetooth, com.google.android.apps.mediashell
# (Chromecast built-in), com.amazon.tv.launcher, com.amazon.tv.settings*, com.amazon.vizzini
# (Fire TV voice), com.amazon.device.messaging, and any user-installed app.
# ---------------------------------------------------------------------------
GOOGLE_LIST='com.google.android.apps.tv.launcherx|launcher|Google TV stock home screen (sponsored rows, "For you" ads)
com.google.android.tungsten.setupwraith|launcher|Google TV setup wrapper (re-asserts stock home if launcherx is off)
com.google.android.tvlauncher|launcher|Android TV stock home screen (older builds, ad rows)
com.google.android.leanbacklauncher|launcher|Legacy Android TV Leanback launcher
com.google.android.tvrecommendations|ads|TV recommendations / sponsored content service
com.google.android.leanbacklauncher.recommendations|ads|Leanback recommendation rows provider
com.google.android.feedback|ads|Google feedback / usage reporting agent
com.google.android.tv.bugreportsender|ads|Automatic bug report uploader
com.google.android.apps.tv.dreamx|apps|Google TV ambient mode screensaver (BootTV has its own)
com.google.android.backdrop|apps|Android TV Backdrop ambient mode (BootTV has its own)
com.google.android.play.games|apps|Google Play Games
com.google.android.youtube.tvmusic|apps|YouTube Music
com.google.android.youtube.tvkids|apps|YouTube Kids
com.google.android.videos|apps|Google TV / Play Movies app'

FIRE_LIST='com.amazon.device.crashmanager|ads|Amazon crash/usage telemetry uploader
com.amazon.client.metrics|ads|Amazon device metrics collection
com.amazon.dp.logger|ads|Amazon device diagnostics logger
com.amazon.bueller.photos|apps|Amazon Photos (screensaver/ambient)
com.amazon.device.software.ota|ota|Fire OS system updater
com.amazon.device.software.ota.override|ota|Fire OS updater override
com.amazon.tv.forcedotaupdater.v2|ota|Fire OS forced update prompt'

# ---------------------------------------------------------------------------
DRY_RUN=0; ASSUME_YES=0; INC_APPS=0; INC_OTA=0; ALLOW_SWAP=0
MODE="disable"; LOG_ONLY=""; REQ_SERIAL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    --include-apps) INC_APPS=1 ;;
    --include-ota) INC_OTA=1 ;;
    --allow-launcher-swap) ALLOW_SWAP=1 ;;
    --restore) MODE="restore" ;;
    --restore-all-known) MODE="restore-all" ;;
    --log) [ $# -ge 2 ] || die "--log needs a file"; LOG_ONLY="$2"; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    -*) die "Unknown option: $1 (see --help)" ;;
    *) [ -z "$REQ_SERIAL" ] || die "Only one serial allowed"; REQ_SERIAL="$1" ;;
  esac
  shift
done

require_adb
select_device "$REQ_SERIAL"
SAFE_SERIAL="$(printf '%s' "$SERIAL" | tr -c 'A-Za-z0-9._-' '_')"
STAMP="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$TOOLS_DIR/debloat-$SAFE_SERIAL-$STAMP.log"

MAKER="$(dsh getprop ro.product.manufacturer || true)"
MODEL="$(dsh getprop ro.product.model || true)"
SDK="$(dsh getprop ro.build.version.sdk || true)"

ALL_PKGS="$(dsh pm list packages 2>/dev/null | sed 's/^package://')" || die "pm list packages failed"
ENABLED_PKGS="$(dsh pm list packages -e 2>/dev/null | sed 's/^package://')" || die "pm list packages -e failed"
DISABLED_PKGS="$(dsh pm list packages -d 2>/dev/null | sed 's/^package://')" || true
has_pkg()     { printf '%s\n' "$ALL_PKGS" | grep -qx "$1"; }
is_enabled()  { printf '%s\n' "$ENABLED_PKGS" | grep -qx "$1"; }
is_disabled() { printf '%s\n' "$DISABLED_PKGS" | grep -qx "$1"; }

PLATFORM="androidtv"
if printf '%s' "$MAKER" | grep -qi amazon || has_pkg com.amazon.tv.launcher; then
  PLATFORM="firetv"
elif has_pkg com.google.android.apps.tv.launcherx; then
  PLATFORM="googletv"
fi
info "Device: ${MAKER:-?} ${MODEL:-?} (SDK ${SDK:-?}) serial $SERIAL -> platform: $PLATFORM"

enable_pkg() {
  local p="$1" out
  out="$(dsh pm enable --user 0 "$p" 2>&1 || true)"
  if printf '%s' "$out" | grep -qi "new state: enabled"; then return 0; fi
  out="$(dsh pm enable "$p" 2>&1 || true)"
  if printf '%s' "$out" | grep -qi "new state: enabled"; then return 0; fi
  out="$(dsh cmd package install-existing "$p" 2>&1 || true)"
  if printf '%s' "$out" | grep -qi "installed"; then return 0; fi
  printf '   %s\n' "$out" >&2
  return 1
}

disable_pkg() {
  local p="$1" out
  out="$(dsh pm disable-user --user 0 "$p" 2>&1 || true)"
  if printf '%s' "$out" | grep -qi "new state: disabled"; then return 0; fi
  printf '   %s\n' "$out" >&2
  return 1
}

# --------------------------- restore modes ---------------------------------
if [ "$MODE" = "restore" ] || [ "$MODE" = "restore-all" ]; then
  if [ "$MODE" = "restore" ]; then
    if [ -n "$LOG_ONLY" ]; then
      [ -f "$LOG_ONLY" ] || die "Log not found: $LOG_ONLY"
      LOGS="$LOG_ONLY"
    else
      LOGS="$(ls -1 "$TOOLS_DIR"/debloat-"$SAFE_SERIAL"-*.log 2>/dev/null | grep -v -- '-restore\.log$' || true)"
      [ -n "$LOGS" ] || die "No debloat logs for $SERIAL in $TOOLS_DIR. Try --restore-all-known."
    fi
    # Newest log first; within a log, undo in reverse order.
    TARGETS="$(printf '%s\n' "$LOGS" | sort -r | while IFS= read -r f; do
      { grep '^DISABLED	' "$f" || true; } | cut -f2 | rev_lines
    done | awk 'NF && !seen[$0]++')"
  else
    TARGETS="$(printf '%s\n%s\n' "$GOOGLE_LIST" "$FIRE_LIST" | cut -d'|' -f1 | while IFS= read -r p; do
      is_disabled "$p" && printf '%s\n' "$p"; done || true)"
  fi
  [ -n "$TARGETS" ] || { ok "Nothing to restore."; exit 0; }
  echo "Will re-enable:"; printf '  %s\n' $TARGETS
  if [ "$DRY_RUN" -eq 1 ]; then ok "Dry run: nothing changed."; exit 0; fi
  RLOG="$TOOLS_DIR/debloat-$SAFE_SERIAL-$STAMP-restore.log"
  printf '# BootTV debloat restore %s on %s %s (%s)\n' "$STAMP" "$MAKER" "$MODEL" "$SERIAL" > "$RLOG"
  fail=0
  for p in $TARGETS; do
    if enable_pkg "$p"; then ok "enabled $p"; printf 'RESTORED\t%s\n' "$p" >> "$RLOG"
    else warn "could not re-enable $p"; printf 'FAILED\t%s\n' "$p" >> "$RLOG"; fail=1; fi
  done
  ok "Restore log: $RLOG"
  [ "$fail" -eq 0 ] || die "Some packages could not be re-enabled (see above). A factory reset always restores everything."
  echo "Tip: if the stock home screen came back, you may need to pick a home app again (press Home)."
  exit 0
fi

# --------------------------- build plan ------------------------------------
case "$PLATFORM" in
  firetv) LIST="$FIRE_LIST" ;;
  *)      LIST="$GOOGLE_LIST" ;;
esac

PLAN=""          # lines: pkg|tier|desc
SKIPPED_LAUNCHER=0
while IFS='|' read -r p tier desc; do
  [ -n "$p" ] || continue
  has_pkg "$p" && is_enabled "$p" || continue
  case "$tier" in
    apps) [ "$INC_APPS" -eq 1 ] || continue ;;
    ota)  [ "$INC_OTA" -eq 1 ] || continue ;;
    launcher) SKIPPED_LAUNCHER=1 ;;  # decided below
  esac
  PLAN="$PLAN$p|$tier|$desc
"
done <<EOF
$LIST
EOF

# Launcher safety gate: never leave the device without a home screen.
if [ "$SKIPPED_LAUNCHER" -eq 1 ]; then
  launcher_ok=0
  if ! is_installed "$PKG" || ! is_enabled "$PKG"; then
    warn "BootTV ($PKG) is not installed/enabled: refusing to disable the stock launcher (you'd have no home screen)."
    warn "Run tools/install.sh first."
  else
    dsh cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 || true
    dsh cmd role add-role-holder android.app.role.HOME "$PKG" 0 >/dev/null 2>&1 || true
    if boottv_is_home || { boottv_holds_home_role && boottv_is_home_candidate; }; then
      launcher_ok=1
      ok "Safety check passed: BootTV is installed and selected as the home app."
    elif boottv_is_home_candidate; then
      if [ "$ALLOW_SWAP" -eq 1 ]; then
        launcher_ok=1
        warn "BootTV is a registered home app but not the default yet; --allow-launcher-swap given, so the stock"
        warn "launcher will be disabled and the result verified (automatic rollback if Home isn't BootTV)."
      else
        warn "BootTV is installed but the stock launcher is still the default Home. Refusing to disable it."
        warn "Press Home and choose BootTV -> Always, or rerun with --allow-launcher-swap"
        warn "(the script verifies afterwards and rolls back automatically if anything is wrong)."
      fi
    else
      warn "BootTV does not answer the HOME intent on this device; refusing to disable the stock launcher."
    fi
  fi
  if [ "$launcher_ok" -eq 0 ]; then
    PLAN="$(printf '%s' "$PLAN" | grep -v '|launcher|' || true)
"
  fi
fi
PLAN="$(printf '%s' "$PLAN" | awk 'NF')"

if [ "$PLATFORM" = "firetv" ]; then
  cat <<EOF
${C_YEL}${C_BLD}Fire TV notice:${C_RST}
 * Amazon's launcher (com.amazon.tv.launcher) is NOT disabled: on Fire OS that can leave you with
   no home screen and no Settings access. Fire OS also forces Home back to Amazon's launcher.
 * To open BootTV with one press: install "Button Mapper" (Amazon Appstore), and map a button
   (e.g. long-press or double-press Home, or an app shortcut button) to "Launch app -> BootTV".
 * Fire OS 7.6+/8 blocks disabling many system packages; those will just fail and be skipped.
EOF
fi

if [ -z "$PLAN" ]; then
  ok "Nothing to disable (already clean, or nothing on the list is present on this device)."
  exit 0
fi

echo
echo "${C_BLD}The following packages will be DISABLED (reversible with --restore):${C_RST}"
printf '%s\n' "$PLAN" | while IFS='|' read -r p tier desc; do
  printf '  %-52s [%s] %s\n' "$p" "$tier" "$desc"
done
echo
echo "Kept on purpose: Play Services, Play Store, Settings, System UI, keyboards, remote/Bluetooth,"
echo "Chromecast built-in, and voice search (com.google.android.katniss - disable by hand if you never use the mic)."

if [ "$DRY_RUN" -eq 1 ]; then ok "Dry run: nothing changed."; exit 0; fi

if [ "$ASSUME_YES" -ne 1 ]; then
  printf '\nType %sDISABLE%s to continue: ' "$C_BLD" "$C_RST"
  answer=""
  if [ -t 0 ]; then
    IFS= read -r answer || true
  elif ! { IFS= read -r answer </dev/tty; } 2>/dev/null; then
    IFS= read -r answer || true
  fi
  [ "$answer" = "DISABLE" ] || die "Aborted; nothing changed."
fi

{
  printf '# BootTV debloat %s on %s %s (SDK %s) serial %s platform %s\n' "$STAMP" "$MAKER" "$MODEL" "$SDK" "$SERIAL" "$PLATFORM"
  printf '# Undo: tools/debloat.sh %s --restore --log %s\n' "$SERIAL" "$LOG_FILE"
} > "$LOG_FILE"

DISABLED_LAUNCHERS=""
failures=0
# Launchers last, so a mid-run failure never leaves you without ads-free *and* home.
ORDERED="$(printf '%s\n' "$PLAN" | grep -v '|launcher|' || true)
$(printf '%s\n' "$PLAN" | grep '|launcher|' || true)"
while IFS='|' read -r p tier desc; do
  [ -n "$p" ] || continue
  if disable_pkg "$p"; then
    printf 'DISABLED\t%s\t%s\n' "$p" "$tier" >> "$LOG_FILE"
    ok "disabled $p"
    [ "$tier" = "launcher" ] && DISABLED_LAUNCHERS="$DISABLED_LAUNCHERS $p"
  else
    printf 'FAILED\t%s\t%s\n' "$p" "$tier" >> "$LOG_FILE"
    warn "could not disable $p (skipped)"
    failures=$((failures + 1))
  fi
done <<EOF
$ORDERED
EOF

# Post-check: Home must resolve to BootTV once launchers are gone.
if [ -n "$DISABLED_LAUNCHERS" ]; then
  dsh cmd package set-home-activity "$HOME_ACTIVITY" >/dev/null 2>&1 || true
  dsh cmd role add-role-holder android.app.role.HOME "$PKG" 0 >/dev/null 2>&1 || true
  if boottv_is_home; then
    ok "Home now resolves to BootTV."
  else
    warn "Home does not resolve to BootTV after disabling the stock launcher - rolling back launcher changes."
    for p in $DISABLED_LAUNCHERS; do
      if enable_pkg "$p"; then printf 'ROLLBACK\t%s\n' "$p" >> "$LOG_FILE"; ok "re-enabled $p"
      else warn "could not re-enable $p - run: adb -s $SERIAL shell pm enable $p"; fi
    done
  fi
fi

echo
ok "Done. Log: $LOG_FILE"
[ "$failures" -eq 0 ] || warn "$failures package(s) could not be disabled (often blocked by the OS; harmless)."
echo "Undo anytime: tools/debloat.sh $SERIAL --restore"
dsh input keyevent KEYCODE_HOME >/dev/null 2>&1 || true

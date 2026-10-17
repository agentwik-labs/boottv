#!/usr/bin/env bash
# Build BootTV and copy the APK to dist/BootTV.apk.
#   tools/build.sh           release build (minified, signed with the debug key)
#   tools/build.sh --debug   debug build
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

VARIANT="release"
for arg in "$@"; do
  case "$arg" in
    --debug) VARIANT="debug" ;;
    -h|--help) sed -n '2,4p' "$0"; exit 0 ;;
    *) die "Unknown option: $arg" ;;
  esac
done

load_env
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME is not set to a JDK. Check env.sh."

cd "$ROOT_DIR"
if [ "$VARIANT" = "debug" ]; then TASK="assembleDebug"; else TASK="assembleRelease"; fi
info "Running ./gradlew $TASK"
./gradlew "$TASK" || die "Gradle build failed (see output above)."

OUT_DIR="$ROOT_DIR/app/build/outputs/apk/$VARIANT"
APK="$(ls -t "$OUT_DIR"/*.apk 2>/dev/null | head -n 1 || true)"
[ -n "$APK" ] && [ -f "$APK" ] || die "No APK found in $OUT_DIR"

mkdir -p "$ROOT_DIR/dist"
cp "$APK" "$APK_PATH"
ok "Built $VARIANT APK -> $APK_PATH ($(du -h "$APK_PATH" | cut -f1 | tr -d ' '))"
echo "Next: tools/connect.sh <tv-ip>   then   tools/install.sh"

#!/usr/bin/env bash
# Baseline Profile and benchmarks, on the running emulator or a connected stick.
#   tools/profile.sh              regenerate app/src/release/generated/baselineProfiles
#   tools/profile.sh --benchmark  startup and Home scrolling, without vs. with the profile
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

load_env
cd "$ROOT_DIR"
case "${1:-}" in
  "")
    info "Generating the Baseline Profile (drives BootTV with the remote for a few minutes)"
    ./gradlew :app:generateBaselineProfile || die "Profile generation failed (see output above)."
    ok "Profile written to app/src/release/generated/baselineProfiles. Rebuild with tools/build.sh."
    ;;
  --benchmark)
    info "Running startup and scrolling benchmarks"
    ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest || die "Benchmarks failed (see output above)."
    ok "Results: baselineprofile/build/outputs/connected_android_test_additional_output/"
    ;;
  -h|--help) sed -n '2,4p' "$0" ;;
  *) die "Unknown option: $1" ;;
esac

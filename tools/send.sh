#!/usr/bin/env bash
# Send folders of your own videos to the TV over Wi-Fi, the same way the Send to TV page does. Needs only curl:
# no adb, no Android SDK. On the TV open My Media › Send from your phone, and pass the address and 4-digit code
# it shows (keep that screen open until this finishes).
#   tools/send.sh ADDRESS CODE DIR... [--channel]
# Each DIR becomes one folder in My Media, named after DIR (Show/Season 1/S01E03 - Title.mp4, poster.jpg).
# --channel puts each folder on the air (or adds the new videos to the channel it already has).
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

usage() { sed -n '2,7p' "$0"; exit "${1:-1}"; }
ADDRESS=""; CODE=""; CHANNEL=0; DIRS=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --channel) CHANNEL=1 ;;
    *) if [ -z "$ADDRESS" ]; then ADDRESS="$1"; elif [ -z "$CODE" ]; then CODE="$1"; else DIRS+=("$1"); fi ;;
  esac
  shift
done
[ -n "$ADDRESS" ] && [ -n "$CODE" ] && [ "${#DIRS[@]}" -gt 0 ] || usage
command -v curl >/dev/null 2>&1 || die "curl not found."
case "$ADDRESS" in http://*|https://*) ;; *) ADDRESS="http://$ADDRESS" ;; esac
ADDRESS="${ADDRESS%/}"

enc() { python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$1"; }

# Prints the server's error message (JSON {"error": ...}) and fails, or succeeds quietly.
call() {
  local out status
  out="$(curl -sS -w '\n%{http_code}' -H "X-Code: $CODE" "$@")" || die "Couldn't reach $ADDRESS. Is Send from your phone open on the TV?"
  status="${out##*$'\n'}"; out="${out%$'\n'*}"
  [ "$status" = "200" ] || die "TV said ($status): $out"
  printf '%s' "$out"
}

call "$ADDRESS/folders" >/dev/null
for dir in "${DIRS[@]}"; do
  [ -d "$dir" ] || die "$dir is not a folder"
  dir="${dir%/}"
  folder="$(basename "$dir")"
  info "Sending $folder"
  sent=0
  while IFS= read -r -d '' file; do
    rel="${file#"$dir"/}"
    name="$(basename "$rel")"
    sub="$(dirname "$rel")"; [ "$sub" = "." ] && sub=""
    ext="$(printf '%s' "${name##*.}" | tr '[:upper:]' '[:lower:]')"
    case "$ext" in
      mp4|m4v|mkv|webm|mov|avi|ts|m2ts|3gp|mpg|mpeg|wmv|flv|jpg|jpeg|png|webp) ;;
      *) continue ;;
    esac
    printf '  %s\n' "$rel"
    call -X POST -T "$file" -H "Content-Type: application/octet-stream" \
      "$ADDRESS/upload?folder=$(enc "$folder")&name=$(enc "$name")&path=$(enc "$sub")" >/dev/null
    sent=$((sent + 1))
  done < <(find "$dir" -type f ! -name '.*' -print0 | sort -z)
  msg="$(call -X POST "$ADDRESS/done?folder=$(enc "$folder")&channel=$CHANNEL")"
  ok "$folder: $sent files sent. $msg"
done

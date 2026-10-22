# Notes for coding agents (Codex, Claude Code)

BootTV is the home screen on our TV: a Xiaomi TV Stick 4K (Android 14, 32-bit, ~2.9 GB free) on a 2013 Sanyo
1080p TV. README.md explains the app; this file covers the two jobs you'll be asked to do from another computer:
**update the app** and **add our videos / make channels**.

Ground rules
- Legal sources only: our own videos, Jellyfin, archive.org and the free feeds already in the app. Don't add
  piracy sites, torrent streaming or anything that gets around a paid service.
- Never `adb uninstall dev.boottv` or `am force-stop dev.boottv` on the stick. Uninstalling wipes channels,
  watch progress and the Home setting; force-stop turns off the remote's hold-Back menu.
- The TV's IP changes. Ask for it (Settings › Network on the TV) instead of guessing.

## Update the app

The app must be signed with the same key as the copy on the TV, and that key is on the original Mac only
(`~/.android/debug.keystore`). So, from any other computer:

```bash
# needs adb (Android platform-tools) and the GitHub CLI; no JDK or Android SDK
gh release download --repo agentwik-labs/boottv --pattern BootTV.apk --dir dist --clobber
tools/connect.sh --help                    # how to turn on Wireless debugging; then connect
tools/connect.sh --pair <ip:pair-port> <code> <ip:connect-port>   # first time from this computer
tools/install.sh <ip:port> --no-localsend
adb -s <ip:port> shell settings put secure enabled_accessibility_services dev.boottv/dev.boottv.remote.RemoteButtonsService
adb -s <ip:port> shell settings put secure accessibility_enabled 1
```

To build from source on this computer instead (`tools/build.sh`), first copy `~/.android/debug.keystore` from the
original Mac to the same place here, otherwise the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Don't
"fix" that by uninstalling. After a code change: commit, push, and attach the new APK to a release
(`gh release create vYYYY.MM.DD dist/BootTV.apk`) so the next update can come from any computer.

## Add videos and channels

Easiest, over Wi-Fi with nothing but curl: on the TV open **My Media › Send from your phone**. It shows an address
(`http://<ip>:8090`) and a 4-digit code; ask the user for both, and they keep that screen open while you send.

```bash
tools/send.sh http://192.168.1.50:8090 1234 ~/Videos/BootTV/Bluey ~/Videos/BootTV/Movies --channel
```

Each folder becomes a section in My Media. `--channel` puts each folder on the air in Live TV and on Home, or,
if it already has a channel, adds the new videos to it. Channels also pick up new videos in their folder on their
own whenever Live TV loads.

Alternative over adb (no code needed, but no channels made): `tools/media.sh <dir>` copies into
`Movies/BootTV/` on the stick. Make the channel on the TV afterwards with the **+ CHANNEL** chip on that folder.

Check free space first; the stick holds only a few GB:
`adb -s <ip:port> shell df -h /sdcard`.

### How to lay out and encode the videos

```
Bluey/                                  ← folder = show name = channel name
  poster.jpg                            ← artwork for the show (portrait, ~600x900)
  Season 1/
    S01E01 - The Magic Xylophone.mp4    ← SxxEyy picks the order; text after " - " is the title
    S01E02 - Hospital.mp4
    S01E02 - Hospital.jpg               ← optional picture for one episode, same name as the video
Movies/
  Spirited Away (2001).mp4              ← a plain folder (no episode numbers) is a shuffled movie channel
  Spirited Away (2001).jpg
```

- Episode names: `S01E03`, `1x03` or `Episode 3`, or a bare number inside a `Season N` folder. A folder with
  episodes plays in order on its channel; any other folder is shuffled once.
- Videos shorter than one minute are left off channels.
- File and folder names: no `/ \ : * ? " < > |`; keep them under 80 characters.
- Format: **MP4, H.264 video + AAC audio** plays smoothly on the stick. MKV/MOV/WebM/TS usually work, but
  convert them to be safe. The TV is 1080p and storage is tight, so 720p is a good size for shows:

```bash
ffmpeg -i in.mkv -map 0:v:0 -map 0:a:0 -c:v libx264 -preset medium -crf 23 -vf "scale=-2:'min(720,ih)'" \
  -profile:v high -level 4.1 -pix_fmt yuv420p -c:a aac -b:a 160k -ac 2 -movflags +faststart "out.mp4"
```

  For a file that is already H.264 + AAC, `ffmpeg -i in.mkv -c copy -movflags +faststart out.mp4` changes the
  container only (fast, no quality loss). Check with `ffprobe -v error -show_entries stream=codec_name,width,height in.mkv`.
- Posters: JPG, PNG or WebP. `poster.jpg` at the top of a show folder covers all its seasons.

The Cloud library (README › Your own movies and shows) streams from an R2/S3 bucket instead; the user chose not to
use it so far.

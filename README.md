# BootTV

An ad-free, Roku-style home screen for Android TV / Google TV boxes. Fast grid of
your apps, one search box for everything, free live TV, your own movie library,
and none of the sponsored rows, "featured" ads or tracking that stock launchers ship with.

BootTV is a **launcher**: it replaces the device's home screen. Your streaming
apps (Netflix, YouTube, Prime Video, Disney+, ...) keep working as normal apps.

## Features

| Roku feature | BootTV |
|---|---|
| Home screen app grid | Big-tile grid; **move**, **hide** and **lock** (PIN) any app |
| Search | **Universal search** across installed apps, live channels and your own media; **voice search** with the device remote's mic |
| The Roku Channel / Live TV | **Live TV guide** with free, legal channels (Pluto TV, Samsung TV Plus, Plex, Roku Channel feeds) plus **HDHomeRun** antenna tuners on your network |
| Streaming store | Google Play / your device's store, untouched |
| Media library | Netflix-style **My Media** powered by Jellyfin on your Mac ([setup guide](docs/jellyfin.md)) |
| Web browsing | Built-in TV browser with **ad and tracker blocking** |
| Screensaver | Built-in screensaver (clock/photo), no ads |
| Parental controls | **Parental PIN** for locked apps and settings |
| Settings | Theme, rows, apps, live TV sources, My Media server, PIN |

**What it removes:** home-screen ads and sponsored rows, "recommended for you" promos,
autoplaying trailers, usage tracking/telemetry from the launcher. With
`tools/debloat.sh` it also disables the stock launcher and ad/telemetry packages
(reversibly).

## What to buy

Your TV isn't a smart TV, so you need a streaming box on HDMI. Pick one that runs
**Google TV / Android TV** — BootTV can fully replace the home screen there.

| Device | Approx. price | Verdict |
|---|---|---|
| **Walmart Onn 4K Pro** (Google TV) | ~$50 | **Best pick.** Fast, 3 GB RAM, Ethernet port, voice remote with mic, Dolby Vision/Atmos. |
| **Google TV Streamer** | ~$100 | Fastest, Ethernet, great remote. Pricier. |
| Fire TV Stick 4K (Max) | ~$40–60 | Works, but Amazon **blocks launcher replacement** (see limitations). |

Avoid cheap no-name Android boxes (no security updates, often preloaded malware) and
the old Chromecast with Google TV HD (2 GB / slow storage).

**Remote:** your Roku voice remote only talks to Roku devices. Use the remote that comes
with the new box — its mic button works with BootTV's voice search.

## Quickstart (Mac)

Everything runs from the project folder in Terminal. No Homebrew or admin rights needed.

```bash
cd ~/Desktop/roku-boot
source env.sh                 # Java + Android SDK + adb on PATH

tools/build.sh                # -> dist/BootTV.apk (use --debug for a debug build)

# On the TV: enable Developer options + (wireless) debugging. Instructions:
tools/connect.sh --help
tools/connect.sh 192.168.1.50                         # older builds / Fire TV
tools/connect.sh --pair 192.168.1.50:37123 123456 192.168.1.50:41234   # Google TV 12+

tools/install.sh              # install, set as Home, grant mic, launch

tools/debloat.sh --dry-run    # preview what gets disabled
tools/debloat.sh              # disable ads/bloat (asks you to type DISABLE)
tools/debloat.sh --restore    # undo everything it did
```

If several devices are connected, pass the serial (e.g. `192.168.1.50:5555`) as the
first argument to `install.sh` / `debloat.sh`.

### About debloating

`tools/debloat.sh` only **disables** packages (`pm disable-user`), never uninstalls,
and logs each change to `tools/debloat-<serial>-<date>.log` so `--restore` undoes
exactly that. A factory reset also restores everything.

- **Google TV:** disables recommendation/feedback services, and the stock launcher
  (`launcherx` + `setupwraith`) **only after** checking BootTV is installed and is the
  Home app; afterwards it verifies Home → BootTV and rolls back automatically if not.
  `--include-apps` also disables Play Games, YouTube Music/Kids, ambient mode, Play Movies.
- **Fire TV:** disables Amazon telemetry/crash reporting (and, with `--include-ota`,
  system updates — not recommended). The Amazon launcher is left alone.
- Never touched: Play Services, Play Store, Settings, System UI, keyboards,
  remote/Bluetooth, Chromecast built-in, and Google voice search (`com.google.android.katniss`,
  needed for the remote's mic; disable by hand if you really never use it).

## Test on the emulator

```bash
source env.sh
tools/emulator.sh             # boots the BootTV_API34 Android TV AVD, installs, launches
tools/emulator.sh --headless  # same, without a window
```

## Your own movies and shows

See **[docs/jellyfin.md](docs/jellyfin.md)**: install Jellyfin on the Mac, point it at
your folders, then BootTV → Settings → My Media → Discover / Quick Connect.

## Project layout

```
app/src/main/java/dev/boottv/
  launcher/     home screen, app grid (move / hide / lock)
  settings/     settings screens, parental PIN
  search/       universal + voice search UI
  browser/      ad-blocking TV web browser
  livetv/       live TV guide (Pluto, Samsung TV Plus, Plex, Roku Channel, HDHomeRun)
  jellyfin/     My Media (Jellyfin client)
  screensaver/  DreamService screensaver
  core/         shared UI, navigation, search registry, storage
tools/          build / connect / install / debloat / emulator scripts
docs/           guides (Jellyfin)
dist/           built APK (BootTV.apk)
```

Kotlin + Jetpack Compose for TV, `applicationId dev.boottv`, min SDK 24.

## Honest limitations

- **Roku voice remote won't work** — it only pairs with Roku devices. Use the new box's remote.
- **Netflix, Disney+, Prime Video, etc.** play in their official apps (BootTV launches
  them). BootTV can't remove ads *inside* those apps or inside free live channels.
- **Fire TV:** Amazon forces its own launcher; Home always goes back to it. Use
  "Button Mapper" to open BootTV from a remote button. Some Fire OS versions also
  refuse to disable system packages.
- **Google TV system updates** may re-enable the stock launcher. Rerun
  `tools/debloat.sh` after an update if the old home screen returns.
- Live channel lineups come from public free feeds and can change or disappear.
- Some features (Play Store "Continue watching" rows, Google Assistant answers on the
  home screen) belong to Google's launcher and aren't replicated.

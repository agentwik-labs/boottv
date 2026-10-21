# BootTV

An ad-free, Roku-style home screen for Android TV / Google TV boxes. Fast grid of
your apps, one search box for everything, free live TV, your own movie library,
and none of the sponsored rows, "featured" ads or tracking that stock launchers ship with.

The look is minimal and quiet, in the spirit of Apple TV: near-black greys, the Inter typeface,
artwork-led banners, cards that lift and glow when focused instead of showing outlines, and an
icon-only side rail that opens when you press Left.

Two looks (Settings › Home screen › Theme): **Pocket Monsters**, a playful Pokémon-inspired theme drawn
from scratch (night-battle indigo, capture-ball red, electric-yellow focus, rounded Fredoka titles, and a
"Pokémon on now" row of the official free Pokémon channels), and **Minimal**, the quiet Apple TV-style one.
Pocket Monsters adds Pokémon sprites: a partner by the clock, a Pokémon for each menu entry and row, and Pokémon
cards for anything without a picture. The sprites are © Nintendo / Creatures / GAME FREAK and are not included in
BootTV: the TV downloads them for personal use from the [PokeAPI sprite collection](https://github.com/PokeAPI/sprites),
and they can be turned off (Settings › Home screen › Look).

BootTV is a **launcher**: it replaces the device's home screen. Your streaming
apps (Netflix, YouTube, Prime Video, Disney+, ...) keep working as normal apps.

## Features

| Roku feature | BootTV |
|---|---|
| Home screen app grid | Big-tile grid; **move**, **hide** and **lock** (PIN) any app |
| Search | **Universal search** across installed apps, live channels and your own media; **voice search** with the device remote's mic |
| The Roku Channel / Live TV | **Live TV guide** with free, legal channels (Pluto TV, Samsung TV Plus, Plex, Roku Channel feeds) plus **HDHomeRun** antenna tuners on your network |
| Streaming store | Google Play / your device's store, untouched |
| Media library | **My Media**: your own videos on the TV, in folders with pictures (a poster you send, or a frame from the video), Continue watching, and a "Make it a channel" card per folder. **Send from your phone**: the TV shows an address, QR code and 4-digit code; any phone or computer on the Wi-Fi uploads videos (and posters) from its browser. Videos from LocalSend or a USB drive show up too. Your Mac's Jellyfin library is one press away ([setup guide](docs/jellyfin.md)) |
| Web browsing | Built-in TV browser with **ad and tracker blocking** |
| — | **Web apps**: pin any website to Home as an app tile (Add website, or Add to Home in the browser) |
| — | **Works out of the box**: a fresh install adds the free Pluto TV, Samsung TV Plus, Plex and Roku Channel line-ups |
| — | **Live preview**: the focused channel plays silently behind the Home banner (can be turned off in Settings) |
| — | **Cartoons**: anime and cartoon channels live now, free anime apps (RetroCrush, Tubi, Pluto TV), and public-domain cartoons sorted by series (Popeye, Fleischer Superman, Betty Boop, Looney Tunes, Casper, silent-era Felix and early Mickey, Creative Commons shorts); a **Cartoons & Anime** filter in the Live TV guide |
| — | **Free Movies**: public-domain and Creative Commons films from the Internet Archive, with search, downloads for offline viewing that keep going in the background (Archive's own torrents only), and Resume / Continue watching. A **rights check** hides anything archive.org doesn't list as public domain or Creative Commons, unless it came out in 1930 or earlier |
| — | **My channels**: turn your own movies and shows (My Media library, or videos copied to the TV / a USB drive) into channels that play around the clock, in the guide and on Home (Settings › Live TV) |
| — | **Remote hot buttons**: Guide/TV opens the guide, Channel ▲▼ jumps to the last channel, and any spare remote button can open a section or one of your channels, inside BootTV or (with the optional accessibility setting) from any app (Settings › Remote buttons) |
| — | **Device control** (optional): BootTV becomes the stick's device owner (`tools/owner.sh` on a freshly reset stick), so Home always opens it, Google's home screen and ad services stay hidden even after updates, an optional lockdown blocks factory reset / safe mode / new users, and you choose when system updates install. Fully reversible in Settings › Device control |
| — | **Your TV provider (Spectrum)**: get or open the Spectrum TV app from Settings › Live TV, pin it to Home or a remote button, or open it from the menu over any app. Live TV and the Guide button stay BootTV's own guide |
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
| **Xiaomi TV Stick 4K (2nd Gen)** (Google TV) | ~$70 | **Best HDMI stick on Amazon** ([B0FFN6NRTZ](https://www.amazon.com/dp/B0FFN6NRTZ)). Voice remote, Wi-Fi 6. |
| **Walmart Onn 4K Pro** (Google TV) | ~$50 | Best box. Fast, 3 GB RAM, Ethernet port, voice remote with mic, Dolby Vision/Atmos. |
| **Google TV Streamer** | ~$100 | Fastest, Ethernet, great remote. Pricier. |
| Fire TV Stick 4K Max | ~$40–60 | Installs, but Amazon **blocks launcher replacement** (see limitations). |
| Fire TV Stick 4K Select / HD (2026) / new 4K (2026) | — | **Won't work.** Vega OS, not Android: no APK installs. |

Buying guide with screenshots: [docs/stick.html](docs/stick.html).

Avoid cheap no-name Android boxes (no security updates, often preloaded malware) and
the old Chromecast with Google TV HD (2 GB / slow storage).

**Older TV without HDMI?** Add an HDMI-to-RCA (AV) converter with an NTSC/PAL switch (~$15–30); the picture is standard definition.

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

## Speed

BootTV ships a **Baseline Profile** (`app/src/release/generated/baselineProfiles`): the code for
startup, the Home rows, the side menu, the guide, Free Movies and Cartoons is compiled ahead of time,
so a cheap stick doesn't stutter on first use. Regenerate it after big UI changes, and compare
startup and scrolling with and without it, on the emulator or a connected stick:

```bash
tools/profile.sh              # regenerate the profile (drives the app with the remote)
tools/profile.sh --benchmark  # startup time and frame timing, without vs. with the profile
```

## Your own movies and shows

See **[docs/jellyfin.md](docs/jellyfin.md)**: install Jellyfin on the Mac, point it at
your folders, then BootTV → Settings → My Media → Discover / Quick Connect.

## Project layout

```
app/src/main/java/dev/boottv/
  launcher/     home screen, app grid (move / hide / lock), live preview
  archive/      Free Movies and Cartoons (Internet Archive), downloads, resume
  settings/     settings screens, parental PIN
  search/       universal + voice search UI
  browser/      ad-blocking TV web browser
  livetv/       live TV guide (Pluto, Samsung TV Plus, Plex, Roku Channel, HDHomeRun)
  jellyfin/     My Media (Jellyfin client)
  screensaver/  DreamService screensaver
  core/         shared UI, navigation, search registry, storage
baselineprofile/ Baseline Profile generator and startup / scrolling benchmarks
tools/          build / connect / install / debloat / emulator / profile scripts
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

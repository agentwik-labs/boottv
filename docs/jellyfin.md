# Your own Netflix: Jellyfin on your Mac + BootTV "My Media"

Jellyfin is a free, open-source media server. It runs on your Mac, organizes your
movie and TV files with posters and descriptions, and BootTV's **My Media** row
plays them on the TV. No account with any company, no ads, no tracking.

## 1. Install Jellyfin Server on macOS

1. Open <https://jellyfin.org/downloads/server> and choose **macOS**.
   Download the **Apple Silicon (arm64)** `.dmg` (Intel Macs: the x64 one).
2. Open the `.dmg` and drag **Jellyfin** into **Applications**. No admin password
   or Homebrew is needed if you drag it to the Applications folder in your home
   folder (`~/Applications`) instead — both work.
3. Open Jellyfin from Applications. macOS may say it is from an unidentified
   developer: open **System Settings → Privacy & Security**, scroll down and click
   **Open Anyway**.
4. A small jellyfish icon appears in the menu bar and your browser opens
   <http://localhost:8096>. If it doesn't, open that address yourself.
5. If macOS asks whether Jellyfin may accept incoming network connections, click
   **Allow** (the TV needs this).

## 2. First-run wizard (http://localhost:8096)

1. Pick your language → **Next**.
2. Create a username and password (this is your Jellyfin login, used once by BootTV).
3. **Add Media Library**:
   - Content type **Movies**, folder → your movies folder (e.g. `~/Movies/Movies`).
   - Add another: content type **Shows**, folder → your TV folder (e.g. `~/Movies/Shows`).
   - Leave metadata language/country at your preference; defaults are fine.
4. Metadata language → **Next**.
5. **Allow remote connections to this server**: leave checked (this means other
   devices on your home network, like the TV). Leave port mapping (UPnP) **off**.
6. **Finish**, then sign in. Jellyfin scans your folders and downloads artwork.

## 3. Name your files so posters and episodes match

Jellyfin identifies titles from folder and file names. Use this layout:

```
Movies/
  The Matrix (1999)/
    The Matrix (1999).mkv
  Spirited Away (2001)/
    Spirited Away (2001).mp4
Shows/
  Breaking Bad/
    Season 01/
      Breaking Bad S01E01.mkv
      Breaking Bad S01E02.mkv
    Season 02/
      Breaking Bad S02E01.mkv
```

Tips:
- Always include the year in parentheses for movies.
- Episodes need `S01E01`-style numbers; season folders are `Season 01`, `Season 02`, ...
- Subtitles next to the video with the same name work automatically:
  `The Matrix (1999).en.srt`.
- Something matched wrong? In Jellyfin open the item → **⋮ → Identify**.
- After adding files: Dashboard → Libraries → **Scan All Libraries** (it also rescans on its own).

## 4. Find your Mac's IP address

**System Settings → Wi-Fi → Details…** (next to your network) → **IP address**,
e.g. `192.168.1.23`. Your server address is then `http://192.168.1.23:8096`.
Test it from your phone's browser on the same Wi-Fi.

Tip: in your router's settings, give the Mac a reserved/static IP so this never changes.

## 5. Keep the Mac awake and Jellyfin running

- **System Settings → Energy** (MacBooks: **Battery → Options**) → turn on
  **Prevent automatic sleeping when the display is off** (MacBooks: "on power adapter").
  The display can still turn off; the Mac just must not sleep.
- Start Jellyfin at login: **System Settings → General → Login Items → +** →
  choose **Jellyfin**.
- Laptops: closing the lid sleeps the Mac unless an external display is attached.

## 6. Connect BootTV

On the TV: **BootTV → Settings → My Media**.

1. Choose **Discover servers** — BootTV finds Jellyfin on your network automatically.
   If it isn't found, choose **Enter address** and type `http://<mac-ip>:8096`.
2. Sign in either way:
   - **Quick Connect** (no typing with the remote): BootTV shows a 6-character code.
     On your Mac go to <http://localhost:8096> → profile icon → **Quick Connect**,
     enter the code, **Authorize**. (If Quick Connect is missing, enable it in
     Dashboard → General → **Enable Quick Connect**.)
   - Or type your Jellyfin username and password.
3. Your **Continue Watching**, **Movies** and **Shows** rows appear on the home screen
   and in universal search.

## Troubleshooting

| Problem | Fix |
|---|---|
| TV can't find the server | Same Wi-Fi network? Mac awake? Try the address manually. Check macOS firewall: System Settings → Network → Firewall → Options → allow Jellyfin. |
| Playback stutters | Prefer H.264/HEVC `.mp4`/`.mkv`. Wired Ethernet on the TV device helps a lot. In Jellyfin Dashboard → Playback, keep hardware acceleration on "Apple VideoToolbox". |
| Wrong poster/title | Fix the folder name (see section 3) or use **Identify**. |
| Server address changed | Reserve the Mac's IP in your router, then re-enter it in BootTV Settings → My Media. |

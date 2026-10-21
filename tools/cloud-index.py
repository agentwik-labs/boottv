#!/usr/bin/env python3
"""Write boottv.json for a folder of your own videos, so BootTV can stream them from a bucket.

    tools/cloud-index.py ~/Videos/BootTV                      -> ~/Videos/BootTV/boottv.json
    tools/cloud-index.py ~/Videos/BootTV --name "Our library"

Then upload the folder (videos, posters and boottv.json together) and point BootTV at boottv.json
(Settings > My Media > Cloud library). Examples:

    # Cloudflare R2 (no charge for streaming out; set up rclone with your R2 keys first)
    rclone sync ~/Videos/BootTV r2:my-bucket/media --progress
    # Amazon S3
    aws s3 sync ~/Videos/BootTV s3://my-bucket/media

Lay the folder out the way Send to TV expects: Show/Season 1/S01E03 - Title.mp4, poster.jpg or Title.jpg
beside the videos. Durations come from ffprobe (part of ffmpeg); without it, channels can't tell how long
a video is, so install ffmpeg first (brew install ffmpeg).
"""
import json, os, subprocess, sys, time

VIDEO = {".mp4", ".m4v", ".mkv", ".webm", ".mov", ".ts", ".m2ts", ".3gp", ".mpg", ".mpeg"}
IMAGE = {".jpg", ".jpeg", ".png", ".webp"}
POSTER_NAMES = {"poster", "folder", "cover"}


def duration_ms(path):
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                             capture_output=True, text=True, timeout=120).stdout.strip()
        return int(float(out) * 1000)
    except Exception:
        return 0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args or "-h" in sys.argv or "--help" in sys.argv:
        print(__doc__); sys.exit(0 if args else 1)
    root = os.path.abspath(args[0])
    name = "Cloud library"
    if "--name" in sys.argv:
        name = sys.argv[sys.argv.index("--name") + 1]
    have_ffprobe = subprocess.run(["which", "ffprobe"], capture_output=True).returncode == 0
    if not have_ffprobe:
        print("warning: ffprobe not found; durations will be 0 and channels will skip these videos (brew install ffmpeg)", file=sys.stderr)

    videos, images = [], {}
    for dirpath, dirs, files in os.walk(root):
        dirs[:] = sorted(d for d in dirs if not d.startswith("."))
        rel_dir = os.path.relpath(dirpath, root).replace(os.sep, "/")
        rel_dir = "" if rel_dir == "." else rel_dir
        for f in sorted(files):
            if f.startswith("."):
                continue
            base, ext = os.path.splitext(f)
            rel = f"{rel_dir}/{f}" if rel_dir else f
            if ext.lower() in IMAGE:
                key = f"{rel_dir}/" if base.lower() in POSTER_NAMES else f"{rel_dir}/{base.lower()}"
                images[key] = rel
            elif ext.lower() in VIDEO:
                videos.append((rel, rel_dir, base, os.path.join(dirpath, f)))

    def poster_for(rel_dir, base):
        # Title.jpg beside the video, else poster.jpg in its folder, then in the folders above it.
        p = images.get(f"{rel_dir}/{base.lower()}")
        d = rel_dir
        while p is None:
            p = images.get(f"{d}/")
            if not d:
                break
            d = d.rsplit("/", 1)[0] if "/" in d else ""
        return p

    entries = []
    for i, (rel, rel_dir, base, full) in enumerate(videos, 1):
        st = os.stat(full)
        print(f"[{i}/{len(videos)}] {rel}", file=sys.stderr)
        entries.append({
            "path": rel,
            "duration": duration_ms(full) if have_ffprobe else 0,
            "size": st.st_size,
            "added": int(st.st_mtime),
            "poster": poster_for(rel_dir, base),
        })
    manifest = {"version": 1, "name": name, "generated": int(time.time()), "videos": entries}
    out = os.path.join(root, "boottv.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=1)
    total = sum(e["size"] for e in entries) / 1e9
    print(f"wrote {out}: {len(entries)} videos, {total:.1f} GB. Upload the whole folder, then paste the address of boottv.json into BootTV.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Generate original moving test patterns. Pass a local FFmpeg with libx264 support."""
from pathlib import Path
import argparse
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("ffmpeg", help="Path to the FFmpeg executable")
args = parser.parse_args()
output = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
output.mkdir(parents=True, exist_ok=True)
for profile in ("baseline", "high"):
    subprocess.run([
        args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
        "-i", "testsrc2=size=800x480:rate=30", "-frames:v", "60", "-an",
        "-c:v", "libx264", "-preset", "medium", "-profile:v", profile,
        "-level:v", "3.1", "-pix_fmt", "yuv420p", "-bf", "0", "-g", "30",
        "-crf", "28", "-threads", "1", "-map_metadata", "-1", "-movflags", "+faststart",
        str(output / f"probe_{profile}.mp4"),
    ], check=True)
    print(f"Generated {profile}: 800x480, 30 fps, 60 frames, no audio")

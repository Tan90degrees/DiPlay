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
subprocess.run([
    args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
    "-i", "sine=frequency=550:sample_rate=44100:duration=2", "-af", "volume=0.15",
    "-c:a", "aac", "-profile:a", "aac_low", "-b:a", "128k", "-ar", "44100", "-ac", "2",
    "-map_metadata", "-1", "-movflags", "+faststart", str(output / "probe_aac.m4a"),
], check=True)
print("Generated AAC-LC: 44100 Hz stereo tone, 2 seconds")

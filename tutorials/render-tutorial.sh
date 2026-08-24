#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "Usage: $0 <silent-video> <narration.txt> <output.mp4> [voice]" >&2
  exit 2
fi

task_video=$1
task_narration=$2
task_output=$3
task_voice=${4:-Samantha}

if [[ ! -f "$task_video" ]]; then
  echo "Video not found: $task_video" >&2
  exit 2
fi
if [[ ! -f "$task_narration" ]]; then
  echo "Narration not found: $task_narration" >&2
  exit 2
fi
if ! command -v say >/dev/null 2>&1; then
  echo "macOS say is required to synthesize narration." >&2
  exit 2
fi
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg is required to render the tutorial." >&2
  exit 2
fi

task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT

say -v "$task_voice" -f "$task_narration" -o "$task_tmp/narration.aiff"

task_audio_duration=$(ffprobe -v error \
  -show_entries format=duration \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_tmp/narration.aiff")
if [[ -z "$task_audio_duration" || "$task_audio_duration" == "0.000000" ]]; then
  echo "Speech synthesis produced an empty track. Allow macOS speech services and run again." >&2
  exit 1
fi

# Re-encode Playwright WebM or any other source to a platform-friendly MP4,
# normalize speech, and stop when the shorter track ends. Record a small visual
# tail after the final action so narration—not video—normally determines length.
ffmpeg -hide_banner -loglevel error -y \
  -i "$task_video" \
  -i "$task_tmp/narration.aiff" \
  -map 0:v:0 \
  -map 1:a:0 \
  -c:v libx264 \
  -preset medium \
  -crf 20 \
  -pix_fmt yuv420p \
  -vf "scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2" \
  -af "loudnorm=I=-16:LRA=11:TP=-1.5" \
  -c:a aac \
  -b:a 192k \
  -shortest \
  -movflags +faststart \
  "$task_output"

ffprobe -v error \
  -show_entries format=duration:stream=codec_name,width,height \
  -of default=noprint_wrappers=1 \
  "$task_output"

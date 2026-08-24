#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 5 ]]; then
  echo "Usage: $0 <silent-video> <narration.txt> <output.mp4> [voice] [words-per-minute]" >&2
  exit 2
fi

task_video=$1
task_narration=$2
task_output=$3
task_voice=${4:-Samantha}
task_speech_rate=${5:-115}

if ! [[ "$task_speech_rate" =~ ^[0-9]+$ ]] || (( task_speech_rate < 80 || task_speech_rate > 220 )); then
  echo "Speech rate must be a whole number from 80 to 220 words per minute." >&2
  exit 2
fi

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

say -v "$task_voice" -r "$task_speech_rate" -f "$task_narration" -o "$task_tmp/narration.aiff"

task_audio_duration=$(ffprobe -v error \
  -show_entries format=duration \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_tmp/narration.aiff")
if ! awk -v duration="$task_audio_duration" 'BEGIN {
  exit !(duration ~ /^[0-9]+([.][0-9]+)?$/ && duration > 0)
}'; then
  echo "Speech synthesis produced an empty track. Allow macOS speech services and run again." >&2
  exit 1
fi

task_video_duration=$(ffprobe -v error \
  -show_entries format=duration \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_video")
if ! awk -v duration="$task_video_duration" 'BEGIN {
  exit !(duration ~ /^[0-9]+([.][0-9]+)?$/ && duration > 0)
}'; then
  echo "The source video has no usable duration: $task_video" >&2
  exit 1
fi
if ! awk -v video="$task_video_duration" -v audio="$task_audio_duration" 'BEGIN {
  exit !(video + 0.25 >= audio)
}'; then
  echo "Source video (${task_video_duration}s) is shorter than narration (${task_audio_duration}s)." >&2
  echo "Record a visual tail after the final action so the narration is not cut off." >&2
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

task_video_codec=$(ffprobe -v error -select_streams v:0 \
  -show_entries stream=codec_name \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_output")
task_audio_codec=$(ffprobe -v error -select_streams a:0 \
  -show_entries stream=codec_name \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_output")
task_dimensions=$(ffprobe -v error -select_streams v:0 \
  -show_entries stream=width,height \
  -of csv=s=x:p=0 \
  "$task_output")
task_output_duration=$(ffprobe -v error \
  -show_entries format=duration \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_output")

if [[ "$task_video_codec" != "h264" || "$task_audio_codec" != "aac" || "$task_dimensions" != "1920x1080" ]]; then
  echo "Rendered file failed stream validation: video=$task_video_codec audio=$task_audio_codec dimensions=$task_dimensions" >&2
  exit 1
fi
if ! awk -v output="$task_output_duration" -v audio="$task_audio_duration" 'BEGIN {
  difference = output - audio
  if (difference < 0) difference = -difference
  exit !(output ~ /^[0-9]+([.][0-9]+)?$/ && output > 0 && difference <= 0.5)
}'; then
  echo "Rendered duration (${task_output_duration}s) does not preserve narration (${task_audio_duration}s)." >&2
  exit 1
fi

echo "video_codec=$task_video_codec"
echo "audio_codec=$task_audio_codec"
echo "dimensions=$task_dimensions"
echo "duration=$task_output_duration"

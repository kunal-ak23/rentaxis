#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 || $# -gt 5 ]]; then
  echo "Usage: $0 <silent-video> <narration.txt> <output.mp4> [voice] [words-per-minute]" >&2
  exit 2
fi

task_video=$1
task_narration=$2
task_output=$3
task_voice=${4:-${TUTORIAL_VOICE:-}}
task_speech_rate=${5:-125}
task_tts_provider=${TUTORIAL_TTS_PROVIDER:-openai}
task_subtitle=${TUTORIAL_SUBTITLE_FILE:-}

if [[ -z "$task_voice" ]]; then
  case "$task_tts_provider" in
    azure) task_voice=${AZURE_SPEECH_VOICE:-en-US-Harper:MAI-Voice-2} ;;
    mac) task_voice=Samantha ;;
    *) task_voice=marin ;;
  esac
fi

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
if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg is required to render the tutorial." >&2
  exit 2
fi

task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT

case "$task_tts_provider" in
  openai)
    task_audio="$task_tmp/narration.wav"
    node "$(dirname "$0")/synthesize-openai-narration.mjs" \
      "$task_narration" "$task_audio" "$task_voice" "$task_speech_rate"
    ;;
  azure)
    task_audio="$task_tmp/narration.mp3"
    node "$(dirname "$0")/synthesize-azure-narration.mjs" \
      "$task_narration" "$task_audio" "$task_voice" "$task_speech_rate"
    ;;
  external)
    task_audio=${TUTORIAL_AUDIO_FILE:-}
    if [[ -z "$task_audio" || ! -f "$task_audio" ]]; then
      echo "TUTORIAL_AUDIO_FILE must point to a generated narration file for the external provider." >&2
      exit 2
    fi
    ;;
  mac)
    if ! command -v say >/dev/null 2>&1; then
      echo "macOS say is required when TUTORIAL_TTS_PROVIDER=mac." >&2
      exit 2
    fi
    task_audio="$task_tmp/narration.aiff"
    say -v "$task_voice" -r "$task_speech_rate" -f "$task_narration" -o "$task_audio"
    ;;
  *)
    echo "Unsupported TTS provider: $task_tts_provider (expected azure, openai, external, or mac)." >&2
    exit 2
    ;;
esac

task_audio_duration=$(ffprobe -v error \
  -show_entries format=duration \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_audio")
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
# Re-encode Playwright WebM or any other source to a platform-friendly MP4,
# normalize speech, and hold the final video frame if narration runs longer.
task_rendered="$task_tmp/rendered.mp4"
task_subtitle_input=()
task_subtitle_map=()
task_subtitle_codec=()
if [[ -n "$task_subtitle" ]]; then
  if [[ ! -f "$task_subtitle" ]]; then
    echo "Subtitle file not found: $task_subtitle" >&2
    exit 1
  fi
  task_subtitle_input=(-i "$task_subtitle")
  task_subtitle_map=(-map 2:0)
  task_subtitle_codec=(-c:s mov_text -metadata:s:s:0 language=eng)
fi
ffmpeg -hide_banner -loglevel error -y \
  -i "$task_video" \
  -i "$task_audio" \
  "${task_subtitle_input[@]}" \
  -map 0:v:0 \
  -map 1:a:0 \
  "${task_subtitle_map[@]}" \
  -c:v libx264 \
  -preset medium \
  -crf 20 \
  -pix_fmt yuv420p \
  -vf "tpad=stop_mode=clone:stop_duration=600,scale=1920:1080:force_original_aspect_ratio=decrease,pad=1920:1080:(ow-iw)/2:(oh-ih)/2" \
  -af "loudnorm=I=-16:LRA=11:TP=-1.5" \
  -c:a aac \
  -b:a 192k \
  "${task_subtitle_codec[@]}" \
  -t "$task_audio_duration" \
  -shortest \
  -movflags +faststart \
  "$task_rendered"

task_video_codec=$(ffprobe -v error -select_streams v:0 \
  -show_entries stream=codec_name \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_rendered")
task_audio_codec=$(ffprobe -v error -select_streams a:0 \
  -show_entries stream=codec_name \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_rendered")
task_dimensions=$(ffprobe -v error -select_streams v:0 \
  -show_entries stream=width,height \
  -of csv=s=x:p=0 \
  "$task_rendered")
task_output_duration=$(ffprobe -v error \
  -show_entries format=duration \
  -of default=noprint_wrappers=1:nokey=1 \
  "$task_rendered")
task_subtitle_codec_name=""
if [[ -n "$task_subtitle" ]]; then
  task_subtitle_codec_name=$(ffprobe -v error -select_streams s:0 \
    -show_entries stream=codec_name \
    -of default=noprint_wrappers=1:nokey=1 \
    "$task_rendered")
fi

if [[ "$task_video_codec" != "h264" || "$task_audio_codec" != "aac" || "$task_dimensions" != "1920x1080" ]]; then
  echo "Rendered file failed stream validation: video=$task_video_codec audio=$task_audio_codec dimensions=$task_dimensions" >&2
  exit 1
fi
if [[ -n "$task_subtitle" && "$task_subtitle_codec_name" != "mov_text" ]]; then
  echo "Rendered file failed subtitle validation: subtitle=$task_subtitle_codec_name" >&2
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

mkdir -p "$(dirname "$task_output")"
mv "$task_rendered" "$task_output"

echo "tts_provider=$task_tts_provider"
echo "video_codec=$task_video_codec"
echo "audio_codec=$task_audio_codec"
echo "dimensions=$task_dimensions"
echo "duration=$task_output_duration"
if [[ -n "$task_subtitle" ]]; then
  echo "subtitle_codec=$task_subtitle_codec_name"
  echo "subtitle_file=$task_subtitle"
fi

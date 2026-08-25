#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 <tutorial-id> [voice] [words-per-minute]" >&2
  exit 2
fi

task_id=$(printf '%02d' "$((10#$1))")
task_tts_provider=${TUTORIAL_TTS_PROVIDER:-openai}
task_voice=${2:-${TUTORIAL_VOICE:-}}
task_rate=${3:-${TUTORIAL_SPEECH_RATE:-125}}

if [[ -z "$task_voice" ]]; then
  case "$task_tts_provider" in
    azure) task_voice=${AZURE_SPEECH_VOICE:-en-US-Harper:MAI-Voice-2} ;;
    mac) task_voice=Samantha ;;
    *) task_voice=marin ;;
  esac
fi
task_root=$(cd "$(dirname "$0")/.." && pwd)
task_web="$task_root/web"
task_output_dir=${TUTORIAL_OUTPUT_DIR:-"$task_root/tutorials/output"}
task_audio_dir=${TUTORIAL_AUDIO_OUTPUT_DIR:-"$task_root/tutorials/audio-generated"}
task_narration=$(find "$task_root/tutorials/narration" -maxdepth 1 -type f -name "${task_id}-*.txt" -print -quit)

if [[ -z "$task_narration" ]]; then
  echo "Narration not found for tutorial $task_id" >&2
  exit 2
fi

mkdir -p "$task_output_dir" "$task_audio_dir"
task_slug=$(basename "$task_narration" .txt)
task_silent="$task_output_dir/${task_slug}-silent.webm"
task_final="$task_output_dir/${task_slug}.mp4"
task_subtitles="$task_output_dir/${task_slug}.srt"

# Synthesize once, then use the same exact audio for capture timing and final
# rendering. Generating a second take after recording changes pauses and makes
# even identical narration drift away from the visuals.
case "$task_tts_provider" in
  azure)
    task_audio="$task_audio_dir/${task_slug}-azure.mp3"
    node "$task_root/tutorials/synthesize-azure-narration.mjs" \
      "$task_narration" "$task_audio" "$task_voice" "$task_rate"
    ;;
  openai)
    task_audio="$task_audio_dir/${task_slug}-openai.wav"
    node "$task_root/tutorials/synthesize-openai-narration.mjs" \
      "$task_narration" "$task_audio" "$task_voice" "$task_rate"
    ;;
  external)
    task_audio=${TUTORIAL_AUDIO_FILE:-}
    if [[ -z "$task_audio" || ! -f "$task_audio" ]]; then
      echo "TUTORIAL_AUDIO_FILE must point to generated narration for the external provider." >&2
      exit 2
    fi
    ;;
  mac)
    task_audio="$task_audio_dir/${task_slug}-mac.aiff"
    say -v "$task_voice" -r "$task_rate" -f "$task_narration" -o "$task_audio"
    ;;
  *)
    echo "Unsupported TTS provider: $task_tts_provider" >&2
    exit 2
    ;;
esac

node "$task_root/tutorials/generate-subtitles.mjs" \
  "$task_narration" "$task_audio" "$task_subtitles"

(
  cd "$task_web"
  TUTORIAL_VOICE="$task_voice" TUTORIAL_TTS_PROVIDER=external \
    TUTORIAL_AUDIO_FILE="$task_audio" \
    node ../tutorials/capture/record-tutorial.mjs \
    "$task_id" "$task_narration" "$task_silent" "$task_rate"
)

TUTORIAL_TTS_PROVIDER=external TUTORIAL_AUDIO_FILE="$task_audio" \
  TUTORIAL_SUBTITLE_FILE="$task_subtitles" "$task_root/tutorials/render-tutorial.sh" \
  "$task_silent" "$task_narration" "$task_final" "$task_voice" "$task_rate"

echo "tutorial=$task_id"
echo "output=$task_final"
echo "audio=$task_audio"
echo "subtitles=$task_subtitles"

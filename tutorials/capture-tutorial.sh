#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 <tutorial-id> [voice] [words-per-minute]" >&2
  exit 2
fi

task_id=$(printf '%02d' "$((10#$1))")
task_voice=${2:-${TUTORIAL_VOICE:-marin}}
task_rate=${3:-${TUTORIAL_SPEECH_RATE:-125}}
task_tts_provider=${TUTORIAL_TTS_PROVIDER:-openai}
task_root=$(cd "$(dirname "$0")/.." && pwd)
task_web="$task_root/web"
task_output_dir=${TUTORIAL_OUTPUT_DIR:-"$task_root/tutorials/output"}
task_narration=$(find "$task_root/tutorials/narration" -maxdepth 1 -type f -name "${task_id}-*.txt" -print -quit)

if [[ -z "$task_narration" ]]; then
  echo "Narration not found for tutorial $task_id" >&2
  exit 2
fi

mkdir -p "$task_output_dir"
task_slug=$(basename "$task_narration" .txt)
task_silent="$task_output_dir/${task_slug}-silent.webm"
task_final="$task_output_dir/${task_slug}.mp4"

(
  cd "$task_web"
  TUTORIAL_VOICE="$task_voice" TUTORIAL_TTS_PROVIDER="$task_tts_provider" \
    node ../tutorials/capture/record-tutorial.mjs \
    "$task_id" "$task_narration" "$task_silent" "$task_rate"
)

TUTORIAL_TTS_PROVIDER="$task_tts_provider" "$task_root/tutorials/render-tutorial.sh" \
  "$task_silent" "$task_narration" "$task_final" "$task_voice" "$task_rate"

echo "tutorial=$task_id"
echo "output=$task_final"

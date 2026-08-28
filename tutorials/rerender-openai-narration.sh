#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 <output-directory> [voice] [words-per-minute]" >&2
  exit 2
fi

task_output_dir=$1
task_voice=${2:-marin}
task_rate=${3:-125}
task_root=$(cd "$(dirname "$0")/.." && pwd)
task_env_file=${TUTORIAL_ENV_FILE:-"$(dirname "$task_output_dir")/.env.local"}

if [[ ! -d "$task_output_dir" ]]; then
  echo "Output directory not found: $task_output_dir" >&2
  exit 2
fi

task_count=0
while IFS= read -r task_silent; do
  task_stem=$(basename "$task_silent" -silent.webm)
  task_narration="$task_root/tutorials/narration/${task_stem}.txt"
  task_final="$task_output_dir/${task_stem}.mp4"
  if [[ ! -f "$task_narration" ]]; then
    echo "Narration not found for $task_stem" >&2
    exit 1
  fi
  TUTORIAL_ENV_FILE="$task_env_file" TUTORIAL_TTS_PROVIDER=openai \
    "$task_root/tutorials/render-tutorial.sh" \
    "$task_silent" "$task_narration" "$task_final" "$task_voice" "$task_rate"
  task_count=$((task_count + 1))
done < <(find "$task_output_dir" -maxdepth 1 -type f -name '*-silent.webm' -print | sort)

if (( task_count == 0 )); then
  echo "No silent tutorial sources found in $task_output_dir" >&2
  exit 1
fi

echo "rerendered=$task_count"
echo "voice=$task_voice"
echo "output_directory=$task_output_dir"

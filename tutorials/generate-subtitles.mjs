#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const [, , narrationArg, audioArg, outputArg] = process.argv;
if (!narrationArg || !audioArg || !outputArg) {
  console.error('Usage: node tutorials/generate-subtitles.mjs <narration.txt> <audio> <output.srt>');
  process.exit(2);
}

const narrationPath = path.resolve(narrationArg);
const audioPath = path.resolve(audioArg);
const outputPath = path.resolve(outputArg);
if (!fs.existsSync(narrationPath)) throw new Error(`Narration not found: ${narrationPath}`);
if (!fs.existsSync(audioPath)) throw new Error(`Audio not found: ${audioPath}`);

const duration = Number(execFileSync(
  'ffprobe',
  ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=noprint_wrappers=1:nokey=1', audioPath],
  { encoding: 'utf8' },
).trim());
if (!Number.isFinite(duration) || duration <= 0) throw new Error(`Audio duration is invalid: ${audioPath}`);

const narration = fs.readFileSync(narrationPath, 'utf8').trim();
const segmenter = typeof Intl.Segmenter === 'function'
  ? new Intl.Segmenter('en', { granularity: 'sentence' })
  : null;
const sentences = segmenter
  ? [...segmenter.segment(narration)].map(({ segment }) => segment.trim()).filter(Boolean)
  : narration.split(/(?<=[.!?])\s+/).map((sentence) => sentence.trim()).filter(Boolean);

function chunkSentence(sentence) {
  const words = sentence.split(/\s+/).filter(Boolean);
  const chunkCount = Math.max(1, Math.ceil(words.length / 12), Math.ceil(sentence.length / 78));
  const chunks = [];
  let offset = 0;
  for (let index = 0; index < chunkCount; index += 1) {
    const remainingWords = words.length - offset;
    const remainingChunks = chunkCount - index;
    const size = Math.ceil(remainingWords / remainingChunks);
    chunks.push(words.slice(offset, offset + size).join(' '));
    offset += size;
  }
  return chunks.filter(Boolean);
}

function wrapSubtitle(text) {
  const words = text.split(/\s+/);
  const lines = [''];
  for (const word of words) {
    const lineIndex = lines.length - 1;
    const candidate = [lines[lineIndex], word].filter(Boolean).join(' ');
    if (candidate.length > 42 && lines[lineIndex] && lines.length < 2) lines.push(word);
    else lines[lineIndex] = candidate;
  }
  return lines.join('\n');
}

function splitForTwoLines(text) {
  const cues = [];
  let lines = [''];
  for (const word of text.split(/\s+/)) {
    const lineIndex = lines.length - 1;
    const candidate = [lines[lineIndex], word].filter(Boolean).join(' ');
    if (candidate.length <= 42) {
      lines[lineIndex] = candidate;
    } else if (lines.length === 1) {
      lines.push(word);
    } else {
      cues.push(lines.join(' '));
      lines = [word];
    }
  }
  if (lines.some(Boolean)) cues.push(lines.join(' '));
  return cues;
}

function srtTimestamp(seconds) {
  const milliseconds = Math.max(0, Math.round(seconds * 1000));
  const hours = Math.floor(milliseconds / 3_600_000);
  const minutes = Math.floor((milliseconds % 3_600_000) / 60_000);
  const secs = Math.floor((milliseconds % 60_000) / 1000);
  const millis = milliseconds % 1000;
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')},${String(millis).padStart(3, '0')}`;
}

const cues = sentences.flatMap(chunkSentence).flatMap(splitForTwoLines);
const cueWeights = cues.map((cue) => cue.split(/\s+/).length);
const totalWeight = cueWeights.reduce((sum, weight) => sum + weight, 0);
let elapsed = 0;
const blocks = cues.map((cue, index) => {
  const start = elapsed;
  elapsed = index === cues.length - 1
    ? duration
    : elapsed + duration * (cueWeights[index] / totalWeight);
  return `${index + 1}\n${srtTimestamp(start)} --> ${srtTimestamp(elapsed)}\n${wrapSubtitle(cue)}`;
});

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${blocks.join('\n\n')}\n`);
console.log(`subtitle_cues=${cues.length}`);
console.log(`subtitle_duration=${duration.toFixed(3)}`);
console.log(`subtitle_file=${outputPath}`);

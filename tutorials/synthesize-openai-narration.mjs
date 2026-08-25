#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';

function usage() {
  console.error(
    'Usage: node tutorials/synthesize-openai-narration.mjs <narration.txt> <output.wav> [voice] [words-per-minute]',
  );
  process.exit(2);
}

const [, , narrationArg, outputArg, voiceArg = 'marin', rateArg = '125'] = process.argv;
if (!narrationArg || !outputArg) usage();

const narrationPath = path.resolve(narrationArg);
const outputPath = path.resolve(outputArg);
const envPath = process.env.TUTORIAL_ENV_FILE
  ? path.resolve(process.env.TUTORIAL_ENV_FILE)
  : path.join(import.meta.dirname, '.env.local');
const allowedVoices = new Set([
  'alloy',
  'ash',
  'ballad',
  'coral',
  'echo',
  'fable',
  'nova',
  'onyx',
  'sage',
  'shimmer',
  'verse',
  'marin',
  'cedar',
]);

if (fs.existsSync(envPath)) {
  for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
    const match = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (!match || process.env[match[1]]) continue;
    process.env[match[1]] = match[2].replace(/^['"]|['"]$/g, '');
  }
}

if (!fs.existsSync(narrationPath)) throw new Error(`Narration not found: ${narrationPath}`);
if (!process.env.OPENAI_API_KEY) {
  throw new Error(
    `OPENAI_API_KEY is missing. Add it to ${envPath} or export it in the shell.`,
  );
}
if (!allowedVoices.has(voiceArg)) {
  throw new Error(`Unsupported OpenAI voice: ${voiceArg}`);
}

const speechRate = Number(rateArg);
if (!Number.isInteger(speechRate) || speechRate < 80 || speechRate > 220) {
  throw new Error('Speech rate must be a whole number from 80 to 220 words per minute.');
}

const narration = fs.readFileSync(narrationPath, 'utf8').trim();
const spokenIntro = process.env.TUTORIAL_SPOKEN_INTRO?.trim();
const input = [spokenIntro, narration].filter(Boolean).join('\n\n');
const instructions =
  process.env.TUTORIAL_TTS_INSTRUCTIONS ||
  `Speak as a polished, confident property-management software educator at about ${speechRate} words per minute. ` +
    'Sound warm, engaging, proficient, and naturally expressive. Use varied intonation and short pauses between workflow sections. ' +
    'Keep the delivery professional and calm, never theatrical or salesy. Pronounce RentAxis as Rent Axis and AED as UAE dirhams.';

const response = await fetch('https://api.openai.com/v1/audio/speech', {
  method: 'POST',
  headers: {
    Authorization: `Bearer ${process.env.OPENAI_API_KEY}`,
    'Content-Type': 'application/json',
  },
  body: JSON.stringify({
    model: process.env.TUTORIAL_TTS_MODEL || 'gpt-4o-mini-tts',
    voice: voiceArg,
    input,
    instructions,
    response_format: 'wav',
  }),
});

if (!response.ok) {
  const body = await response.text().catch(() => '');
  throw new Error(`OpenAI speech generation failed (${response.status}): ${body.slice(0, 500)}`);
}

const audio = Buffer.from(await response.arrayBuffer());
if (audio.length < 1024) throw new Error('OpenAI speech generation returned an empty audio file.');
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, audio);
console.log(`model=${process.env.TUTORIAL_TTS_MODEL || 'gpt-4o-mini-tts'}`);
console.log(`voice=${voiceArg}`);
console.log(`audio=${outputPath}`);

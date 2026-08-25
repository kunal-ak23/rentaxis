#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';

function usage() {
  console.error(
    'Usage: node tutorials/synthesize-azure-narration.mjs <narration.txt> <output.mp3> [voice] [words-per-minute]',
  );
  process.exit(2);
}

function escapeXml(value) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;');
}

const [, , narrationArg, outputArg, voiceArg, rateArg = '125'] = process.argv;
if (!narrationArg || !outputArg) usage();

const narrationPath = path.resolve(narrationArg);
const outputPath = path.resolve(outputArg);
const envPath = process.env.TUTORIAL_ENV_FILE
  ? path.resolve(process.env.TUTORIAL_ENV_FILE)
  : path.join(import.meta.dirname, '.env.local');

if (fs.existsSync(envPath)) {
  for (const line of fs.readFileSync(envPath, 'utf8').split('\n')) {
    const match = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (!match || process.env[match[1]]) continue;
    process.env[match[1]] = match[2].replace(/^['"]|['"]$/g, '');
  }
}

if (!fs.existsSync(narrationPath)) throw new Error(`Narration not found: ${narrationPath}`);
if (!process.env.AZURE_SPEECH_KEY) {
  throw new Error(`AZURE_SPEECH_KEY is missing. Add it to ${envPath} or export it.`);
}
if (!process.env.AZURE_SPEECH_REGION) {
  throw new Error(`AZURE_SPEECH_REGION is missing. Add it to ${envPath} or export it.`);
}

const speechRate = Number(rateArg);
if (!Number.isInteger(speechRate) || speechRate < 80 || speechRate > 220) {
  throw new Error('Speech rate must be a whole number from 80 to 220 words per minute.');
}

const voice = voiceArg || process.env.AZURE_SPEECH_VOICE || 'en-US-Harper:MAI-Voice-2';
const style = process.env.AZURE_SPEECH_STYLE || 'hopeful';
const disclosure =
  process.env.TUTORIAL_AI_VOICE_DISCLOSURE || 'This tutorial uses an AI-generated voice.';
const narration = fs.readFileSync(narrationPath, 'utf8').trim();
const ratePercent = Math.round((speechRate / 135 - 1) * 100);
const rate = `${ratePercent >= 0 ? '+' : ''}${ratePercent}%`;
const spokenText = escapeXml(`${disclosure}\n\n${narration}`);
const styledText = style
  ? `<mstts:express-as style="${escapeXml(style)}"><prosody rate="${rate}">${spokenText}</prosody></mstts:express-as>`
  : `<prosody rate="${rate}">${spokenText}</prosody>`;
const ssml = `<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" xml:lang="en-US"><voice name="${escapeXml(voice)}">${styledText}</voice></speak>`;
const region = process.env.AZURE_SPEECH_REGION;

const response = await fetch(
  `https://${encodeURIComponent(region)}.tts.speech.microsoft.com/cognitiveservices/v1`,
  {
    method: 'POST',
    headers: {
      'Content-Type': 'application/ssml+xml',
      'Ocp-Apim-Subscription-Key': process.env.AZURE_SPEECH_KEY,
      'X-Microsoft-OutputFormat': 'audio-24khz-160kbitrate-mono-mp3',
      'User-Agent': 'RentAxisTutorialRenderer',
    },
    body: ssml,
  },
);

if (!response.ok) {
  const body = await response.text().catch(() => '');
  throw new Error(`Azure Speech synthesis failed (${response.status}): ${body.slice(0, 500)}`);
}

const audio = Buffer.from(await response.arrayBuffer());
if (audio.length < 1024) throw new Error('Azure Speech returned an empty audio file.');
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, audio);
console.log(`region=${region}`);
console.log(`voice=${voice}`);
console.log(`style=${style || 'default'}`);
console.log(`audio=${outputPath}`);

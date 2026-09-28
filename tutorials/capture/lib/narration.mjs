// Narration length: drives how long each scene holds so video and voice stay in step.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { narrationPath, speechRate, tutorialId, workRoot } from './context.mjs';

export function narrationDurationSeconds() {
  if ((process.env.TUTORIAL_TTS_PROVIDER || 'openai') !== 'mac') {
    const suppliedAudio = process.env.TUTORIAL_AUDIO_FILE;
    if (suppliedAudio && fs.existsSync(suppliedAudio)) {
      const rawDuration = execFileSync(
        'ffprobe',
        [
          '-v',
          'error',
          '-show_entries',
          'format=duration',
          '-of',
          'default=noprint_wrappers=1:nokey=1',
          suppliedAudio,
        ],
        { encoding: 'utf8' },
      ).trim();
      const duration = Number(rawDuration);
      if (!Number.isFinite(duration) || duration <= 0) {
        throw new Error(`The supplied narration audio has no usable duration: ${suppliedAudio}`);
      }
      return duration;
    }
    const narration = fs.readFileSync(narrationPath, 'utf8').trim();
    const spokenIntro = process.env.TUTORIAL_SPOKEN_INTRO?.trim();
    const wordCount = [spokenIntro, narration].filter(Boolean).join(' ').trim().split(/\s+/).length;
    return (wordCount / speechRate) * 60 * 1.08 + 4;
  }
  // Keep capture artifacts under tutorials/ so interrupted work is reviewable
  // and recoverable. System temporary folders are routinely purged.
  fs.mkdirSync(workRoot, { recursive: true });
  const taskDir = fs.mkdtempSync(path.join(workRoot, `timing-${tutorialId}-`));
  const audioPath = path.join(taskDir, 'narration.aiff');
  try {
    execFileSync('say', [
      '-v',
      process.env.TUTORIAL_VOICE || 'Samantha',
      '-r',
      String(speechRate),
      '-f',
      narrationPath,
      '-o',
      audioPath,
    ]);
    const rawDuration = execFileSync(
      'ffprobe',
      [
        '-v',
        'error',
        '-show_entries',
        'format=duration',
        '-of',
        'default=noprint_wrappers=1:nokey=1',
        audioPath,
      ],
      { encoding: 'utf8' },
    ).trim();
    const duration = Number(rawDuration);
    if (!Number.isFinite(duration) || duration <= 0) {
      throw new Error('Speech synthesis produced an invalid narration duration.');
    }
    return duration;
  } finally {
    // Retain timing audio alongside capture artifacts for reproducible review.
  }
}

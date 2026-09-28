#!/usr/bin/env node
// Tutorial recorder: the runner. Scenes live in scenarios/<id>.mjs (default
// export { role, scenes }) and shared helpers in lib/, so several tutorials can
// be refreshed in parallel without editing one file.
//
// Usage: node tutorials/capture/record-tutorial.mjs <tutorial-id> <narration.txt> <output.webm> [speech-rate]

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  authStatePath, baseURL, chromium, outputPath, qaDir, rawRoot, seed, tenantId, tenantName, tutorialId, validateOnly,
} from './lib/context.mjs';
import { installCursorOverlay, patchLocatorForCapture, pointerAt } from './lib/cursor.mjs';
import { narrationDurationSeconds } from './lib/narration.mjs';
import { addCallout, auditVisibleDialogContrast, clearCallout, suppressAutomaticOnboarding } from './lib/page.mjs';

const scenarioPath = path.join(import.meta.dirname, 'scenarios', `${tutorialId}.mjs`);
if (!fs.existsSync(scenarioPath)) throw new Error(`Tutorial ${tutorialId} does not have an automated capture scenario yet.`);
const { default: scenario } = await import(pathToFileURL(scenarioPath).href);
const scenes = scenario?.scenes;
if (!Array.isArray(scenes) || scenes.length === 0) throw new Error(`Tutorial ${tutorialId} does not have an automated capture scenario yet.`);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.mkdirSync(rawRoot, { recursive: true });
const videoDir = validateOnly
  ? null
  : fs.mkdtempSync(path.join(rawRoot, `tutorial-${tutorialId}-`));
const targetDuration = narrationDurationSeconds() + 2;
// Playwright starts each clip when the page is created, before scene timing
// begins, and keeps a short trailer while the page closes. Reserve that
// measured per-clip overhead so early scenes cannot push the closing workflow
// beyond the narration and get trimmed from the final MP4.
const clipOverheadSeconds = Number(process.env.TUTORIAL_CLIP_OVERHEAD_SECONDS || 1);
const sceneWeights = scenes.map((scene) => {
  const weight = Number(scene.weight ?? 1);
  return Number.isFinite(weight) && weight > 0 ? weight : 1;
});
const totalSceneWeight = sceneWeights.reduce((total, weight) => total + weight, 0);
const browser = await chromium.launch({ headless: true });
const recordedVideoPaths = [];
const storageStateByRole = new Map();

async function authenticatedStorageState(role) {
  if (storageStateByRole.has(role)) return storageStateByRole.get(role);
  if (role === 'superadmin') {
    storageStateByRole.set(role, authStatePath);
    return authStatePath;
  }
  if (role === 'anonymous') {
    storageStateByRole.set(role, undefined);
    return undefined;
  }
  const credentials = role === 'tenantAdmin'
    ? seed.adminLogin
    : role === 'renter'
      ? seed.renterLogins?.find((login) => login.name === 'Ahmed Hassan') || seed.renterLogins?.[0]
      : role === 'propertyManager'
        ? seed.operatorLogins?.find((login) => login.role === 'PROPERTY_MANAGER')
        : role === 'securityGuard'
          ? seed.operatorLogins?.find((login) => login.role === 'SECURITY_GUARD')
      : null;
  if (!credentials?.email || !credentials?.password) {
    throw new Error(`The seed manifest does not include credentials for ${role}.`);
  }
  const loginContext = await browser.newContext({ baseURL });
  await suppressAutomaticOnboarding(loginContext);
  const loginPage = await loginContext.newPage();
  await loginPage.goto('/en/auth/login');
  await loginPage.locator('#login-email').fill(credentials.email);
  await loginPage.locator('#login-password').fill(credentials.password);
  await loginPage.getByRole('button', { name: /sign in|log in/i }).click();
  // The production dashboard keeps a few long-lived resources open, so the
  // default `load` wait can time out after navigation has already succeeded.
  // DOM readiness is enough to establish the authenticated storage state.
  await loginPage.waitForURL(/\/en\/dashboard/, { timeout: 30_000, waitUntil: 'domcontentloaded' });
  const state = await loginContext.storageState();
  await loginContext.close();
  storageStateByRole.set(role, state);
  return state;
}

// One browser context (and so one video clip) per scene, except that a scene
// marked `continues` (see `stepScene`) carries on in the previous scene's
// context and page when both run as the same role.
let live = null;
let recordingStartedAt = null;

async function closeLive() {
  if (!live) return;
  const { context, page, video } = live;
  live = null;
  await page.close();
  if (video) recordedVideoPaths.push(await video.path());
  await context.close();
}

async function openLive(role, sceneIndex, scene, host) {
  const storageState = await authenticatedStorageState(role);
  const contextOptions = {
    baseURL,
    storageState,
    viewport: { width: 1920, height: 1080 },
    colorScheme: 'light',
    locale: 'en-AE',
  };
  if (!validateOnly) {
    contextOptions.recordVideo = { dir: videoDir, size: { width: 1920, height: 1080 } };
  }
  const context = await browser.newContext(contextOptions);
  await suppressAutomaticOnboarding(context);
  if (!validateOnly && sceneIndex === 0) {
    const introTitle = String(scene.title || 'RentAxis tutorial').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    await context.addInitScript({
      content: `(() => {
        try {
          if (sessionStorage.getItem('rentaxisRecordingIntroShown')) return;
          sessionStorage.setItem('rentaxisRecordingIntroShown', '1');
        } catch {}
        const mount = () => {
          if (document.querySelector('[data-rentaxis-recording-intro]')) return;
          const root = document.createElement('div');
          root.dataset.rentaxisRecordingIntro = 'true';
          root.innerHTML = '<div style="font-size:18px;letter-spacing:.18em;text-transform:uppercase;color:#f59e0b;margin-bottom:18px">RentAxis tutorial</div><div style="font-size:42px;line-height:1.1;font-weight:700;max-width:900px">${introTitle}</div>';
          Object.assign(root.style, {
            position: 'fixed', inset: '0', zIndex: '2147483647', display: 'flex',
            flexDirection: 'column', justifyContent: 'center', alignItems: 'center',
            textAlign: 'center', padding: '48px', color: '#f8fafc',
            background: 'linear-gradient(135deg, #0f172a 0%, #1e3a5f 100%)',
            fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
          });
          const mountTarget = document.body;
          if (mountTarget) mountTarget.append(root);
        };
        if (document.body) mount();
        else document.addEventListener('DOMContentLoaded', mount, { once: true });
      })();`,
    });
  }
  await context.addCookies([
    {
      name: 'active_tenant_id',
      value: tenantId,
      domain: host,
      path: '/',
      httpOnly: false,
      secure: baseURL.startsWith('https:'),
      sameSite: 'Lax',
    },
  ]);
  if (!validateOnly) await context.addInitScript(installCursorOverlay);
  const page = await context.newPage();
  const video = validateOnly ? null : page.video();
  if (!validateOnly) {
    patchLocatorForCapture(page);
    // Park the pointer where the cursor starts, so it is on screen from the first frame.
    await page.mouse.move(1500, 640);
    pointerAt.set(page, { x: 1500, y: 640 });
  }
  return { role, context, page, video };
}

try {
  const defaultRole = scenario.role || 'superadmin';
  const host = new URL(baseURL).hostname;

  for (const [sceneIndex, scene] of scenes.entries()) {
    const role = scene.role || defaultRole;
    if (!scene.continues || !live || live.role !== role) {
      if (scene.continues) {
        throw new Error(`Scene ${sceneIndex + 1} continues a page, but no page of role ${role} is open.`);
      }
      await closeLive();
      live = await openLive(role, sceneIndex, scene, host);
    }
    const { page } = live;

    const startedAt = Date.now();
    recordingStartedAt ??= startedAt;
    if (!validateOnly) {
      // Approximate offset into the recording, for checking scenes against subtitle cues.
      console.log(`scene=${sceneIndex + 1} starts_at=${((startedAt - recordingStartedAt) / 1000).toFixed(1)}s title=${scene.title}`);
    }
    await scene.run(page);
    const activeTourCount = await page.locator('.shepherd-element:visible').count();
    if (activeTourCount > 0 && !scene.allowTour) {
      throw new Error(`An onboarding tour opened unexpectedly in scene ${sceneIndex + 1}; recording stopped.`);
    }
    await auditVisibleDialogContrast(page);
    if (scene.verifyTenantContext !== false) {
      // Role-specific sessions fetch their tenant membership after the
      // application shell is visible. Wait for that asynchronous label
      // before deciding the context is wrong, otherwise a healthy session
      // can fail the preflight during a slow production response.
      await page.getByText(tenantName, { exact: true }).first().waitFor({ state: 'visible', timeout: 10_000 });
    }
    if (qaDir) {
      fs.mkdirSync(qaDir, { recursive: true });
      const qaName = `${tutorialId}-${String(sceneIndex + 1).padStart(2, '0')}-${scene.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '')}.png`;
      await page.screenshot({ path: path.join(qaDir, qaName), fullPage: false });
    }
    await addCallout(page, scene.title, scene.body);
    const sceneDuration = validateOnly
      ? 0.5
      // A new clip pays Playwright's per-clip overhead; a continuing scene instead
      // pays the pause after its callout clears (below), which would otherwise
      // push every later scene behind the narration.
      : Math.max(1, targetDuration * (sceneWeights[sceneIndex] / totalSceneWeight) - (scene.continues ? 0.4 : clipOverheadSeconds));
    const elapsedSeconds = (Date.now() - startedAt) / 1000;
    await page.waitForTimeout(Math.max(500, (sceneDuration - elapsedSeconds) * 1000));
    await clearCallout(page);
    await page.waitForTimeout(validateOnly ? 100 : 400);
  }
} finally {
  await closeLive().catch(() => {});
  await browser.close();
}

if (validateOnly) {
  console.log(`tutorial=${tutorialId}`);
  console.log(`tenant=${tenantName}`);
  console.log('scenario_validation=passed');
} else {
  const expectedClips = scenes.filter((scene) => !scene.continues).length;
  if (recordedVideoPaths.length !== expectedClips || recordedVideoPaths.some((videoPath) => !fs.existsSync(videoPath))) {
    throw new Error(`Playwright produced ${recordedVideoPaths.length} clips for ${expectedClips} tutorial contexts.`);
  }
  if (recordedVideoPaths.length === 1) {
    fs.copyFileSync(recordedVideoPaths[0], outputPath);
  } else {
    const concatPath = path.join(videoDir, 'concat.txt');
    fs.writeFileSync(
      concatPath,
      recordedVideoPaths.map((videoPath) => `file '${videoPath.replaceAll("'", "'\\''")}'`).join('\n'),
    );
    execFileSync('ffmpeg', [
      '-hide_banner',
      '-loglevel',
      'error',
      '-y',
      '-f',
      'concat',
      '-safe',
      '0',
      '-i',
      concatPath,
      '-c',
      'copy',
      outputPath,
    ]);
  }
  console.log(`tutorial=${tutorialId}`);
  console.log(`tenant=${tenantName}`);
  console.log(`target_duration=${targetDuration.toFixed(2)}`);
  console.log(`silent_video=${outputPath}`);
  console.log(`raw_video_dir=${videoDir}`);
}

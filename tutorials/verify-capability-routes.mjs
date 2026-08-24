#!/usr/bin/env node

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const tutorialDir = dirname(fileURLToPath(import.meta.url));
const repoDir = dirname(tutorialDir);
const routeMap = JSON.parse(
  readFileSync(join(tutorialDir, 'capability-route-map.json'), 'utf8'),
);
const failures = [];

function walk(directory) {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

function webRoutes() {
  const appDir = join(repoDir, 'web', 'src', 'app');
  return [...new Set(
    walk(appDir)
      .filter((path) => /\/(?:page|route)\.(?:tsx?|jsx?)$/.test(path))
      .map((path) => {
        const directory = dirname(relative(appDir, path)).split(sep).join('/');
        return directory === '.' ? '/' : `/${directory}`;
      }),
  )].sort();
}

function matchingParen(source, openIndex) {
  let depth = 0;
  let quote = null;
  let lineComment = false;
  let blockComment = false;
  for (let index = openIndex; index < source.length; index += 1) {
    const char = source[index];
    const next = source[index + 1];
    if (lineComment) {
      if (char === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (char === '*' && next === '/') {
        blockComment = false;
        index += 1;
      }
      continue;
    }
    if (quote) {
      if (char === '\\') {
        index += 1;
      } else if (char === quote) {
        quote = null;
      }
      continue;
    }
    if (char === '/' && next === '/') {
      lineComment = true;
      index += 1;
      continue;
    }
    if (char === '/' && next === '*') {
      blockComment = true;
      index += 1;
      continue;
    }
    if (char === "'" || char === '"') {
      quote = char;
      continue;
    }
    if (char === '(') depth += 1;
    if (char === ')') {
      depth -= 1;
      if (depth === 0) return index;
    }
  }
  throw new Error(`Unclosed parenthesis at source offset ${openIndex}`);
}

function dartRoutes(app) {
  const source = readFileSync(
    join(repoDir, 'mobile', 'apps', app, 'lib', 'router.dart'),
    'utf8',
  );
  const nodes = [];
  let cursor = 0;
  while (true) {
    const start = source.indexOf('GoRoute(', cursor);
    if (start === -1) break;
    const open = start + 'GoRoute'.length;
    const end = matchingParen(source, open);
    const path = source.slice(start, end).match(/path:\s*'([^']+)'/)?.[1];
    if (!path) failures.push(`${app}: GoRoute at ${start} has no literal path`);
    nodes.push({ start, end, path });
    cursor = start + 'GoRoute('.length;
  }

  for (const node of nodes) {
    const parents = nodes.filter(
      (candidate) => candidate.start < node.start && candidate.end > node.end,
    );
    node.parent = parents.sort((a, b) => b.start - a.start)[0];
  }

  function fullPath(node) {
    if (node.fullPath) return node.fullPath;
    if (node.path.startsWith('/')) return (node.fullPath = node.path);
    const parent = node.parent ? fullPath(node.parent) : '/';
    return (node.fullPath = `${parent === '/' ? '' : parent}/${node.path}`);
  }

  return nodes.map(fullPath).sort();
}

function mappedRoutes(surface) {
  const groups = routeMap.surfaces[surface];
  if (!Array.isArray(groups)) {
    failures.push(`${surface}: route-map surface is missing`);
    return [];
  }
  const seen = new Set();
  for (const [index, group] of groups.entries()) {
    if (!Array.isArray(group.routes) || group.routes.length === 0) {
      failures.push(`${surface}[${index}]: group has no routes`);
    }
    if (!Array.isArray(group.evidence) || group.evidence.length === 0) {
      failures.push(`${surface}[${index}]: group has no evidence reference`);
    }
    for (const evidence of group.evidence ?? []) {
      if (!/^PR\d+:/.test(evidence) && !existsSync(join(repoDir, evidence))) {
        failures.push(`${surface}[${index}]: missing evidence file ${evidence}`);
      }
    }
    const tutorials = group.tutorials ?? [];
    if (tutorials.length === 0 && group.classification !== 'system') {
      failures.push(`${surface}[${index}]: unmapped group needs system classification`);
    }
    if (group.classification === 'system' && !group.reason) {
      failures.push(`${surface}[${index}]: system group needs a reason`);
    }
    for (const tutorial of tutorials) {
      if (!Number.isInteger(tutorial) || tutorial < 1 || tutorial > 33) {
        failures.push(`${surface}[${index}]: invalid tutorial ${tutorial}`);
      }
    }
    for (const route of group.routes ?? []) {
      if (seen.has(route)) failures.push(`${surface}: duplicate mapped route ${route}`);
      seen.add(route);
    }
  }
  return [...seen].sort();
}

const discovered = {
  web: webRoutes(),
  manager: dartRoutes('manager'),
  renter: dartRoutes('renter'),
  security: dartRoutes('security'),
};

for (const [surface, routes] of Object.entries(discovered)) {
  const mapped = mappedRoutes(surface);
  const missing = routes.filter((route) => !mapped.includes(route));
  const stale = mapped.filter((route) => !routes.includes(route));
  if (missing.length) failures.push(`${surface}: unmapped routes: ${missing.join(', ')}`);
  if (stale.length) failures.push(`${surface}: stale mapped routes: ${stale.join(', ')}`);
  console.log(`${surface}: ${routes.length} discovered, ${mapped.length} mapped`);
}

const tutorialCoverage = new Set(
  Object.values(routeMap.surfaces)
    .flat()
    .flatMap((group) => group.tutorials ?? []),
);
const missingTutorials = Array.from({ length: 33 }, (_, index) => index + 1)
  .filter((tutorial) => !tutorialCoverage.has(tutorial));
if (missingTutorials.length) {
  failures.push(`Tutorials have no mapped route: ${missingTutorials.join(', ')}`);
}

if (failures.length) {
  console.error(`\nCapability-route verification failed (${failures.length}):`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exitCode = 1;
} else {
  const total = Object.values(discovered).reduce((sum, routes) => sum + routes.length, 0);
  console.log(`\nCapability-route verification passed: ${total} shipped routes mapped to tutorials 01–33.`);
}

import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "src"),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
    exclude: ["node_modules", "e2e", ".next"],
    // Ceiling on the worker pool. The suite is ~112 files, each loading
    // Next.js + jsdom + React Testing Library into its own forked worker. Left
    // unbounded, Vitest spawns one worker per core (10 on this dev box, more on
    // a large CI host), and that many heavy Node processes exhausted memory
    // badly enough that workers failed to spawn at all ("Failed to start forks
    // worker" / "Timeout waiting for worker to respond"). A ceiling keeps
    // spawning reliable; because it is only a ceiling, a 2–4 core CI runner
    // (which forks fewer anyway) is unaffected. At this cap a full run is ~15 s.
    pool: "forks",
    maxWorkers: 4,
    // Headroom over the 5 s default for a genuinely heavy test under a full run.
    testTimeout: 15000,
  },
});

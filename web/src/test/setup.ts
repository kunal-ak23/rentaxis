import "@testing-library/jest-dom/vitest";
import { configure } from "@testing-library/dom";

// Give async queries (`waitFor`/`findBy`) headroom above their 1 s default so a
// render that is merely slow under a full parallel run fails with a clear
// Testing-Library message rather than being cut off early. This is a secondary
// guard: the real cause of the intermittent timeouts was an unbounded fork pool
// starving workers, which is fixed by the parallelism cap in vitest.config.ts.
// Kept below the 15 s test timeout so an async wait still reports first.
configure({ asyncUtilTimeout: 10000 });

// src/lib/workspace/keys.ts

/**
 * Workspace shortcuts (spec §10). A browser tab cannot take Ctrl+T/W/Tab/1…9,
 * so the keys are Alt-based. They match on `KeyboardEvent.code`, not `key`, so
 * they work with an Arabic keyboard layout. "Next" means towards the end of the
 * strip in both directions of writing.
 */
export type WorkspaceKey =
    | { action: "goto"; index: number }
    | { action: "last" }
    | { action: "next" }
    | { action: "prev" }
    | { action: "close" }
    | { action: "reopen" };

type KeyLike = Pick<KeyboardEvent, "code" | "altKey" | "ctrlKey" | "metaKey" | "shiftKey">;

export function workspaceKey(e: KeyLike): WorkspaceKey | null {
    if (!e.altKey || e.ctrlKey || e.metaKey) return null;
    if (e.shiftKey) return e.code === "KeyT" ? { action: "reopen" } : null;
    const digit = /^Digit([1-9])$/.exec(e.code);
    if (digit) return digit[1] === "9" ? { action: "last" } : { action: "goto", index: Number(digit[1]) - 1 };
    if (e.code === "PageDown") return { action: "next" };
    if (e.code === "PageUp") return { action: "prev" };
    if (e.code === "KeyW") return { action: "close" };
    return null;
}

/**
 * Arrow keys inside the strip follow the visual order (WAI-ARIA APG): in a
 * right-to-left page ArrowLeft moves to the next tab.
 */
export function arrowStep(code: string, rtl: boolean): 1 | -1 | null {
    if (code === "ArrowRight") return rtl ? -1 : 1;
    if (code === "ArrowLeft") return rtl ? 1 : -1;
    return null;
}

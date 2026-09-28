/**
 * Break-it R3 data3 F4: an import/post job's error in the viewer's language.
 *
 * The server never stores a raw exception any more (`ImportFailures`): a
 * unique-key race is "already being imported" and anything unexpected is
 * "failed — reference ABCD1234". Those carry a `code` (`import.alreadyRunning`,
 * `import.failedRef`, `post.alreadyRunning`, `post.failedRef`) and `args`; this
 * renders them from the `ImportErrors` catalog. Anything without a known code is
 * the server's own sentence, unchanged.
 */
export type CodedMessage = {
    message: string | null;
    code?: string | null;
    args?: Record<string, string> | null;
};

/** The `ImportErrors` translator (`useTranslations("ImportErrors")`), shaped like `serverText`'s. */
export type ImportErrorsTranslator = {
    (key: string, values?: Record<string, string | number>): string;
    has: (key: string) => boolean;
};

export function importErrorText(t: ImportErrorsTranslator, e: CodedMessage): string {
    if (e.code) {
        const key = e.code.replace(/\./g, "_");
        if (t.has(key)) {
            try {
                return t(key, { ...(e.args ?? {}) });
            } catch {
                // A malformed translation must not hide the error.
            }
        }
    }
    return e.message ?? "";
}

import { ApiError } from "@/lib/api/facilities";

/**
 * F14-09: server text in the user's language. A refusal from the bank-rec
 * services carries a stable `code` (e.g. `bankrec.chooseDateFormat`) and its
 * `args`, already formatted; the English `message` is the fallback for a code
 * this client does not know. Keys live under `BankRec.errors.<code>`.
 */
export type Coded = { code?: string | null; args?: Record<string, unknown> | null; message?: string | null };

type T = {
    (key: string, values?: Record<string, string | number>): string;
    has: (key: string) => boolean;
};

/** The code, args and message of an ApiError's JSON body, a `{code, args, message}` object, or anything else. */
export function codedOf(err: unknown): Coded {
    if (err instanceof ApiError) {
        let body: Coded = {};
        try {
            const parsed = err.body ? JSON.parse(err.body) : null;
            if (parsed && typeof parsed === "object") body = parsed as Coded;
        } catch {
            // Not JSON (a proxy's HTML page): the message ApiError already made is all there is.
        }
        return { code: body.code ?? null, args: body.args ?? null, message: err.message };
    }
    if (err && typeof err === "object" && ("code" in err || "message" in err)) {
        const e = err as Coded;
        return { code: e.code ?? null, args: e.args ?? null, message: e.message ?? null };
    }
    return { message: err == null ? "" : String(err) };
}

/** The translated text for a coded server message, else its English message. */
export function serverText(t: T, err: unknown): string {
    const c = codedOf(err);
    if (c.code) {
        const key = `errors.${c.code}`;
        if (t.has(key)) {
            const values: Record<string, string | number> = {};
            for (const [k, v] of Object.entries(c.args ?? {})) values[k] = typeof v === "number" ? v : String(v ?? "");
            try {
                return t(key, values);
            } catch {
                // A malformed translation must not hide the refusal.
            }
        }
    }
    return c.message ?? "";
}

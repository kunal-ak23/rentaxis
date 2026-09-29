import { vi } from "vitest";

/** One recorded request: method, URL and the body as sent. */
export type Call = { method: string; url: string; body: unknown };

type Reply = { status?: number; body?: unknown };
type Route = { method: string; match: (url: string) => boolean; reply: (call: Call) => Reply };

/**
 * A fetch stub routed by method and URL, recording every call. Unmatched calls
 * answer 404 so a page that asks for something the test did not plan fails
 * visibly rather than hanging. A FormData body is recorded as a plain object.
 */
export function fetchRouter() {
    const routes: Route[] = [];
    const calls: Call[] = [];
    const on = (method: string, match: string | RegExp | ((u: string) => boolean), reply: Reply | ((c: Call) => Reply)) => {
        const m = typeof match === "string" ? (u: string) => u.includes(match)
            : match instanceof RegExp ? (u: string) => match.test(u) : match;
        // Later routes win, so a test can override a default set up in beforeEach.
        routes.unshift({ method, match: m, reply: typeof reply === "function" ? reply : () => reply });
    };
    const impl = async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = (init?.method ?? "GET").toUpperCase();
        let body: unknown = init?.body;
        if (body instanceof FormData) body = Object.fromEntries(body.entries());
        else if (typeof body === "string") { try { body = JSON.parse(body); } catch { /* keep text */ } }
        const call = { method, url, body };
        calls.push(call);
        const route = routes.find(r => r.method === method && r.match(url));
        const { status = 200, body: payload = null } = route ? route.reply(call) : { status: 404, body: { message: "unplanned" } };
        const text = payload === null || payload === undefined ? "" : JSON.stringify(payload);
        return new Response(text, { status, headers: { "Content-Type": "application/json" } });
    };
    global.fetch = vi.fn(impl) as unknown as typeof fetch;
    return { on, calls, callsTo: (method: string, part: string) => calls.filter(c => c.method === method && c.url.includes(part)) };
}

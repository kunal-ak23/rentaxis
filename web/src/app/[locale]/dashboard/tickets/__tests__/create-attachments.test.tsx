import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// Break round 2 (portal2) F1: attachments picked in the Create Ticket modal
// were never uploaded. The input's onChange queued
// `setAttachmentFiles(prev => [...prev, ...Array.from(e.target.files!)])` and
// then cleared `e.target.value`. A browser empties the input's FileList when
// its value is set to "", and React runs that updater later (at render), so it
// read an empty list: nothing was listed, nothing uploaded, no error shown.
// The per-file upload loop also never checked the response.

let sessionState: { role: string } = { role: "RENTER" };
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: sessionState.role, id: "me-1" } }, status: "authenticated" }),
}));
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));
vi.mock("next-intl", async () => {
    const { createTranslator } = await vi.importActual<typeof import("next-intl")>("next-intl");
    const messages = (await import("../../../../../../messages/en.json")).default;
    const cache = new Map<string, ReturnType<typeof createTranslator>>();
    return {
        useTranslations: (namespace: string) => {
            if (!cache.has(namespace)) cache.set(namespace, createTranslator({ locale: "en", messages, namespace: namespace as never }));
            return cache.get(namespace)!;
        },
        useLocale: () => "en",
    };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));

import TicketsPage from "../page";

/**
 * Select files on a file input the way a browser does: `files` is the
 * input's live selection, and setting `value` to "" empties it (HTML spec,
 * and what Chrome was observed doing in the repro). jsdom's fireEvent with
 * `target: { files }` pins a static list the value reset can't clear, which
 * would hide the bug.
 */
function selectFiles(input: HTMLInputElement, files: File[]) {
    let selected: File[] = [...files];
    const asFileList = () => {
        const list: Record<number, File> & { length: number; item: (i: number) => File | null; [Symbol.iterator]: () => Iterator<File> } = {
            length: selected.length,
            item: (i: number) => selected[i] ?? null,
            [Symbol.iterator]: () => selected[Symbol.iterator](),
        } as never;
        selected.forEach((f, i) => { list[i] = f; });
        return list;
    };
    Object.defineProperty(input, "files", { configurable: true, get: () => asFileList() });
    Object.defineProperty(input, "value", {
        configurable: true,
        get: () => (selected[0] ? `C:\\fakepath\\${selected[0].name}` : ""),
        set: (v: string) => { if (v === "") selected = []; },
    });
    fireEvent.change(input);
}

type UploadReply = { ok: boolean; status: number };
let uploadReplies: UploadReply[] = [];
let calls: { url: string; method: string; body: unknown }[] = [];

function installFetch() {
    calls = [];
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        const method = init?.method ?? "GET";
        calls.push({ url: u, method, body: init?.body });
        if (u.includes("/v1/leases/my-leases")) {
            return { ok: true, status: 200, json: async () => [{ id: "l1", status: "ACTIVE", propertyId: "p1", propertyName: "Tower", unitId: "u1", unitIdentifier: "101" }] } as unknown as Response;
        }
        if (u.startsWith("/api/upload")) {
            const r = uploadReplies.shift() ?? { ok: true, status: 200 };
            return { ok: r.ok, status: r.status, json: async () => (r.ok ? { id: "a1" } : { error: true, message: "nope" }) } as unknown as Response;
        }
        if (u.endsWith("/v1/tickets") && method === "POST") {
            return { ok: true, status: 200, json: async () => ({ id: "t-1", title: "Leak" }) } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
}

const jpg = (name: string, size = 16) => new File([new Uint8Array(size)], name, { type: "image/jpeg" });

async function openFormAndTitle() {
    render(<TicketsPage />);
    // The single-lease renter's property is auto-filled once their leases load.
    await waitFor(() => expect(calls.some(c => c.url.includes("my-leases"))).toBe(true));
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });
    fireEvent.click(await screen.findByRole("button", { name: /create ticket/i }));
    fireEvent.change(screen.getByPlaceholderText(/brief summary/i), { target: { value: "Leak" } });
    return document.querySelector('input[type="file"]') as HTMLInputElement;
}

const submit = () => {
    const buttons = screen.getAllByRole("button", { name: /create ticket/i });
    fireEvent.click(buttons[buttons.length - 1]);
};

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("Create Ticket — attachments (break R2 portal2 F1)", () => {
    beforeEach(() => {
        sessionState = { role: "RENTER" };
        uploadReplies = [];
        installFetch();
    });

    it("lists a picked file and uploads it to the ticket's attachments path", async () => {
        const input = await openFormAndTitle();
        selectFiles(input, [jpg("tap.jpg")]);
        expect(await screen.findByText("tap.jpg")).toBeTruthy();

        submit();
        await waitFor(() => expect(calls.some(c => c.url.startsWith("/api/upload"))).toBe(true));
        const upload = calls.find(c => c.url.startsWith("/api/upload"))!;
        expect(upload.url).toBe("/api/upload?path=/api/v1/tickets/t-1/attachments");
        expect(upload.method).toBe("POST");
        expect((upload.body as FormData).get("file")).toBeInstanceOf(File);
        expect(((upload.body as FormData).get("file") as File).name).toBe("tap.jpg");
        // The modal closes once everything uploaded.
        await waitFor(() => expect(screen.queryByPlaceholderText(/brief summary/i)).toBeNull());
    });

    it("keeps files picked across two selections", async () => {
        const input = await openFormAndTitle();
        selectFiles(input, [jpg("one.jpg")]);
        selectFiles(input, [jpg("two.jpg")]);
        expect(await screen.findByText("one.jpg")).toBeTruthy();
        expect(screen.getByText("two.jpg")).toBeTruthy();
    });

    it("tells the user which files failed to upload, keeping the created ticket", async () => {
        uploadReplies = [{ ok: true, status: 200 }, { ok: false, status: 400 }];
        const input = await openFormAndTitle();
        selectFiles(input, [jpg("good.jpg"), jpg("bad.jpg")]);
        submit();

        const alert = await screen.findByRole("alert");
        expect(alert.textContent).toContain("bad.jpg");
        expect(alert.textContent).not.toContain("good.jpg");
        // The ticket exists: the notice links to it, and the create form is not
        // left open for a second (duplicate) submit.
        expect(within(alert).getByRole("link").getAttribute("href")).toBe("/dashboard/tickets/t-1");
        expect(screen.queryByPlaceholderText(/brief summary/i)).toBeNull();
        expect(calls.filter(c => c.url.endsWith("/v1/tickets") && c.method === "POST")).toHaveLength(1);
    });

    it("reports a network error on an upload as a failed file", async () => {
        const input = await openFormAndTitle();
        selectFiles(input, [jpg("net.jpg")]);
        const base = global.fetch;
        global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
            if (String(url).startsWith("/api/upload")) throw new TypeError("Failed to fetch");
            return (base as typeof fetch)(url as string, init);
        }) as unknown as typeof fetch;
        submit();
        const alert = await screen.findByRole("alert");
        expect(alert.textContent).toContain("net.jpg");
    });

    it("refuses an empty, oversized or wrong-type file with a readable reason", async () => {
        const input = await openFormAndTitle();
        selectFiles(input, [
            jpg("empty.jpg", 0),
            jpg("huge.jpg", 10 * 1024 * 1024 + 1),
            new File(["MZ"], "payload.exe", { type: "application/x-msdownload" }),
            jpg("fine.jpg"),
        ]);
        expect(await screen.findByText("fine.jpg")).toBeTruthy();
        const errors = screen.getByTestId("ticket-attachment-errors").textContent ?? "";
        expect(errors).toContain("empty.jpg");
        expect(errors).toMatch(/empty/i);
        expect(errors).toContain("huge.jpg");
        expect(errors).toMatch(/10 MB/);
        expect(errors).toContain("payload.exe");
        expect(errors).toMatch(/photo, video or PDF/i);

        submit();
        await waitFor(() => expect(calls.filter(c => c.url.startsWith("/api/upload"))).toHaveLength(1));
        expect(((calls.find(c => c.url.startsWith("/api/upload"))!.body as FormData).get("file") as File).name).toBe("fine.jpg");
    });

    it("accepts a PDF picked by extension even when the browser gives no MIME type", async () => {
        const input = await openFormAndTitle();
        selectFiles(input, [new File(["%PDF"], "lease.PDF", { type: "" })]);
        expect(await screen.findByText("lease.PDF")).toBeTruthy();
        expect(screen.queryByTestId("ticket-attachment-errors")).toBeNull();
    });
});

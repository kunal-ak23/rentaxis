import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import { ContractDocuments } from "../ContractDocuments";

/**
 * Executed copy at posting (Kunal, 2026-09-28): the lease's documents list the
 * signed contract and the executed copy; an admin can issue a missing copy.
 */
let docs: Array<{ id: string; type: string; createdAt: string }>;
let posts: string[];
let issueStatus: number;

beforeEach(() => {
    docs = [{ id: "d1", type: "CONTRACT", createdAt: "2026-04-01T08:00:00Z" }];
    posts = [];
    issueStatus = 200;
    global.fetch = vi.fn(async (url: unknown, init?: RequestInit) => {
        const u = String(url);
        if (init?.method === "POST") {
            posts.push(u);
            if (issueStatus === 200) docs = [...docs, { id: "d2", type: "EXECUTED_COPY", createdAt: "2026-04-02T08:00:00Z" }];
            return new Response("{}", { status: issueStatus });
        }
        return new Response(JSON.stringify(docs), { status: 200 });
    }) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

const renderIt = (canIssue: boolean, locale: "en" | "ar" = "en") => render(
    <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
        <ContractDocuments leaseId="lease-1" canIssue={canIssue} />
    </NextIntlClientProvider>);

describe("contract documents", () => {
    it("lists the executed copy first, then the signed contract", async () => {
        docs = [...docs, { id: "d2", type: "EXECUTED_COPY", createdAt: "2026-04-02T08:00:00Z" },
            { id: "d3", type: "ADDENDUM", createdAt: "2026-05-01T08:00:00Z" }];
        renderIt(false);
        await screen.findByTestId("contract-documents");
        const rows = screen.getAllByRole("listitem");
        expect(rows).toHaveLength(2);
        expect(rows[0]).toHaveTextContent(en.ContractDocuments.executedCopy);
        expect(rows[1]).toHaveTextContent(en.ContractDocuments.signedContract);
        expect(screen.queryByTestId("issue-executed-copy")).toBeNull();
    });

    it("an admin issues a missing executed copy and sees it listed", async () => {
        renderIt(true);
        fireEvent.click(await screen.findByTestId("issue-executed-copy"));
        await waitFor(() => expect(screen.getByTestId("contract-doc-EXECUTED_COPY")).toBeInTheDocument());
        expect(posts).toEqual(["/api/proxy/v1/leases/lease-1/executed-copy"]);
        expect(screen.queryByTestId("issue-executed-copy")).toBeNull();
    });

    it("says why when no copy is due (Arabic)", async () => {
        issueStatus = 409;
        renderIt(true, "ar");
        fireEvent.click(await screen.findByTestId("issue-executed-copy"));
        expect(await screen.findByRole("alert")).toHaveTextContent(ar.ContractDocuments.notDue);
        expect(screen.getByText(ar.ContractDocuments.signedContract)).toBeInTheDocument();
    });

    it("shows nothing before a contract exists", async () => {
        docs = [];
        const { container } = renderIt(true);
        await waitFor(() => expect(global.fetch).toHaveBeenCalled());
        expect(container).toBeEmptyDOMElement();
    });
});

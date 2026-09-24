import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-intl", async () => {
    // The real English catalog, one stable translator per namespace.
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
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }),
}));
// Heavy children irrelevant to the behavior under test.
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));
vi.mock("@/components/ui/confirm-dialog", () => ({ ConfirmDialog: () => null }));

import PropertiesPage from "../page";
import en from "../../../../../../messages/en.json";

const SAMPLE_STATS = [
    {
        property: {
            id: "prop-1",
            nameEn: "Marina Tower",
            nameAr: "برج المارينا",
            emirate: "DUBAI",
            address: "Dubai Marina",
            makaniNumber: "12345-67890",
            type: "RESIDENTIAL",
            fixedExpenses: 0,
        },
        propertyCount: 4,
        revenueAtCapacity: 100000,
        actualRevenue: 80000,
        vacancies: 1,
        assignedManagers: [],
    },
];

/** Minimal slice of Response the page (and lib/api/facilities helpers) reads. */
type StubResponse = {
    ok: boolean;
    status?: number;
    json?: () => Promise<unknown>;
    text?: () => Promise<string>;
};

let postImportResponse: StubResponse;
let postPropertiesResponse: StubResponse;
let portfolioUploadResponse: StubResponse;
let portfolioStatusResponse: StubResponse;
let portfolioTemplateResponse: StubResponse;

function jsonStub(ok: boolean, body: unknown, status = ok ? 200 : 400): StubResponse {
    return {
        ok,
        status,
        json: async () => body,
        text: async () => JSON.stringify(body),
    };
}

beforeEach(() => {
    postImportResponse = jsonStub(true, {});
    postPropertiesResponse = jsonStub(true, {});
    portfolioUploadResponse = jsonStub(true, { jobId: "job-1" });
    portfolioStatusResponse = jsonStub(true, { status: "PROCESSING" });
    portfolioTemplateResponse = jsonStub(true, {});
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.includes("/v1/import/portfolio/template")) {
            return portfolioTemplateResponse as unknown as Response;
        }
        if (url.includes("/v1/import/portfolio") && url.includes("/status")) {
            return portfolioStatusResponse as unknown as Response;
        }
        if (url.includes("/v1/import/portfolio")) {
            return portfolioUploadResponse as unknown as Response;
        }
        if (url.includes("/v1/properties/import")) {
            return postImportResponse as unknown as Response;
        }
        if (url.includes("/v1/properties") && init?.method === "POST") {
            return postPropertiesResponse as unknown as Response;
        }
        if (url.includes("/v1/properties")) {
            return jsonStub(true, SAMPLE_STATS) as unknown as Response;
        }
        return jsonStub(true, null) as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
});

async function openImportModalAndSubmit() {
    render(<PropertiesPage />);
    fireEvent.click(await screen.findByRole("button", { name: /Import Property/ }));
    const fileInput = document.querySelector('input[type="file"][accept=".csv"]')!;
    fireEvent.change(fileInput, {
        target: { files: [new File(["BuildingName,UnitNumber\nA,101\n"], "units.csv", { type: "text/csv" })] },
    });
    fireEvent.change(screen.getByPlaceholderText("Project Name (EN)"), {
        target: { value: "Marina Tower" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Import" }));
}

describe("PropertiesPage import result", () => {
    it("shows the success banner for a 200 whose DTO carries errors: []", async () => {
        // BulkPropertyImportResultDTO always serializes an (empty) errors array on
        // success; an empty array must not be treated as a failure.
        postImportResponse = jsonStub(true, {
            propertyId: "prop-1",
            propertyName: "Marina Tower",
            buildingsCreated: 2,
            unitsCreated: 5,
            errors: [],
        });

        await openImportModalAndSubmit();

        expect(await screen.findByText("Import successful!")).toBeTruthy();
        expect(screen.getByText(/Buildings created: 2/)).toBeTruthy();
        expect(screen.getByText(/Units created: 5/)).toBeTruthy();
        expect(screen.queryByText("Import failed:")).toBeNull();
    });

    it("lists row errors for a 400 whose DTO carries a non-empty errors array", async () => {
        postImportResponse = jsonStub(false, {
            buildingsCreated: 0,
            unitsCreated: 0,
            errors: ["Row 2: Invalid unit type 'VILLA'"],
        });

        await openImportModalAndSubmit();

        expect(await screen.findByText("Import failed:")).toBeTruthy();
        expect(screen.getByText("Row 2: Invalid unit type 'VILLA'")).toBeTruthy();
    });

    it("renders the message string, not the boolean error flag, for a global-handler body", async () => {
        // GlobalExceptionHandler failures look like {error: true, message: "..."};
        // the boolean must never be rendered in place of the message.
        postImportResponse = jsonStub(false, { error: true, message: "Invalid emirate: MARS" });

        await openImportModalAndSubmit();

        expect(await screen.findByText("Invalid emirate: MARS")).toBeTruthy();
        expect(screen.queryByText("Import successful!")).toBeNull();
    });
});

describe("PropertiesPage enum options", () => {
    it("offers only valid PropertyType values in the Add Project form", async () => {
        render(<PropertiesPage />);
        fireEvent.click(await screen.findByRole("button", { name: en.MasterData.addProject }));

        // Labelled options, valued with the backend's enum names.
        expect((screen.getByRole("option", { name: "Residential" }) as HTMLOptionElement).value).toBe("RESIDENTIAL");
        expect((screen.getByRole("option", { name: "Commercial" }) as HTMLOptionElement).value).toBe("COMMERCIAL");
        expect((screen.getByRole("option", { name: "Mixed use" }) as HTMLOptionElement).value).toBe("MIXED");
        expect(screen.queryByRole("option", { name: "Industrial" })).toBeNull();
    });

    it("offers only valid UnitType values in the Add Property form", async () => {
        render(<PropertiesPage />);
        fireEvent.click(await screen.findByRole("button", { name: en.MasterData.addProperty }));

        for (const valid of ["STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE", "RETAIL", "OFFICE"] as const) {
            const option = screen.getByRole("option", { name: en.MasterData[`unitType${valid}`] }) as HTMLOptionElement;
            expect(option.value).toBe(valid);
        }
        for (const invalid of ["BHK 4", "SHOP", "WAREHOUSE"]) {
            expect(screen.queryByRole("option", { name: invalid })).toBeNull();
        }
    });
});

describe("PropertiesPage portfolio import", () => {
    const statusCallCount = () =>
        (global.fetch as ReturnType<typeof vi.fn>).mock.calls
            .filter((c) => String(c[0]).includes("/status")).length;

    async function openPortfolioModalAndUpload() {
        render(<PropertiesPage />);
        fireEvent.click(await screen.findByRole("button", { name: /Import Portfolio/ }));
        const fileInput = document.querySelector('input[type="file"][accept=".xlsx"]')!;
        fireEvent.change(fileInput, {
            target: { files: [new File(["stub"], "portfolio.xlsx")] },
        });
        vi.useFakeTimers();
        fireEvent.click(screen.getByRole("button", { name: "Upload & Import" }));
        // Let the upload POST resolve and the poll interval start.
        await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    }

    it("stops polling and shows a failure when the status endpoint returns 404", async () => {
        portfolioStatusResponse = jsonStub(false, {}, 404);

        await openPortfolioModalAndUpload();
        // First poll tick returns 404 — must terminate with a visible failure.
        await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

        expect(screen.getByText("Import job not found. Please upload the file again.")).toBeTruthy();
        expect(statusCallCount()).toBe(1);

        // Polling must not continue after the terminal error.
        await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
        expect(statusCallCount()).toBe(1);
    });

    it("stops polling and shows a failure when the session expires (401)", async () => {
        portfolioStatusResponse = jsonStub(false, {}, 401);

        await openPortfolioModalAndUpload();
        await act(async () => { await vi.advanceTimersByTimeAsync(2000); });

        expect(screen.getByText("Your session has expired. Please sign in again and retry the import.")).toBeTruthy();
        await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
        expect(statusCallCount()).toBe(1);
    });

    it("stops polling when the modal is closed mid-processing", async () => {
        portfolioStatusResponse = jsonStub(true, { status: "PROCESSING" });

        await openPortfolioModalAndUpload();
        await act(async () => { await vi.advanceTimersByTimeAsync(2000); });
        expect(statusCallCount()).toBe(1);

        fireEvent.click(screen.getByRole("button", { name: "Close" }));
        await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
        expect(statusCallCount()).toBe(1);
    });

    it("shows an error instead of saving a corrupt file when template download fails", async () => {
        portfolioTemplateResponse = jsonStub(false, {}, 500);

        render(<PropertiesPage />);
        fireEvent.click(await screen.findByRole("button", { name: /Import Portfolio/ }));
        const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, "click");
        fireEvent.click(screen.getByRole("button", { name: /Download Template/ }));

        expect(await screen.findByText("Failed to download template. Please try again.")).toBeTruthy();
        expect(clickSpy).not.toHaveBeenCalled();
    });
});

describe("PropertiesPage create project errors", () => {
    it("surfaces the backend message when project creation fails", async () => {
        postPropertiesResponse = jsonStub(false, {
            error: true,
            message: "A property named 'Marina Tower' already exists",
        });

        render(<PropertiesPage />);
        fireEvent.click(await screen.findByRole("button", { name: en.MasterData.addProject }));
        fireEvent.change(screen.getByPlaceholderText("Project Name (EN)"), {
            target: { value: "Marina Tower" },
        });
        fireEvent.click(screen.getByRole("button", { name: en.MasterData.create }));

        expect(await screen.findByText("A property named 'Marina Tower' already exists")).toBeTruthy();
    });
});

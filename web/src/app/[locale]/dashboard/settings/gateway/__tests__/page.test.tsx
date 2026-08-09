import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => "en",
}));

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: null }),
}));

import GatewayConfigPage from "../page";

/** Shaped like the backend PaymentGatewayDTO — supportedCurrencies is a plain String. */
const sampleGateways = [
    {
        id: "gw-1",
        code: "RAZORPAY",
        name: "Razorpay",
        description: "Cards, UPI, wallets",
        isActive: true,
        sdkJsUrl: "https://checkout.razorpay.com/v1/checkout.js",
        supportedCurrencies: "AED,INR,USD",
    },
];

/** What GET /v1/gateway-config answers with. */
let configResponse: { status: number; body?: unknown } = { status: 204 };

function stubFetch() {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/v1/gateway-config/gateways")) {
            return { ok: true, status: 200, json: async () => sampleGateways } as unknown as Response;
        }
        if (url.includes("/v1/gateway-config")) {
            const { status, body } = configResponse;
            return {
                ok: status >= 200 && status < 300,
                status,
                json: async () => {
                    // A real 204 has an empty body — res.json() rejects.
                    if (body === undefined) throw new SyntaxError("Unexpected end of JSON input");
                    return body;
                },
            } as unknown as Response;
        }
        return { ok: true, status: 200, json: async () => [] } as unknown as Response;
    }) as unknown as typeof fetch;
}

beforeEach(() => {
    configResponse = { status: 204 };
    stubFetch();
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("GatewayConfigPage fetchExistingConfig", () => {
    it("treats 204 No Content as 'not configured yet' without an error banner", async () => {
        render(<GatewayConfigPage />);

        expect(await screen.findByText("Razorpay")).toBeTruthy();
        expect(screen.queryByText("Failed to load gateway configuration.")).toBeNull();
        expect(screen.queryByText(/Currently configured/)).toBeNull();
    });

    it("hydrates the page from a 200 config response", async () => {
        configResponse = {
            status: 200,
            body: {
                id: "cfg-1",
                gatewayId: "gw-1",
                gatewayCode: "RAZORPAY",
                gatewayName: "Razorpay",
                isActive: true,
                isTestMode: true,
                apiKeyMasked: "pk_test_****",
                hasWebhookSecret: false,
            },
        };
        render(<GatewayConfigPage />);

        expect(await screen.findByText(/Currently configured/)).toBeTruthy();
        expect(screen.queryByText("Failed to load gateway configuration.")).toBeNull();
    });

    it("surfaces a transport/server failure instead of silently looking unconfigured", async () => {
        configResponse = { status: 500, body: { message: "boom" } };
        render(<GatewayConfigPage />);

        expect(await screen.findByText("Failed to load gateway configuration.")).toBeTruthy();
    });
});

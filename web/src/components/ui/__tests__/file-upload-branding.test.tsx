import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";

import { FileUpload } from "../FileUpload";

/**
 * Organisation branding (review I1/I3): the org dialog uploads to its own backend
 * path, and a saved image in a private container is previewed through the app.
 */
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function Harness({ initial, previewSrc, uploadPath }: { initial: string; previewSrc?: string; uploadPath?: string }) {
    const [value, setValue] = useState(initial);
    return <FileUpload value={value} onChange={setValue} onRemove={() => setValue("")}
        label="Upload" hint="hint" uploadPath={uploadPath} previewSrc={previewSrc} />;
}

describe("FileUpload for branding", () => {
    it("posts to the given backend path, encoded for /api/upload", async () => {
        const fetchMock = vi.fn(async () => new Response(JSON.stringify({ url: "https://acct/tenant-b/assets/branding/x.png" }), { status: 200 }));
        global.fetch = fetchMock as unknown as typeof fetch;
        URL.createObjectURL = vi.fn(() => "blob:local-copy");
        const { container } = render(<Harness initial="" uploadPath="/api/admin/tenants/org-b/branding" />);
        const input = container.querySelector("input[type=file]") as HTMLInputElement;
        fireEvent.change(input, { target: { files: [new File([new Uint8Array([1])], "s.png", { type: "image/png" })] } });
        await waitFor(() => expect(fetchMock).toHaveBeenCalled());
        const calls = fetchMock.mock.calls as unknown as [string][];
        expect(calls[0][0]).toBe("/api/upload?path=%2Fapi%2Fadmin%2Ftenants%2Forg-b%2Fbranding");
        // The fresh upload is previewed from the browser's own copy, not the private URL.
        expect(await screen.findByRole("img")).toHaveAttribute("src", "blob:local-copy");
    });

    it("previews a saved value through the given app route", () => {
        render(<Harness initial="https://acct/tenant-b/assets/branding/x.png" previewSrc="/api/proxy/admin/tenants/b/branding/stamp?v=1" />);
        expect(screen.getByRole("img")).toHaveAttribute("src", "/api/proxy/admin/tenants/b/branding/stamp?v=1");
    });

    it("defaults to the public assets upload and the value itself", () => {
        render(<Harness initial="/api/v1/assets/serve/assets/a.png" />);
        expect(screen.getByRole("img")).toHaveAttribute("src", "/api/v1/assets/serve/assets/a.png");
    });
});

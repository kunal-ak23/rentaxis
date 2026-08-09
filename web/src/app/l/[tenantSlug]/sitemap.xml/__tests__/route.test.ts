import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";

import { GET } from "../route";

const BACKEND_XML = [
  '<?xml version="1.0" encoding="UTF-8"?>',
  '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
  "  <url>",
  "    <loc>/l/acme/unit-101</loc>",
  "    <lastmod>2026-08-01</lastmod>",
  "    <changefreq>weekly</changefreq>",
  "  </url>",
  "  <url>",
  "    <loc>/l/acme/unit-202</loc>",
  "    <lastmod>2026-08-02</lastmod>",
  "    <changefreq>weekly</changefreq>",
  "  </url>",
  "</urlset>",
].join("\n");

const fetchMock = vi.fn();

function makeRequest(headers: Record<string, string> = {}) {
  return new Request("http://localhost:3000/l/acme/sitemap.xml", { headers });
}

const params = Promise.resolve({ tenantSlug: "acme" });

beforeEach(() => {
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(new Response(BACKEND_XML, { status: 200 }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("GET /l/[tenantSlug]/sitemap.xml", () => {
  it("rewrites relative <loc> paths to absolute URLs using X-Forwarded-* headers", async () => {
    const res = await GET(
      makeRequest({ "x-forwarded-proto": "https", "x-forwarded-host": "miftah.example.com" }),
      { params }
    );

    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("application/xml");
    const xml = await res.text();
    expect(xml).toContain("<loc>https://miftah.example.com/l/acme/unit-101</loc>");
    expect(xml).toContain("<loc>https://miftah.example.com/l/acme/unit-202</loc>");
    expect(xml).not.toContain("<loc>/");
  });

  it("falls back to the Host header and request protocol without X-Forwarded-*", async () => {
    const res = await GET(makeRequest({ host: "localhost:3000" }), { params });

    const xml = await res.text();
    expect(xml).toContain("<loc>http://localhost:3000/l/acme/unit-101</loc>");
  });

  it("uses only the first entry of a comma-separated x-forwarded-proto", async () => {
    const res = await GET(
      makeRequest({ "x-forwarded-proto": "https, http", "x-forwarded-host": "miftah.example.com" }),
      { params }
    );

    const xml = await res.text();
    expect(xml).toContain("<loc>https://miftah.example.com/l/acme/unit-101</loc>");
  });

  it("uses only the first entry of a comma-separated x-forwarded-host", async () => {
    const res = await GET(
      makeRequest({
        "x-forwarded-proto": "https",
        "x-forwarded-host": "miftah.example.com, internal-proxy:3000",
      }),
      { params }
    );

    const xml = await res.text();
    expect(xml).toContain("<loc>https://miftah.example.com/l/acme/unit-101</loc>");
  });

  it("leaves already-absolute <loc> values untouched", async () => {
    fetchMock.mockResolvedValue(
      new Response(
        "<urlset><url><loc>https://other.example.com/l/acme/unit-303</loc></url></urlset>",
        { status: 200 }
      )
    );

    const res = await GET(
      makeRequest({ "x-forwarded-proto": "https", "x-forwarded-host": "miftah.example.com" }),
      { params }
    );

    const xml = await res.text();
    expect(xml).toContain("<loc>https://other.example.com/l/acme/unit-303</loc>");
  });

  it("returns 404 when the backend responds with an error", async () => {
    fetchMock.mockResolvedValue(new Response("nope", { status: 404 }));

    const res = await GET(makeRequest(), { params });

    expect(res.status).toBe(404);
  });
});

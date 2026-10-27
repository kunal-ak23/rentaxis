import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";
import {
    ApiError, createPromoterAd, createPromoterBusiness, deletePromoterAd, deletePromoterBusiness, fetchPromoterAds,
    fetchPromoterBusinesses, fetchPromoterMe, fetchPromoterProperties, updatePromoterAd,
    updatePromoterBusiness, uploadPromoterImage,
} from "../promoter";
import {
    approveAd, deleteAd, deleteBusiness, fetchAds, fetchBusinesses, fetchPendingAdCount, fetchPromoters,
    invitePromoter, isAdChangedError, rejectAd, restoreAd, restoreBusiness, updatePromoterAccess,
} from "../promotions";

/**
 * The contract with the backend's PromoterController (/api/v1/promoter/**) and
 * the admin additions on /api/v1/promotions. A PROMOTER is refused everything
 * else, so a client function drifting to an admin path would 403 in production
 * while every mocked page test stayed green.
 */
const P = "/api/proxy/v1/promoter";
const A = "/api/proxy/v1/promotions";

let api: ReturnType<typeof fetchRouter>;
beforeEach(() => { api = fetchRouter(); });
afterEach(() => { vi.restoreAllMocks(); });

const last = () => api.calls[api.calls.length - 1];

describe("promoter client", () => {
    it("reads who the promoter is, their properties and their businesses", async () => {
        api.on("GET", `${P}/me`, { body: { canCreateBusinesses: true, organisationName: "Org" } });
        api.on("GET", `${P}/properties`, { body: [{ id: "p-1", nameEn: "Palm Tower", nameAr: null }] });
        api.on("GET", `${P}/businesses`, { body: [{ id: "b-1" }] });

        expect(await fetchPromoterMe()).toEqual({ canCreateBusinesses: true, organisationName: "Org" });
        expect(await fetchPromoterProperties()).toEqual([{ id: "p-1", nameEn: "Palm Tower", nameAr: null }]);
        expect(await fetchPromoterBusinesses()).toEqual([{ id: "b-1" }]);
    });

    it("accepts the business list as a bare array or as a Spring page", async () => {
        api.on("GET", `${P}/businesses`, { body: { content: [{ id: "b-2" }], totalElements: 1, totalPages: 1, number: 0, size: 10 } });
        expect(await fetchPromoterBusinesses()).toEqual([{ id: "b-2" }]);
    });

    it("pages the ads and narrows them by business", async () => {
        api.on("GET", `${P}/ads`, { body: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 } });
        await fetchPromoterAds(2, 10, "b-1");
        expect(last().url).toBe(`${P}/ads?page=2&size=10&businessId=b-1`);
        await fetchPromoterAds();
        expect(last().url).toBe(`${P}/ads?page=0&size=10`);
    });

    it("creates, edits and deletes ads and businesses under /promoter", async () => {
        api.on("POST", `${P}/ads`, { status: 201, body: { id: "ad-1" } });
        api.on("PUT", `${P}/ads/ad-1`, { body: { id: "ad-1" } });
        api.on("DELETE", `${P}/ads/ad-1`, { status: 200 });
        api.on("POST", `${P}/businesses`, { status: 201, body: { id: "b-1" } });
        api.on("PUT", `${P}/businesses/b-1`, { body: { id: "b-1" } });

        await createPromoterAd({ businessId: "b-1", titleEn: "x", propertyIds: ["p-1"] });
        expect(last()).toMatchObject({ method: "POST", url: `${P}/ads`, body: { businessId: "b-1", propertyIds: ["p-1"] } });
        await updatePromoterAd("ad-1", { businessId: "b-1", propertyIds: ["p-1"] });
        expect(last()).toMatchObject({ method: "PUT", url: `${P}/ads/ad-1` });
        await deletePromoterAd("ad-1");
        expect(last()).toMatchObject({ method: "DELETE", url: `${P}/ads/ad-1` });
        await createPromoterBusiness({ nameEn: "Sparkle" });
        expect(last()).toMatchObject({ method: "POST", url: `${P}/businesses`, body: { nameEn: "Sparkle" } });
        await updatePromoterBusiness("b-1", { nameEn: "Sparkle 2" });
        expect(last()).toMatchObject({ method: "PUT", url: `${P}/businesses/b-1` });

        expect(api.calls.every(c => c.url.startsWith(P))).toBe(true);
    });

    it("uploads images to the promoter route and returns the stored route", async () => {
        api.on("POST", `${P}/images`, { status: 201, body: { url: "/api/v1/public/promo-images/x.png" } });
        const url = await uploadPromoterImage(new File([new Uint8Array(4)], "logo.png", { type: "image/png" }));
        expect(url).toBe("/api/v1/public/promo-images/x.png");
        expect(last()).toMatchObject({ method: "POST", url: `${P}/images` });
    });

    it("rejects an upload that answers without a URL", async () => {
        api.on("POST", `${P}/images`, { status: 201, body: {} });
        await expect(uploadPromoterImage(new File([new Uint8Array(4)], "a.png"))).rejects.toMatchObject({ status: 502 });
    });

    it("throws ApiError carrying the server's message", async () => {
        api.on("POST", `${P}/ads`, { status: 400, body: { message: "Property is outside your access" } });
        const err = await createPromoterAd({ businessId: "b-1" }).catch(e => e);
        expect(err).toBeInstanceOf(ApiError);
        expect(err).toMatchObject({ status: 400, message: "Property is outside your access" });
    });
});

describe("admin additions on /promotions", () => {
    it("filters ads by approval status", async () => {
        api.on("GET", `${A}/ads`, { body: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 } });
        await fetchAds(0, 10, undefined, "PENDING");
        expect(last().url).toBe(`${A}/ads?page=0&size=10&approvalStatus=PENDING`);
        await fetchAds(1, 10, "b-1");
        expect(last().url).toBe(`${A}/ads?page=1&size=10&businessId=b-1`);
    });

    it("reads the pending count", async () => {
        api.on("GET", `${A}/ads/pending-count`, { body: { count: 4 } });
        expect(await fetchPendingAdCount()).toBe(4);
    });

    it("approves with a bare POST and rejects with the reason", async () => {
        api.on("POST", `${A}/ads/ad-1/approve`, { body: {} });
        api.on("POST", `${A}/ads/ad-1/reject`, { body: {} });
        await approveAd("ad-1", "2026-10-05T10:11:12.123456Z");
        expect(last()).toMatchObject({ method: "POST", url: `${A}/ads/ad-1/approve`, body: { expectedSubmittedAt: "2026-10-05T10:11:12.123456Z" } });
        await rejectAd("ad-1", "Blurry", "2026-10-05T10:11:12.123456Z");
        expect(last()).toMatchObject({
            method: "POST", url: `${A}/ads/ad-1/reject`,
            body: { reason: "Blurry", expectedSubmittedAt: "2026-10-05T10:11:12.123456Z" },
        });
    });

    it("sends the submittedAt string untouched (not re-parsed or reformatted), null included", async () => {
        api.on("POST", `${A}/ads/ad-1/approve`, { body: {} });
        // Microsecond precision and an offset: a Date round trip would change both.
        await approveAd("ad-1", "2026-10-05T14:11:12.123456+04:00");
        expect((last().body as { expectedSubmittedAt: string }).expectedSubmittedAt).toBe("2026-10-05T14:11:12.123456+04:00");
        await approveAd("ad-1", null);
        expect(last().body).toEqual({ expectedSubmittedAt: null });
    });

    it("recognises the 409 promo.changed of a review, and only that", async () => {
        api.on("POST", `${A}/ads/ad-1/approve`, {
            status: 409,
            body: { code: "promo.changed", message: "This ad was changed after you opened it. Reload to review the latest version." },
        });
        const changed = await approveAd("ad-1", "x").catch(e => e);
        expect(isAdChangedError(changed)).toBe(true);
        expect(changed.message).toBe("This ad was changed after you opened it. Reload to review the latest version.");

        api.on("POST", `${A}/ads/ad-2/approve`, { status: 409, body: { code: "something.else", message: "Conflict" } });
        api.on("POST", `${A}/ads/ad-3/approve`, { status: 400, body: { code: "promo.changed", message: "x" } });
        api.on("POST", `${A}/ads/ad-4/approve`, { status: 409, body: { message: "no code" } });
        for (const id of ["ad-2", "ad-3", "ad-4"]) {
            expect(isAdChangedError(await approveAd(id, "x").catch(e => e)), id).toBe(false);
        }
        expect(isAdChangedError(new Error("network"))).toBe(false);
    });

    it("lists, invites and updates promoters", async () => {
        api.on("GET", `${A}/promoters`, { body: [] });
        api.on("POST", `${A}/promoters`, { status: 201, body: {} });
        api.on("PUT", `${A}/promoters/u-1`, { body: {} });
        expect(await fetchPromoters()).toEqual([]);
        const invite = { name: "N", email: "promoter@example.test", phoneNumber: null, canCreateBusinesses: false, businessIds: [], propertyIds: ["p-1"] };
        await invitePromoter(invite);
        expect(last()).toMatchObject({ method: "POST", url: `${A}/promoters`, body: invite });
        await updatePromoterAccess("u-1", { canCreateBusinesses: true, businessIds: ["b-1"], propertyIds: [] });
        expect(last()).toMatchObject({ method: "PUT", url: `${A}/promoters/u-1`, body: { canCreateBusinesses: true, businessIds: ["b-1"], propertyIds: [] } });
    });
});

describe("delete outcome and archive (Addendum B)", () => {
    it("reads DELETED or ARCHIVED from every delete", async () => {
        api.on("DELETE", `${A}/ads/a1`, { body: { outcome: "ARCHIVED" } });
        api.on("DELETE", `${A}/ads/a2`, { body: { outcome: "DELETED" } });
        api.on("DELETE", `${A}/businesses/b1`, { body: { outcome: "ARCHIVED" } });
        api.on("DELETE", `${P}/ads/a1`, { body: { outcome: "ARCHIVED" } });
        api.on("DELETE", `${P}/businesses/b1`, { body: { outcome: "DELETED" } });

        expect(await deleteAd("a1")).toBe("ARCHIVED");
        expect(await deleteAd("a2")).toBe("DELETED");
        expect(await deleteBusiness("b1")).toBe("ARCHIVED");
        expect(await deletePromoterAd("a1")).toBe("ARCHIVED");
        expect(await deletePromoterBusiness("b1")).toBe("DELETED");
        expect(api.calls.map(c => `${c.method} ${c.url}`)).toEqual([
            `DELETE ${A}/ads/a1`, `DELETE ${A}/ads/a2`, `DELETE ${A}/businesses/b1`,
            `DELETE ${P}/ads/a1`, `DELETE ${P}/businesses/b1`,
        ]);
    });

    it("reads a delete with no body, or an unknown outcome, as a hard delete", async () => {
        api.on("DELETE", `${A}/ads/a1`, { status: 200 });
        api.on("DELETE", `${A}/ads/a2`, { body: { outcome: "SOMETHING_ELSE" } });
        expect(await deleteAd("a1")).toBe("DELETED");
        expect(await deleteAd("a2")).toBe("DELETED");
    });

    it("throws the server's message when a delete is refused", async () => {
        api.on("DELETE", `${P}/businesses/b1`, { status: 403, body: { message: "You cannot create businesses" } });
        await expect(deletePromoterBusiness("b1")).rejects.toMatchObject({ status: 403, message: "You cannot create businesses" });
    });

    it("asks for archived ads and businesses only when told to", async () => {
        api.on("GET", `${A}/ads`, { body: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 } });
        api.on("GET", `${A}/businesses`, { body: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 } });
        await fetchAds(0, 10);
        expect(last().url).toBe(`${A}/ads?page=0&size=10`);
        await fetchAds(1, 10, "b-1", undefined, true);
        expect(last().url).toBe(`${A}/ads?page=1&size=10&businessId=b-1&archived=true`);
        await fetchBusinesses(0, 10);
        expect(last().url).toBe(`${A}/businesses?page=0&size=10`);
        await fetchBusinesses(2, 10, true);
        expect(last().url).toBe(`${A}/businesses?page=2&size=10&archived=true`);
    });

    it("restores with a bare POST and throws the refusal", async () => {
        api.on("POST", `${A}/ads/a1/restore`, { body: {} });
        api.on("POST", `${A}/businesses/b1/restore`, { status: 400, body: { message: "Name already taken" } });
        await restoreAd("a1");
        expect(last()).toMatchObject({ method: "POST", url: `${A}/ads/a1/restore` });
        await expect(restoreBusiness("b1")).rejects.toMatchObject({ status: 400, message: "Name already taken" });
    });
});


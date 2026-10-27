import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";
import {
    ApiError, createPromoterAd, createPromoterBusiness, deletePromoterAd, fetchPromoterAds,
    fetchPromoterBusinesses, fetchPromoterMe, fetchPromoterProperties, updatePromoterAd,
    updatePromoterBusiness, uploadPromoterImage,
} from "../promoter";
import {
    approveAd, fetchAds, fetchPendingAdCount, fetchPromoters, invitePromoter, rejectAd, updatePromoterAccess,
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
        await approveAd("ad-1");
        expect(last()).toMatchObject({ method: "POST", url: `${A}/ads/ad-1/approve` });
        await rejectAd("ad-1", "Blurry");
        expect(last()).toMatchObject({ method: "POST", url: `${A}/ads/ad-1/reject`, body: { reason: "Blurry" } });
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

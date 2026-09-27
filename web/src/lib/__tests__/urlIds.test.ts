import { afterEach, describe, expect, it } from "vitest";
import { idParam, isUuid, stripInvalidIdParams } from "../urlIds";

// Break round 1: `/dashboard/leases?propertyId=not-a-uuid` sent the garbage to
// the API (400, unhandled ApiError) while the filter silently failed. An id
// read from the URL is used only when it is UUID-shaped; otherwise it is no
// filter, and it is removed from the URL.

afterEach(() => window.history.replaceState(null, "", "/"));

describe("URL id params", () => {
    it("accepts only UUID-shaped values", () => {
        expect(isUuid("b394c93d-94d8-40e2-91b6-0740febaf250")).toBe(true);
        expect(isUuid("B394C93D-94D8-40E2-91B6-0740FEBAF250")).toBe(true);
        expect(isUuid("not-a-uuid")).toBe(false);
        expect(isUuid("b394c93d-94d8-40e2-91b6-0740f")).toBe(false);
        expect(isUuid("")).toBe(false);
        expect(isUuid(null)).toBe(false);
    });

    it("reads an invalid or missing id as no filter", () => {
        expect(idParam("not-a-uuid")).toBe("");
        expect(idParam(null)).toBe("");
        expect(idParam(" b394c93d-94d8-40e2-91b6-0740febaf250 ")).toBe("b394c93d-94d8-40e2-91b6-0740febaf250");
    });

    it("strips only the invalid id params from the URL, keeping everything else and the history state", () => {
        const state = { __NA: true };
        window.history.replaceState(state, "", "/en/dashboard/leases?propertyId=not-a-uuid&buildingId=b394c93d-94d8-40e2-91b6-0740febaf250&view=active");
        expect(stripInvalidIdParams(["propertyId", "buildingId"])).toBe(true);
        expect(window.location.pathname).toBe("/en/dashboard/leases");
        expect(new URLSearchParams(window.location.search).get("propertyId")).toBeNull();
        expect(new URLSearchParams(window.location.search).get("buildingId")).toBe("b394c93d-94d8-40e2-91b6-0740febaf250");
        expect(new URLSearchParams(window.location.search).get("view")).toBe("active");
        expect(window.history.state).toEqual(state);
    });

    it("leaves a clean URL untouched", () => {
        window.history.replaceState(null, "", "/x?propertyId=b394c93d-94d8-40e2-91b6-0740febaf250");
        expect(stripInvalidIdParams(["propertyId", "buildingId"])).toBe(false);
        expect(window.location.search).toBe("?propertyId=b394c93d-94d8-40e2-91b6-0740febaf250");
    });
});

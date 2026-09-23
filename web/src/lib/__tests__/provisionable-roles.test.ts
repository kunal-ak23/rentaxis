import { describe, expect, it } from "vitest";

import { assignableRoles, getRoleLabel, PROVISIONABLE_ROLES, type UserRole } from "../rbac";
import en from "../../../messages/en.json";
import ar from "../../../messages/ar.json";

/**
 * The staff-provisioning form used to hold a hand-written copy of the role list,
 * and ACCOUNTANT was missing from it. The role could be granted through
 * /api/admin/users but never from the UI, so a customer could not staff the
 * finance function that accounting v2 gates journals, opening balances, the
 * period lock and penalty approval on.
 *
 * These tests pin the list to ROLE_RANK so a role added to the product can never
 * again exist everywhere except the screen that creates users.
 */
describe("PROVISIONABLE_ROLES", () => {
    it("offers every role a SUPER_ADMIN may assign, except RENTER", () => {
        const expected = assignableRoles("SUPER_ADMIN").filter((r) => r !== "RENTER").sort();

        expect([...PROVISIONABLE_ROLES].sort()).toEqual(expected);
    });

    it("includes ACCOUNTANT", () => {
        expect(PROVISIONABLE_ROLES).toContain("ACCOUNTANT");
    });

    /**
     * A renter is created from the Renters screen, which also builds their portal
     * account. Offering RENTER here would make a login with no tenancy behind it.
     */
    it("excludes RENTER", () => {
        expect(PROVISIONABLE_ROLES).not.toContain("RENTER");
    });

    it("is ordered by privilege, most privileged first", () => {
        expect(PROVISIONABLE_ROLES.slice(0, 3)).toEqual([
            "SUPER_ADMIN",
            "TENANT_ADMIN",
            "ACCOUNTANT",
        ]);
    });

    /** A role with no label renders as a raw enum name in the dropdown. */
    it.each(PROVISIONABLE_ROLES)("has an English and Arabic label for %s", (role: UserRole) => {
        const roles: Record<string, string> = en.Roles;
        const rolesAr: Record<string, string> = ar.Roles;

        expect(roles[role]).toBeTruthy();
        expect(rolesAr[role]).toBeTruthy();
        expect(getRoleLabel(role)).not.toEqual(role);
    });

    /**
     * ACCOUNTANT outranks PROPERTY_MANAGER, so a manager must not be able to
     * create one — the guard that made the fix safe to ship.
     */
    it("does not let a PROPERTY_MANAGER assign ACCOUNTANT", () => {
        expect(assignableRoles("PROPERTY_MANAGER")).not.toContain("ACCOUNTANT");
        expect(assignableRoles("TENANT_ADMIN")).toContain("ACCOUNTANT");
    });
});

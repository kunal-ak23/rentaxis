/**
 * RBAC permission utilities for RentAxis
 * Central source of truth for role-based access control in the frontend
 */

export type UserRole = 'SUPER_ADMIN' | 'TENANT_ADMIN' | 'PROPERTY_MANAGER' | 'SECURITY_GUARD' | 'TENANT_USER' | 'RENTER' | 'ACCOUNTANT';

export const PERMISSIONS = {
    canManageTenants: ['SUPER_ADMIN'] as UserRole[],
    canManageUsers: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canCreateProperties: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canViewProperties: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    canCreateUnits: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    // Draft-lease create/update/delete. NOT widened to ACCOUNTANT for
    // accounting-v2: LeaseController's POST /leases, PUT /{id} and DELETE /{id}
    // are all @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')") — an
    // accountant posts and amends a lease that already exists (canPostLeases)
    // but does not draft one. Widen this only alongside those annotations.
    canManageLeases: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    // Reading contracts: the list, the detail page and the sidebar link.
    // Separate from canManageLeases because the two answer different questions —
    // an ACCOUNTANT posts, amends and extends a contract (canPostLeases,
    // canExtendLeases) but never drafts one, and until this key existed the
    // Leases link was gated on canViewProperties, which does not admit them:
    // the role that owns posting could not reach the screen it posts from.
    // Mirrors LeaseController's read methods (GET /leases, /paged, /{id}).
    canViewLeases: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
    canManageRenters: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    // The accounting-v2 ledger pages: chart of accounts, journals, general ledger,
    // tenant ledger, trial balance. ACCOUNTANT is admitted because every controller
    // behind those pages grants it.
    canAccessFinance: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // The operational pages that merely LIVE under /dashboard/finance plus Staff.
    // Their controllers do NOT grant ACCOUNTANT — VendorController,
    // BankAccountController and StaffController are all
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')") — so gating them on
    // canAccessFinance offered an accountant four links that 403 on arrival.
    //
    // PROPERTY_MANAGER is deliberately absent even though PaymentScheduleController
    // does grant it: the same key gates Vendors, Bank Accounts and Staff, which
    // refuse PM, and a property manager has never been shown a finance link
    // (walkthrough 13 pins `a[href*="/dashboard/finance/"]` at zero for PM).
    // Widening PM's sidebar is its own change, not this one.
    canAccessFinanceOps: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canResolveIssues: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    canCreateIssues: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'TENANT_USER'] as UserRole[],
    canViewOwnPayments: ['TENANT_USER'] as UserRole[],
    canSwitchTenants: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    canViewRenterPortal: ['RENTER'] as UserRole[],
    canAcceptLeases: ['RENTER'] as UserRole[],
    canDownloadContracts: ['SUPER_ADMIN', 'TENANT_ADMIN', 'RENTER'] as UserRole[],
    canManageMeetings: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    canCreateMeetings: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'RENTER'] as UserRole[],
    // Deliberately excludes SUPER_ADMIN, unlike its neighbours. This mirrors
    // GatePassController#report's @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER')")
    // exactly — the gate-pass module scopes every read to a tenant, so a SUPER_ADMIN
    // hitting it gets a 403, and offering the nav item would only surface that as a
    // broken page. Widen this only alongside the annotation.
    canViewGatePassReport: ['TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors the amenities/parking/bookings controllers' @PreAuthorize
    // hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER'). Unlike
    // canViewGatePassReport, SUPER_ADMIN is deliberately included here because
    // the backend admits it.
    canManageFacilities: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors PromotionAdminController's @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')").
    // Promotions are tenant-wide (not scoped to a single property), so
    // PROPERTY_MANAGER is deliberately excluded, unlike canManageFacilities.
    canManagePromotions: ['SUPER_ADMIN', 'TENANT_ADMIN'] as UserRole[],
    // Posting/reversing journal vouchers. Mirrors JournalController's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')").
    canPostJournals: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // Chart of accounts, property account mappings, template and defaults.
    // Mirrors AccountController/PropertyAccountController's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')").
    canManageAccountSetup: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],

    // ---- accounting-v2 plan 2: leasing / cheque register / penalties ----
    //
    // Mirrors LeaseController#postLease and #amendLeaseLines:
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
    // on POST /leases/{id}/post (incl. dry-run) and POST /leases/{id}/amend-lines.
    canPostLeases: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // Mirrors LeaseController#renewLease's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT','PROPERTY_MANAGER')").
    // Drafting next year's contract is open to a property manager — it writes no
    // journals — unlike posting it, which does.
    canRenewLeases: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors LeaseController#extendLease's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')") — unlike
    // renew, an extension posts a TCO immediately, so PROPERTY_MANAGER is excluded.
    canExtendLeases: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // Mirrors ChequeController's STAFF group (deposit, clear, receive, bounce,
    // replace, details, cash-receipt, deposit-batch) and LeaseController's cheque
    // grid endpoints (generate, generate numbers, save rows, bulk-attach), all
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT','PROPERTY_MANAGER')").
    canManageCheques: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors ChequeController's FINANCE group on PUT /{id}/cancel:
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')"). Narrower
    // than canManageCheques on purpose — cancelling reverses the registering
    // journal, a finance correction, so PROPERTY_MANAGER may work the register but
    // not cancel a row on it.
    canCancelCheques: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // Mirrors PenaltyAssessmentController#list and #propose's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT','PROPERTY_MANAGER')") —
    // spotting that a renter should be fined is part of running a building.
    canProposePenalties: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors PenaltyAssessmentController#approve/#waive/#reverse's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')") — turning
    // a proposal into a charge on the ledger is finance's decision, not a manager's.
    canApprovePenalties: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // ---- accounting-v2 plan 3: recognition / termination / settlement ----
    //
    // Ending a contract: POST /leases/{id}/terminate, which hands cheques back,
    // truncates recognition and posts a TCR. Mirrors LeaseController#terminateLease's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
    // (LeaseController.java:287-288).
    //
    // This key USED to be ['SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER'] and
    // its comment claimed the four settlement endpoints admitted a PM and refused
    // an ACCOUNTANT. Both halves are now false — plan 3 made termination a finance
    // act with its own journals, and gave the accountant the settlement. A PM is
    // not shut out of move-outs: they still preview a termination
    // (canPreviewTermination) and read the statement (canViewSettlement).
    canTerminateLeases: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // Looking at what ending a tenancy would cost. Mirrors
    // LeaseController#previewTermination's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT','PROPERTY_MANAGER')")
    // (LeaseController.java:273-274), scoped further to the manager's own
    // buildings by LeaseAccessPolicy. One role wider than canTerminateLeases on
    // purpose: reading the consequences of a move-out is the building manager's
    // job, posting the journals that end the contract is the accountant's.
    canPreviewTermination: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
    // Taking a renter's notice: POST /leases/{id}/notice, ACTIVE → NOTICE_GIVEN.
    // Mirrors LeaseController#giveNotice's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT','PROPERTY_MANAGER')")
    // (LeaseController.java:250-251).
    //
    // Its own key rather than a reuse of canTerminateLeases: the annotation is
    // one role wider, deliberately, because taking a notice writes no journal,
    // hands nothing back and leaves every instrument on the register exactly
    // where it was — which is the building manager's job, not finance's.
    canGiveNotice: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
    // Reading the move-out statement and the stored settlement row. Mirrors
    // LeaseController#getSettlementStatement and #getSettlement
    // (LeaseController.java:309-316) — both SA/TA/ACCOUNTANT/PM.
    canViewSettlement: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
    // Saving the settlement's lines and finalising it. Mirrors
    // LeaseController#saveSettlementDraft and #finalizeSettlement
    // (LeaseController.java:333-334, :360-361) — SA/TA/ACCOUNTANT, PM removed:
    // deciding what comes out of a renter's deposit posts an STL.
    canSettleLeases: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // The month-end close. Mirrors RecognitionController's FINANCE_ROLES
    // constant, "hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')"
    // (RecognitionController.java:50), on GET /finance/recognition/pending and
    // POST /finance/recognition/run. PROPERTY_MANAGER is deliberately absent —
    // the same controller admits them to GET /leases/{id}/recognition, because a
    // lease's schedule is part of the contract they manage while running a close
    // is an act on the organisation's books.
    canRunRecognition: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // Reading one lease's recognition schedule tab. Mirrors
    // RecognitionController#schedule's
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT','PROPERTY_MANAGER')")
    // (RecognitionController.java:110-111).
    canViewRecognitionSchedule: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors ChargeTypeController's write methods (POST, PUT /{id}):
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')"). The
    // class-level rule (which also admits PROPERTY_MANAGER) covers only the read,
    // and Spring Security does not combine the two — the narrower one wins on writes.
    canManageChargeTypes: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],

    // ---- accounting-v2 plan 4: vouchers ----
    //
    // Purchase/Service Invoices and Bank/Cash Payment Vouchers: the list, both
    // forms, post, amend, delete and the attachment sub-resources. Mirrors the
    // ONE class-level annotation on VoucherController
    // (VoucherController.java:55), which covers every handler in the file:
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')").
    //
    // Its own key rather than a reuse of canPostJournals even though the two
    // lists are identical today: they mirror different annotations, on different
    // controllers, and a future widening of one is not a widening of the other.
    // PROPERTY_MANAGER is absent because the controller refuses it — so the
    // sidebar shows a manager no voucher link, and the pages show an
    // access-denied panel rather than a screen that 403s on its first fetch.
    canManageVouchers: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
    // The cut-over import batches screen: the list and the one Reverse button.
    // Mirrors ImportBatchController's class-level @PreAuthorize
    // (ImportBatchController.java:44), which covers every handler in the file:
    // @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')").
    //
    // Reversing a cut-over unposts every contract it created, so it sits with
    // finance rather than with property management — PROPERTY_MANAGER is absent
    // because the controller refuses it. Its own key, not a reuse of
    // canManageVouchers: same set today, different annotation on a different
    // controller. NOTE the template download on that same page is narrower
    // still (SA/TA) — see cutoverRules.canDownloadImportTemplate.
    canManageImportBatches: ['SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT'] as UserRole[],
} as const;

export type Permission = keyof typeof PERMISSIONS;

/**
 * Role privilege ranking — lower number == more privileged.
 * Mirrors the backend hierarchy enforced in UserController.
 */
const ROLE_RANK: Record<UserRole, number> = {
    SUPER_ADMIN: 0,
    TENANT_ADMIN: 1,
    // ACCOUNTANT ranks ABOVE PROPERTY_MANAGER deliberately, mirroring
    // UserController#privilegeRank exactly. An accountant posts journal
    // entries, so a property manager must not be able to create one — with
    // assignableRoles' `>=` comparison, an equal rank here would have let a
    // PROPERTY_MANAGER assign ACCOUNTANT exactly as it may already assign
    // another PROPERTY_MANAGER. Strictly between TENANT_ADMIN and
    // PROPERTY_MANAGER is the only placement that leaves the role assignable
    // by TENANT_ADMIN and SUPER_ADMIN alone.
    ACCOUNTANT: 2,
    PROPERTY_MANAGER: 3,
    TENANT_USER: 4,
    RENTER: 5,
    SECURITY_GUARD: 6,
};

/**
 * Roles a user with the given role is allowed to create/assign: their own
 * level and anything below it. A TENANT_ADMIN therefore cannot provision a
 * SUPER_ADMIN. Mirrors the server-side check in UserController.
 */
export function assignableRoles(role: UserRole | undefined): UserRole[] {
    if (!role) return [];
    const callerRank = ROLE_RANK[role];
    return (Object.keys(ROLE_RANK) as UserRole[]).filter((r) => ROLE_RANK[r] >= callerRank);
}

/**
 * Check if a role has a specific permission
 */
export function hasPermission(role: UserRole | undefined, permission: Permission): boolean {
    if (!role) return false;
    return PERMISSIONS[permission].includes(role);
}

/**
 * Check if a role is in a list of allowed roles
 */
export function hasRole(role: UserRole | undefined, allowedRoles: UserRole[]): boolean {
    if (!role) return false;
    return allowedRoles.includes(role);
}

/**
 * Translation key for a role, for use as t(getRoleLabelKey(role)) with the
 * "Roles" namespace.
 *
 * getRoleLabel below is kept for non-React callers, but it returns English
 * unconditionally — the role sits under the user's name in the top bar on
 * every page, so in Arabic it was the last English text on the shell.
 */
export function getRoleLabelKey(role: UserRole | string): string {
    return String(role);
}

/**
 * Get human-readable role label. English only — prefer getRoleLabelKey with a
 * translator anywhere the string is rendered to a user.
 */
export function getRoleLabel(role: UserRole | string): string {
    const labels: Record<string, string> = {
        SUPER_ADMIN: 'System Admin',
        TENANT_ADMIN: 'Tenant Admin',
        PROPERTY_MANAGER: 'Property Manager',
        SECURITY_GUARD: 'Security Guard',
        TENANT_USER: 'Tenant',
        RENTER: 'Renter',
        ACCOUNTANT: 'Accountant',
    };
    return labels[role] || role.replace(/_/g, ' ');
}

export function canViewPayments(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'].includes(role);
}

export function canManagePayments(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN'].includes(role);
}

export function canPayOnline(role: UserRole): boolean {
    return ['RENTER'].includes(role);
}

export function canConfigureGateway(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN'].includes(role);
}

export function canConfigureRentSettings(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN'].includes(role);
}

export function canConfigureFines(role: UserRole): boolean {
    return ['SUPER_ADMIN', 'TENANT_ADMIN'].includes(role);
}

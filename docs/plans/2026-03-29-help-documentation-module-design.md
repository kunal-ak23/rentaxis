# Help & Documentation Module — Design Document

**Date:** 2026-03-29
**Status:** Approved
**Approach:** Shepherd.js guided tours + custom markdown help center (Approach B)

---

## Overview

A comprehensive in-app help system for RentAxis that provides:
1. **Guided interactive tours** — role-specific step-by-step walkthroughs of key workflows
2. **Persistent help center** — searchable, role-filtered documentation articles
3. **Contextual help** — floating help button linking to page-relevant articles and tours

All 5 roles (SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, TENANT_USER, RENTER) get tailored documentation. English-first, Arabic i18n support structurally ready for later.

---

## Architecture & File Structure

```
web/src/
  app/[locale]/dashboard/help/
    page.tsx                    # Help center main page (searchable, role-filtered)
    [slug]/page.tsx             # Individual article view
  components/help/
    HelpCenter.tsx              # Help center layout (sidebar categories + article list)
    HelpArticle.tsx             # Single article renderer (markdown -> HTML)
    HelpSearch.tsx              # Search bar with live filtering
    HelpFAB.tsx                 # Floating "?" button on all dashboard pages
    RoleFilter.tsx              # Role badge filter chips
  components/tour/
    TourProvider.tsx            # Shepherd.js wrapper, manages tour state
    TourTrigger.tsx             # "Take a tour" button component
    tours/                      # Tour definitions per role + workflow
      admin-onboarding.ts       # Tenant Admin first-time tour
      property-workflow.ts      # Add property -> units -> lease flow
      finance-overview.ts       # Finance module walkthrough
      renter-portal.ts          # Renter portal tour
      super-admin.ts            # Super admin tour
  content/help/                 # Markdown articles
    getting-started/
      welcome.md
      roles-and-permissions.md
      first-property-setup.md
    properties/
      managing-properties.md
      units-and-buildings.md
    leases/
      creating-a-lease.md
      lease-lifecycle.md
      payment-schedules.md
    finance/
      chart-of-accounts.md
      recording-transactions.md
      financial-reports.md
    renter/
      renter-portal-overview.md
      submitting-tickets.md
      making-payments.md
    admin/
      managing-staff.md
      tenant-settings.md
      super-admin-guide.md
```

---

## Guided Tours (Shepherd.js)

### Tour Step Shape

```typescript
interface TourStep {
  id: string;
  target: string;           // data-tour selector e.g. '[data-tour="sidebar-properties"]'
  title: string;
  text: string;
  position: 'top' | 'bottom' | 'left' | 'right';
  roles: UserRole[];
  nextRoute?: string;       // navigate to a different page mid-tour
}
```

### Tours per Role

| Tour | Roles | Steps | Trigger |
|------|-------|-------|---------|
| Welcome Onboarding | ALL | 5-6 | Auto on first login |
| Property Setup | TENANT_ADMIN, PROPERTY_MANAGER | 8-10 | Help center / FAB |
| Lease Workflow | TENANT_ADMIN, PROPERTY_MANAGER | 7-8 | Help center / FAB |
| Finance Overview | TENANT_ADMIN | 6-7 | Help center / FAB |
| Renter Portal | RENTER | 4-5 | Auto on first login |
| Super Admin | SUPER_ADMIN | 4-5 | Auto on first login |

### UX Rules

- **Always skippable** — Skip + Back + Next buttons on every step
- **Progress indicator** — "Step 3 of 8" in the tooltip
- **Spotlight overlay** — dims everything except the target element (Shepherd built-in modal)
- **`data-tour` attributes** — stable selectors on key UI elements (won't break with CSS changes)
- **Cross-page tours** — steps can specify `nextRoute` to navigate mid-tour
- **Completion tracking** — `localStorage` key `rentaxis_tours_completed: string[]`
- **Resume capability** — if user closes mid-tour, offer to resume next visit
- **Styling** — Shepherd tooltips themed: teal header, white body, gold accent on CTA buttons

---

## Help Center

### Layout

Two-column layout:
- **Left sidebar** — collapsible category sections matching content folder structure
- **Right main area** — article cards in a grid, with search bar at top
- **Bottom section** — available tours for user's role with completion status

### Features

- **Role-aware filtering** — articles tagged with roles in frontmatter; users only see relevant articles (admins get an "All articles" toggle)
- **Client-side search** — `string.includes()` on title + content, 200ms debounce
- **Article cards** — title, description, role badges (teal chips), reading time estimate
- **Tour section** — shows available tours with completion status (checkmark / play button)
- **Breadcrumbs** — Help Center > Category > Article

### Article Frontmatter

```markdown
---
title: Creating a Lease
description: Step-by-step guide to creating and activating a lease
category: leases
roles: [TENANT_ADMIN, PROPERTY_MANAGER]
order: 1
relatedTour: lease-workflow
---
```

### Markdown Rendering

`react-markdown` renders `.md` to React components. Articles loaded via a content utility that parses frontmatter and provides metadata for filtering/search.

---

## Floating Help Button (FAB)

- Fixed position bottom-right, teal circle with `HelpCircle` icon (Lucide)
- Click opens a small popover with:
  - "View help for this page" — contextual link to matching article
  - "Take a tour" — launches the page-relevant tour
  - "Open Help Center" — navigates to `/dashboard/help`
- Framer Motion enter/exit animation
- Uses route-to-help mapping for contextual awareness

---

## Contextual Page Mapping

```typescript
const helpMap: Record<string, { article?: string; tour?: string }> = {
  '/dashboard':               { article: 'getting-started/welcome', tour: 'admin-onboarding' },
  '/dashboard/properties':    { article: 'properties/managing-properties', tour: 'property-workflow' },
  '/dashboard/leases':        { article: 'leases/creating-a-lease', tour: 'lease-workflow' },
  '/dashboard/finance':       { article: 'finance/chart-of-accounts', tour: 'finance-overview' },
  '/dashboard/renter-portal': { article: 'renter/renter-portal-overview', tour: 'renter-portal' },
}
```

Powers both the FAB contextual links and auto-suggesting relevant tours.

---

## Sidebar Integration

- New menu item in sidebar under "Support" section (before version footer)
- Icon: `HelpCircle` from Lucide
- Label: "Help & Guides"
- Visible to all roles

---

## First-Visit Experience

- On first login (no `rentaxis_tours_completed` in localStorage), auto-trigger the role-appropriate onboarding tour after 1-second delay
- Show a dismissible welcome banner at top of dashboard: "Welcome to RentAxis! Take a quick tour to get started." with Start Tour + Dismiss actions

---

## Dependencies

| Package | Size | Purpose |
|---------|------|---------|
| `react-shepherd` | ~12KB gzipped | Guided tour engine with modal overlay, a11y, RTL support |
| `react-markdown` | ~5KB gzipped | Render markdown articles to React components |

**No backend changes required.** Entirely frontend.

---

## Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tour library | `react-shepherd` | MIT, battle-tested, built-in modal overlay, a11y, RTL-ready |
| Markdown rendering | `react-markdown` | Lightweight, no MDX build complexity |
| Search | Client-side `string.includes()` | ~15 articles initially, no Fuse.js overhead needed |
| Tour state | `localStorage` | Simple, no backend changes, per-browser |
| Content storage | `.md` files in `web/src/content/help/` | Git-versioned, easy to edit |
| Styling | Tailwind + existing design tokens | Teal headers, gold accents, compact enterprise density |
| i18n | English-only, structurally ready | Frontmatter can add `locale` field; folder can split to `en/` + `ar/` |

---

## Content Plan (15 articles)

| Category | Article | Roles |
|----------|---------|-------|
| Getting Started | Welcome to RentAxis | ALL |
| Getting Started | Roles & Permissions | ALL |
| Getting Started | First Property Setup | TENANT_ADMIN, PROPERTY_MANAGER |
| Properties | Managing Properties | TENANT_ADMIN, PROPERTY_MANAGER |
| Properties | Units & Buildings | TENANT_ADMIN, PROPERTY_MANAGER |
| Leases | Creating a Lease | TENANT_ADMIN, PROPERTY_MANAGER |
| Leases | Lease Lifecycle | TENANT_ADMIN, PROPERTY_MANAGER |
| Leases | Payment Schedules | TENANT_ADMIN, PROPERTY_MANAGER |
| Finance | Chart of Accounts | TENANT_ADMIN |
| Finance | Recording Transactions | TENANT_ADMIN |
| Finance | Financial Reports | TENANT_ADMIN |
| Renter | Renter Portal Overview | RENTER |
| Renter | Submitting Tickets | RENTER |
| Renter | Making Payments | RENTER |
| Admin | Managing Staff | TENANT_ADMIN |
| Admin | Tenant Settings | TENANT_ADMIN |
| Admin | Super Admin Guide | SUPER_ADMIN |

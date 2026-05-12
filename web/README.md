This is a [Next.js](https://nextjs.org) project bootstrapped with [`create-next-app`](https://nextjs.org/docs/app/api-reference/cli/create-next-app).

## Getting Started

First, run the development server:

```bash
npm run dev
# or
yarn dev
# or
pnpm dev
# or
bun dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

You can start editing the page by modifying `app/page.tsx`. The page auto-updates as you edit the file.

This project uses [`next/font`](https://nextjs.org/docs/app/building-your-application/optimizing/fonts) to automatically optimize and load [Geist](https://vercel.com/font), a new font family for Vercel.

## Learn More

To learn more about Next.js, take a look at the following resources:

- [Next.js Documentation](https://nextjs.org/docs) - learn about Next.js features and API.
- [Learn Next.js](https://nextjs.org/learn) - an interactive Next.js tutorial.

You can check out [the Next.js GitHub repository](https://github.com/vercel/next.js) - your feedback and contributions are welcome!

## Deploy on Vercel

The easiest way to deploy your Next.js app is to use the [Vercel Platform](https://vercel.com/new?utm_medium=default-template&filter=next.js&utm_source=create-next-app&utm_campaign=create-next-app-readme) from the creators of Next.js.

Check out our [Next.js deployment documentation](https://nextjs.org/docs/app/building-your-application/deploying) for more details.

## Lease Renewal Feature

This feature delivers 90/60/30-day renewal reminders, renter intent capture via signed magic links, a CRM-style interaction log on every lease, and a renter portal renewal page.

### Configuration (env vars)

- `APP_RENEWAL_SCHEDULER_ENABLED` — Toggle the daily 08:00 cron (default `false`). Flip `true` after staging smoke test.
- `APP_RENEWAL_TOKEN_SECRET` — HMAC secret for magic-link tokens. Must be ≥32 bytes. Rotate periodically.
- `APP_RENEWAL_PORTAL_BASE_URL` — Base URL embedded in renewal emails (no trailing slash).

### Manual operations

- `POST /api/v1/admin/renewals/run-now` — SUPER_ADMIN only. Triggers the scheduler immediately (open opportunities, fire reminders, close stale).
- `POST /api/v1/leases/{id}/renewal/mark-renewed` — TENANT_ADMIN or PROPERTY_MANAGER. Manually closes a lease's open opportunity as CLOSED_WON / RENEWED. Optional body `{ "note": "..." }` adds a NOTE interaction.

### Renter portal

- `/dashboard/renter-portal/renewals` — Bucketed view of leases by renewal window.
- `/dashboard/renter-portal/renewal-intent?token=<jwt>` — Magic-link landing page from email CTAs.

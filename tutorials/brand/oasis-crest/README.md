# Oasis Crest Properties — fictional demo brand

**Oasis Crest Properties L.L.C. is not a real company.** It is the demo organisation the
tutorial recordings run against on the local recording stack (`tutorials/recording-stack.sh`).
Every asset here is original and made for the demo: the name, the crest, the stamp and
its licence number are invented. The stamp is marked "DEMO · NOT VALID".

| File | Use |
|---|---|
| `logo-mark.svg`, `logo-mark.png` | crest only, square; the one uploaded as the org logo. The app has one logo slot: the header shows it in a 32 px round avatar, and the receipt, tax invoice and contract PDFs print it (40 px tall) next to the organisation name, which they already print as text |
| `logo.svg`, `logo-512.png` | square mark + wordmark |
| `logo-wide.svg`, `logo-wide.png` | wide lock-up (the org logo until 2026-09-29: illegible in the round header avatar) |
| `stamp.svg`, `stamp.png` | round company stamp for `stampImageUrl` |
| `render.mjs` | re-renders the PNGs from the SVGs with headless Chromium |

Organisation details the seed applies (`tutorials/recording-stack.sh seed`, through
`DEMO_ORG_*` in `scripts/seed_demo_tenant.py`) — all fictional:

- Address: Office 1407, Crest Tower, Marasi Drive, Business Bay, Dubai, United Arab Emirates, P.O. Box 00000
- Phone: +971 4 000 0000
- TRN: 100123456700003
- Email domain: `oasiscrest.example` (reserved, undeliverable)

Palette: teal `#0F5F5A`, sea `#7FC4B8`, sand `#E2BE7E`, bronze `#B08A4E`, stamp ink `#1D3F8F`.

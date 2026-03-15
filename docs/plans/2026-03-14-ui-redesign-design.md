# RentAxis UI Redesign — Luxury Real Estate

**Date:** 2026-03-14
**Status:** Approved

## Design Direction
Luxury Real Estate aesthetic for Dubai-based clientele. Dark navy sidebar, gold accents, Cinzel/Josefin Sans typography, professional warm palette.

## Color Palette
| Role | Hex |
|------|-----|
| Sidebar/Nav | #0F1B2D |
| Primary (Teal) | #0F766E |
| Accent/CTA (Gold) | #C8A951 |
| Background | #FAFAF8 |
| Card Surface | #FFFFFF |
| Text Primary | #0F172A |
| Text Muted | #475569 |
| Border | #E2E0DC |
| Success | #059669 |
| Warning | #D97706 |
| Error | #DC2626 |
| Info | #0D9488 |

## Typography
- Headings: Cinzel (400/600/700)
- Body: Josefin Sans (300/400/500/600)

## AED Currency
- Standard: `AED 1,234.56`
- `Intl.NumberFormat('en-AE', { style: 'currency', currency: 'AED' })`
- Shared utility: `web/src/lib/format.ts`

## Scope
All 23 dashboard pages, shared components, currency formatter across 14 files.

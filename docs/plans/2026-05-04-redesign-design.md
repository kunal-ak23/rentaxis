# RentAxis Redesign — Design Document
**Date:** 2026-05-04  
**Scope:** Web (Next.js) + Renter mobile (Flutter) + Manager mobile (Flutter)  
**Source:** `Rentaxis-handoff.zip` / `RentAxis Redesign.html`

---

## 1. Design system

### Palette

| Token | Value | Replaces |
|---|---|---|
| Primary (navy) | `#0B1F3A` | `#0F766E` teal |
| Accent (gold) | `#C9A961` | `#C8A951` (near-identical) |
| Background (sand) | `#FAF7F2` | `#FAFAF8` |
| Surface | `#FFFFFF` | unchanged |
| Border | `#E6DFD3` | `#E2E0DC` |
| Border strong | `#D8CBB1` | — |
| Text-1 | `#0B1F3A` | `#0F172A` |
| Text-2 | `#335580` | `#475569` |
| Text-3 | `#5C7494` | `#94A3B8` |
| Sidebar bg | `#FFFFFF` (with navy avatar/accents) | `#0F1B2D` dark sidebar |
| Dark mode bg | `#0A1424` | `#0A1120` |
| Status: green | `#2F7B4C` / bg `#DDEFE3` | `#059669` |
| Status: amber | `#B5781E` / bg `#F8EBD0` | `#D97706` |
| Status: red | `#B33A30` / bg `#F6DAD6` | `#DC2626` |
| Gold tones | 700:`#8C7434` 500:`#C9A961` 100:`#F4EBD3` | — |
| Teal (secondary) | `#2D7D7D` | — |

### Typography

| Role | Font | Usage |
|---|---|---|
| Display / headlines | Source Serif 4 (Google Fonts) | Page titles, greetings, big AED numbers, section headings |
| Body / UI | Inter (Google Fonts) | Table cells, labels, buttons, captions, body copy |
| Numerics | JetBrains Mono (Google Fonts) | Cheque numbers, AED amounts, reference IDs — tabular figures |

**Arabic fallback:** Switch to IBM Plex Sans Arabic (body) + IBM Plex Serif (display) when `dir="rtl"` — metrically close to Inter/Source Serif.

### Spacing & radius (design tokens)
- Radius: `--radius-sm: 6px`, `--radius: 10px`, `--radius-lg: 14px`
- Density: compact / comfy / spacious (via CSS vars)
- Row height: `52px` (comfy default)

### Status pills
`.pill-paid` (green), `.pill-pending` (amber), `.pill-overdue` (red), `.pill-cleared` (gold), `.pill-bounced` (red), `.pill-future` (ink/muted)

---

## 2. Web — Next.js

### Shell changes
- **Sidebar** (`web/src/components/Sidebar`): white bg, `244px` expanded / `68px` collapsed, gold active-item left-bar accent, section labels ("Workspace" / "Operations"), org-switcher footer with navy avatar + gold initials
- **Topbar**: 60px, Source Serif 4 title, search bar with ⌘K hint, bell icon, avatar

### Screen A — Dashboard (`/dashboard`)
```
Greeting row           : date + "Good morning, Omar" (Source Serif) + alert chips + action buttons
KPI grid (4-col)       : StatCard × 4 (label, serif value, delta arrow, sub-text, sparkline SVG)
Row 2 (1.6/1 split)    : CollectionChart (SVG line, 12-month, expected dashed gold + collected navy)
                         ChequePipeline (5 stage rows with progress bars)
Row 3 (1.4/1 split)    : ExpiringLeases table (unit+tenant, annual rent, renewal pill, days)
                         RecentActivity feed (avatar + action + detail + time)
```

### Screen B — Lease detail (`/leases/[id]`)
```
Header                 : 64px navy-gradient unit badge + tenant name + contact chips + action buttons
Ribbon (5-col)         : Lease term | Annual rent | Security deposit | Collected | Status
Tab bar                : Overview / Payment schedule* / Penalties / Contract / Maintenance / Documents
Main (1.5/1 split)
  Left: Payment schedule  : Dot-rail timeline with ChequeRow cards (cheque chrome: gradient bg, gold left stripe)
  Right col:
    PenaltiesCard          : Penalty rows (icon badge, title, detail, status pill, date, amount)
    ContractCard           : Document preview + contract facts grid + renewal alert banner
```

### Screen C — Finance (`/finance`)
```
Header                 : "Payments ledger" + filter/export/record buttons
Summary strip (4-col)  : Cleared | Pending | Overdue | Total (mono serif numbers)
Filter chips           : active=navy-filled pill, inactive=outline
Table                  : Date | Reference (mono) | Tenant+Unit (avatar) | Method | Bank | Amount (mono) | Status pill | ⋯
Footer                 : pagination with page 1 highlighted in navy
```

---

## 3. Renter mobile — Flutter

### Theme updates
- `AppColors.primary` → `0xFF0B1F3A`
- `AppColors.background` → `0xFFFAF7F2`
- Fonts: `GoogleFonts.sourceSerif4` (headlines), `GoogleFonts.inter` (body), `GoogleFonts.jetBrainsMono` (numerics)
- `BottomNavigationBar` bg → white with blur, selected = navy, icon size 20

### Screen A — Home (`home_screen.dart`)
- Top bar: tenancy address + avatar (gold bg)
- Hero card: navy gradient (160deg), radial gold glow top-right, "Next payment due" label, AED 23,750 in Source Serif 36, progress dots (3 gold / 1 muted), "Pay now" (gold) + "Set reminder" (ghost) buttons
- Quick actions: 4-tile grid (Cheques / Maintain / Contract / Contact), sand-100 icon containers
- Recent activity: list of cards with ActIcon + label + detail + optional amount

### Screen B — Cheques (`payments_screen.dart`)
- Progress card: sand-100 bg, AED x/95,000 in Source Serif 28, gold gradient progress bar
- Cheque list: white cards with left 3px stripe (green=cleared, gold=pending), cheque number (mono), due date, status pill

### Screen C — Pay rent (new screen or modal)
- Centred amount: "AMOUNT DUE" caption, AED 23,750 in Source Serif 44
- Method picker: 3 rows (cheque/transfer/card) with icon tile, name+detail, radio dot; selected = navy border + navy icon tile + gold icon
- Receipt summary: base rent, late fee, adjustments, divider, total (mono bold)
- CTA: full-width gold button "Continue · AED 23,250"

---

## 4. Manager mobile — Flutter

### Theme updates
Same as renter app (shared `rentaxis_core` package — update once).

### Screen A — Today (`dashboard_screen.dart`)
- Date + greeting + search + bell (with red badge)
- Hero card: navy gradient + gold radial glow, "6 actions need you" (Source Serif 26), 3-col MiniStats (3 Cheques/2 Renewals/1 Maintenance)
- Quick actions: 4-tile grid — Scan cheque tile is gold-filled (primary action)
- Task queue: TaskRow cards with coloured tag badges (DEPOSIT/RENEWAL/OVERDUE/VISIT)
- Portfolio glance: surface card with collected AED + occupancy % + gold progress bar

### Screen B — Leases (`leases_screen.dart`)
- Search bar (border-radius 12, search icon, placeholder text)
- Filter chip tabs: All / Active / Expiring / Issues (active = navy-filled)
- Lease list: white cards with 4px left status bar (green/red/gold), avatar, tenant name + unit, AED amount + expiry

### Screen C — Scan cheque (`shell_screen.dart` FAB or new screen)
- Camera viewfinder: dark navy bg, 1.7:1 aspect ratio, faux cheque tilted −2°, gold reticle corner brackets
- "Detecting cheque" status banner (dark blur pill at bottom of viewfinder)
- OCR results table: KV rows, amount row highlighted gold-100
- Match banner: gold-100 bg + check icon + lease match text
- Retake (secondary) + Confirm & log (navy primary) buttons

---

## 5. Implementation notes

- **Shared Flutter theme:** Changes to `mobile/packages/rentaxis_core/lib/theme/app_theme.dart` apply to both apps automatically
- **Web CSS tokens:** `globals.css` token update propagates to all existing Tailwind-using screens
- **No new API endpoints needed** — all screens use existing data
- **RTL:** Token update preserves existing `dir="rtl"` support; Arabic font swap is a CSS variable change only
- **Liquibase:** No DB changes required

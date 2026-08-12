# Handoff: Miftah mobile redesign (Resident · Manager · Security)

## Overview

A full visual reinvention of the three Miftah Flutter apps — `apps/renter`,
`apps/manager`, `apps/security`. The brief was: the current UI feels dated
(sharp edges, dark navy/gold chrome bars on every screen, tracked-uppercase
serif titles, an older arrangement of elements). Only the logo is retained.

50 screens are designed across the three apps. Two information-architecture
changes are proposed alongside the visual work:

- **Resident:** six bottom tabs (Home · Browse · Saved · Meetings · Payments ·
  Tickets) collapse to **four plus a raised centre action** — Home · Explore ·
  **Pass** · Wallet · Services. Saved lives inside Explore; Meetings, Tickets,
  Facilities, Gate passes and Approvals live inside a Services hub.
- **Manager:** scattered approval surfaces (gate-pass approvals, booking
  approvals, lease signatures) collapse into a single **Queue** tab.

Both are proposals, not requirements — confirm with the design owner before
implementing. Everything else is a like-for-like re-skin.

## About the design files

The files in `designs/` are **design references written in HTML**. They are
prototypes showing intended look, layout and spacing — **not production code to
port**. The task is to recreate them in the existing Flutter codebase using its
own patterns (Riverpod providers, `go_router`, the `rentaxis_core` shared
package).

`designs/*.dc.html` open directly in a browser. Each file is a wall of phone
frames at 412 × 916 (Android), each captioned with a number and a one-line note.
Open them side by side with the code you are editing.

`dart/` contains **real, ready-to-use Dart** implementing the design system —
tokens, theme, and a widget for every element in the mockups. Start there; see
"Implementation order" below.

## Fidelity

**High fidelity.** Colours, type, spacing, radii and copy in the mockups are
final and exact. Recreate them faithfully. Where a mockup and this README
disagree, this README wins.

Photography and map tiles are represented by diagonal-stripe placeholders with a
monospace caption ("listing photo", "map tiles", "amenity photo") — those are
placeholders for real content, not a visual treatment to reproduce.

## Design tokens

All of these are already expressed in `dart/miftah_tokens.dart`.

### Colour

| Token | Hex | Use |
| --- | --- | --- |
| `ink` | `#12101A` | Dark headers, primary buttons, selected chips, active nav |
| `inkSoft` | `#2A2536` | Ink button hover/pressed |
| `canvas` | `#F6F5FA` | Scaffold background |
| `surface` | `#FFFFFF` | Cards, app bar, nav bar |
| `surfaceAlt` | `#F4F2F9` | Search fields, inline tag chips |
| `border` | `#EDEAF4` | Card border (1px), dividers |
| `borderStrong` | `#E6E3EE` | Icon-button outline, unselected chip border |
| `textPrimary` | `#12101A` | Headings, values |
| `textSecondary` | `#4A4358` | Body |
| `textMuted` | `#8E88A0` | Captions, labels |
| `textFaint` | `#A39CB8` | Placeholders, disabled |
| `brass` | `#C79A3C` | Accent mid |
| `brassDeep` | `#A87A1E` | Accent dark — icons on light, link text |
| `brassLight` | `#E3BE6E` | Accent light — text/icons on ink |
| `brassPale` | `#E7C883` | Gradient terminal |
| `brassTint` / border | `#FBF3E2` / `#EBD7A8` | Selected chip, warning badge |
| `success` / tint | `#1E9E5A` / `#E7F3EC` | Cleared, approved |
| `successDeep` | `#1E7A4A` | Success icon on tint |
| `danger` / bright | `#C13B3B` / `#D64545` | Overdue, rejected; notification dot |
| `dangerTint` / border | `#FDF0F0` / `#F5DADA` | Danger card, destructive button |
| `warning` / tint | `#8A6412` / `#FBF3E2` | Pending, in progress |
| `info` / tint | `#4A5B72` / `#E7EAEF` | Neutral category icons (HVAC, bank) |
| Dark canvas / surface | `#0E0C14` / `#1B1826` | Dark mode |
| Presentation canvas | `#08060E` | The HTML wall background only — not an app colour |

**The gold gradient** — the single accent gradient in the system:
`linear-gradient(120deg, #A87A1E 0%, #C79A3C 55%, #E7C883 100%)`.
Used only for: the money hero card, the gold CTA, the raised nav action, the
avatar on Profile, and progress fills. Nothing else may be gold-filled.

Every gradient card carries one decorative bloom: a 170 px circle of
`rgba(255,255,255,0.14)`, positioned `right: -44px; top: -56px`, clipped.

### Typography

One family. Weight does the work — no tracked-uppercase headings, no serif
display. (The old `Cinzel` + `Josefin Sans` pairing is the main source of the
dated feel.)

| Role | Font | Size | Weight | Letter-spacing |
| --- | --- | --- | --- | --- |
| Display (login) | Plus Jakarta Sans | 38–40 | 800 | −0.03em |
| Screen title | Plus Jakarta Sans | 22 | 800 | −0.02em |
| Amount / KPI | Plus Jakarta Sans | 26–38 | 800 | −0.025em |
| Card title | Plus Jakarta Sans | 15.5–16.5 | 800 | −0.01em |
| Body | Plus Jakarta Sans | 13–14.5 | 400 | 0, line-height 1.5 |
| Meta / caption | Plus Jakarta Sans | 11.5–12.5 | 500–600 | 0 |
| Section label | Plus Jakarta Sans | 11 | 800 | **0.14em, uppercase** |
| Badge | Plus Jakarta Sans | 10–10.5 | 800 | 0.06–0.1em, uppercase |
| Button | Plus Jakarta Sans | 15–17 | 800 | 0 |
| Mono | IBM Plex Mono | 11–22 | 400–500 | Codes, plate numbers, cheque numbers, reference IDs, dates in captions, the gate-pass code (22 px, 0.24em) |
| Arabic | Noto Naskh Arabic | +0.5 over Latin | as above | **always 0** — tracking breaks glyph joining |

All three families are on Google Fonts; the apps already depend on
`google_fonts`, so no asset bundling is required.

### Geometry

| Token | Value | Use |
| --- | --- | --- |
| Card radius | 20 | List cards, panels |
| Hero radius | 22 | Gradient money cards |
| Tile radius | 16 | Quick actions, inputs, buttons, sheets' inner cards |
| Chip radius | 11 | Filter chips — deliberately squarish, not pills |
| Sheet radius | 30 (top only) | Bottom sheets |
| Pill radius | 999 | Status badges, avatars, icon buttons |
| Page gutter | 20 | Horizontal padding on every screen |
| Card gap | 11 | Vertical space between stacked cards |
| Card padding | 17 | Inside a list card |
| Hero padding | 20 | Inside a gradient card |

**Cards do not get shadows.** They get a 1 px `#EDEAF4` border. Shadow is
reserved for elements that genuinely float: the raised nav action, the map peek
card, the gold CTA (`0 12px 28px rgba(199,154,60,0.32)`), the emphasised
approval card.

### Elevation & emphasis

Exactly one card per screen may be emphasised, using a **1.5 px brass border**
plus a soft brass glow — the pending gate approval, the selected technician, the
matched cheque. Everything else is a flat 1 px border.

## Screens

### Resident — `designs/Resident App.dc.html` (25 screens)

| # | Screen | Notes |
| --- | --- | --- |
| 01 | Sign in | Ink background, gold radial bloom top-right, gold CTA |
| 02 | Home | Gold hero (next cheque), penalty strip, 4 quick actions, amenity promo, activity list |
| 03 | Wallet · cheques | Gold hero (annual rent + progress), filter chips, cheque list; the live cheque card is **inverted to ink** |
| 04 | Penalties | Danger summary card, per-penalty card with Pay / Dispute |
| 05 | Explore | Location header, search + filter (badge count), chip row, listing cards |
| 06 | Map view | Price pins — the focused one is ink, others white; peek card bottom |
| 07 | Listing detail | 330 px hero, three fact tiles, brass-bordered viewing promo, amenity tags, pinned CTA bar |
| 08 | Saved | Price-drop shown inline in green; delisted rows at 60 % opacity |
| 09 | Services hub | 2 × 3 grid, tinted icon tiles, badge on Approvals |
| 10 | Maintenance list | Search, filter chips, category icon + status badge per row |
| 11 | Ticket detail | Ink header, assignee card with call action, photo strip, timeline, comment bar |
| 12 | New request | Category grid (no dropdowns), priority row, description, photo adder |
| 13 | Filters sheet | Bedrooms row, dual-handle rent slider, furnishing, toggle; CTA shows live result count |
| 14 | Visitors | Ink "create a pass" banner, pass rows with status; no live codes in the list |
| 15 | Gate pass | **Full ink screen** — QR on a white card, 6-digit code in mono at 0.24em |
| 16 | New pass | One-time / recurring segmented control, form cards, purpose chips |
| 17 | Visitor approvals | Emphasised pending card with mono countdown; resolved rows below |
| 18 | Facilities | 2-col amenity cards with image header, parking rows |
| 19 | Booking request | Sheet — date strip, note, info strip, CTA |
| 20 | Meetings | Date block (60 px) leads each row |
| 21 | Meeting detail | Ink header, four info rows, mini map, calendar / cancel |
| 22 | Book a meeting | Day strip, slot grid — taken slots struck through |
| 23 | Inbox | Unread carries a 1.5 px brass border **and** a brass dot |
| 24 | Profile | Ink header with gradient avatar, documents pulled to the top |
| 25 | My requests + empty state | Shows the dashed-border empty panel pattern |

### Manager — `designs/Manager App.dc.html` (15 screens)

Sign in (biometrics) · Today (2 × 2 KPI grid + "needs attention" feed with
semantic left accents + quick actions) · Portfolio · Property detail (tabbed,
unit roster) · Finance (gold collected hero, accounts, 6-month bar chart) ·
Cheque scan capture · Cheque scan review (auto-matched to lease) · Queue ·
Leases · Lease detail (cheque schedule) · Tickets (unassigned first, action
inline) · Assign sheet · Listings · Guards & gate policy · More.

Manager nav: Today · Portfolio · **Scan** (centre) · Finance · Queue.

### Security — `designs/Security App.dc.html` (10 screens)

Phone sign in · OTP (56 px keypad keys, gloved-hand target size) · Guard home
(ink header, scan is the whole hero) · Scanner (corner brackets in
`#E3BE6E`, keyed-code fallback always visible) · Result ALLOWED (full-screen
green `#0E2B1B`) · Result REJECTED (full-screen red `#31120E`, reason in plain
words plus a way out) · Walk-in · Walk-in status (2-minute timer, override
disabled until it expires) · Approvals · Shift settings sheet.

Security nav: Visitors · Scan · Approvals (three slots, no centre action).

**Guard-specific rules:** minimum body size 14, minimum touch target 52 px,
verdict screens are full-bleed single-colour and readable at arm's length in
daylight, and the gate-pass / result screens are dark so the screen doubles as a
light source at night.

## Interactions & behaviour

- **Tab roots** cross-fade; **pushed detail screens** slide up. This matches the
  existing `fadeTransition` / `slideUpTransition` helpers in
  `rentaxis_core/widgets/page_transitions.dart` — keep them.
- **List entrance:** each card rises 12 px and fades in, 500 ms
  `cubic-bezier(.2,.8,.2,1)`, staggered 70 ms per index. The existing
  `AnimatedListItem` already does this — keep it.
- **Progress bars** animate their fill from 0 on first paint, 700–900 ms,
  `easeOutCubic`, 200–300 ms delay.
- **Chips** animate background/border over 160 ms.
- **Sheets** slide up 340 ms `cubic-bezier(.2,.8,.2,1)` over a
  `rgba(18,16,26,0.5)` scrim.
- **Pins on the map** pop in (scale 0.88 → 1.03 → 1) 450 ms, staggered.
- Pressed state on cards: translate Y −2 px; on the ink button: background to
  `#2A2536`.

Nothing in the mockups depends on JavaScript behaviour — they are static walls.
Behaviour above is the specification.

## State & data

No new backend surface is required. Every value shown maps to an existing
provider:

- Resident home — `_myLeasesProvider`, `_myPaymentsProvider`,
  `_openPenaltyCountProvider`, `notificationProvider`
- Wallet — `PaymentService.getMyPayments`, `LeaseService.getMyLeases`; keep
  `effectivePaymentStatus` and `isNonRentPayment` exactly as they are
- Explore — `browseListingsProvider` + `browseFiltersProvider`
- Services badges — ticket, booking, gate-pass and approval counts
- Manager Today — `DashboardService.getSummary`
- Manager Queue — union of gate-pass approvals, booking approvals and leases
  pending signature (client-side merge; no new endpoint)
- Security — `expectedTodayProvider`, `myPropertiesProvider`,
  `approvalsProvider`, `gatePassServiceProvider.scan`

Preserve all existing empty/error/loading branching. In particular keep the
security home screen's three-way empty state (no properties assigned vs. no
visitors today vs. assignments call failed) — the redesign changes only its
styling.

## The Dart bundle

`dart/` contains three files, ready to drop into
`packages/rentaxis_core/lib/ui/` and export from `rentaxis_core.dart`:

- **`miftah_tokens.dart`** — `MiftahColors`, `MiftahGradients`, `MiftahRadii`,
  `MiftahSpacing`, `MiftahType`, `MiftahShadows`.
- **`miftah_theme.dart`** — `MiftahTheme.light` / `.dark`. Pointing
  `MaterialApp.theme` at this re-skins scaffold, app bars, cards, inputs, chips,
  buttons, sheets, snackbars, switches and page transitions with **no screen
  edits**.
- **`miftah_widgets.dart`** — one widget per mockup element:

| Mockup element | Widget |
| --- | --- |
| White list card | `MiftahCard` (`emphasised: true` for the brass border) |
| Gold money card | `MiftahGoldCard` |
| Status pill | `MiftahBadge(label, tone: MiftahTone.…)` |
| Ink CTA | `MiftahButton` |
| Gold CTA | `MiftahGoldButton` |
| Secondary / destructive | `MiftahOutlineButton(tone: MiftahTone.danger)` |
| Filter chip | `MiftahFilterChip(selected:, onDark:)` |
| Leading rounded-square icon | `MiftahIconTile(icon, tone:)` |
| "AMENITIES" label | `MiftahSectionLabel` |
| Cheque / occupancy bar | `MiftahProgress(value:, onGold:)` |
| Bottom sheets | `showMiftahSheet(...)` |
| Empty panel | `MiftahEmptyState` |
| Bottom navigation | `MiftahNavBar` |

They are standalone — they do not import `AppColors`, `AppTheme`,
`context.miftah` or `MiftahGradients` from the existing core, because
`packages/rentaxis_core` was not available when they were written. If you prefer
zero import churn across screens, alias the old names onto the new tokens inside
`rentaxis_core.dart` rather than editing every screen.

## Implementation order

1. **Theme swap.** Copy `dart/` into `rentaxis_core/lib/ui/`, export it, set
   `theme: MiftahTheme.light, darkTheme: MiftahTheme.dark` in all three
   `app.dart` files. Run all three apps and look — roughly 60 % of the redesign
   lands here.
2. **`shell_screen.dart` × 3.** Replace `_FrostedBottomNav` with `MiftahNavBar`.
   Remove `extendBody: true`, the scrim `LinearGradient`, the `BackdropFilter`,
   and every `AppInsets.bottomNav(context)` bottom padding — the new bar is
   solid, so content no longer has to clear a floating pill. Replace the ink
   `AppBar` carrying the logo with a plain themed `AppBar`; the logo moves into
   the Home screen body only.
3. **Delete the dark chrome headers.** Nearly every screen currently opens with
   a `Container(color: AppColors.navyDark)` holding a tracked-uppercase Cinzel
   title. Replace with a themed `AppBar`, and where the screen has a headline
   money figure, a `MiftahGoldCard` as the first body item. Ink headers survive
   in exactly five places: resident ticket detail, meeting detail, profile;
   manager lease detail, finance, queue; security home.
4. **Font call sites.** Search for `GoogleFonts.cinzel` and
   `GoogleFonts.josefinSans` and replace with `MiftahType.*`. Mechanical, and
   where most of the dated feeling actually lives. Keep every `context.isAr`
   branch — Arabic must stay on Noto Naskh with zero letter-spacing.
5. **Screen by screen**, highest traffic first: renter `home_screen` →
   `payments_screen` → security `home_screen` / `result_screen` → manager
   `dashboard_screen` → the rest as touched.

## Constraints to respect

- **Bilingual EN/AR throughout.** Every screen has an `_L` string class; keep
  the pattern. Never apply `letterSpacing` to Arabic. Use `EdgeInsetsDirectional`
  and `AlignmentDirectional`, never `left`/`right`.
- **Light and dark mode** both ship. Use theme-derived colours, not the raw
  `MiftahColors` constants, anywhere a screen renders in both modes. The
  deliberately mode-independent surfaces are: the gate-pass screen, the security
  verdict screens, and the ink detail headers.
- **Roles gate content** in the manager app (`TENANT_ADMIN` / `SUPER_ADMIN` vs.
  `PROPERTY_MANAGER`). Keep every existing role check — Staff, Vendors, Finance
  and property creation stay hidden rather than 403-ing.
- Do not reduce the security app's touch targets or type sizes for visual
  tidiness. They are sized for gloved hands and daylight.

## Assets

- `designs/assets/logo_horizontal.png` — gold serif "MIFTAH" wordmark,
  transparent background. Used in EN.
- `designs/assets/logo_mark.png` — gold Arabic "مفتاح" wordmark. Used in AR, and
  inside the gate-pass QR.
- `designs/assets/logo.png` — square mark.

These are the existing app assets, copied unchanged from
`apps/manager/assets/`. No new assets were created.

Icons in the mockups are **Material Symbols Rounded** — the same set the Flutter
apps already use via `Icons.*`. Every glyph name in the HTML maps directly to a
Flutter icon (`qr_code_2` → `Icons.qr_code_2`, `how_to_reg` → `Icons.how_to_reg`,
and so on). No custom iconography was introduced.

## Files in this bundle

```
designs/Resident App.dc.html    25 screens
designs/Manager App.dc.html     15 screens
designs/Security App.dc.html    10 screens
designs/support.js              runtime the HTML files need — keep alongside them
designs/assets/                 logos referenced by the HTML
dart/miftah_tokens.dart         colours, type, radii, spacing
dart/miftah_theme.dart          MiftahTheme.light / .dark
dart/miftah_widgets.dart        the component set
```

Open the three `.dc.html` files in a browser to view the designs. They must sit
next to `support.js` and `assets/` to render.

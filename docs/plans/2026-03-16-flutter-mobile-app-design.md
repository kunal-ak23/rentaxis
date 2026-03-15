# RentAxis Flutter Mobile App — Design Document

**Date:** 2026-03-16
**Status:** Approved

## Overview

Two separate Flutter mobile apps for RentAxis property management platform:
- **Renter App** — Leases, payments, maintenance tickets, notifications
- **PM/Admin App** — Full property management, payment collection, ticket management, finance

Both apps share a common core package and reuse all existing backend endpoints (152+). Target platforms: Android + iOS.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| App strategy | Two separate apps | Tailored UX per role, smaller bundles |
| Push notifications | Firebase Cloud Messaging (FCM) | Free, battle-tested, backend already has DeviceToken entity |
| Payment gateway | Razorpay Flutter SDK | Official SDK, same backend endpoints, zero backend changes |
| State management | Riverpod | Modern, compile-safe, great for shared packages |
| Target platforms | Android + iOS | Flutter cross-platform, Dubai has ~40-50% iPhone share |
| Monorepo tool | Melos | Industry standard for Flutter workspaces |

## Architecture

### Monorepo Structure
```
mobile/
├── packages/
│   └── rentaxis_core/          # Shared: API client, models, auth, theme, i18n
├── apps/
│   ├── renter/                 # Renter app (4 tabs: Home, Pay, Help, More)
│   └── manager/                # PM/Admin app (5 tabs: Home, Props, Tickets, Pay, More)
└── melos.yaml
```

### Key Dependencies
- dio, flutter_riverpod, firebase_messaging, razorpay_flutter
- image_picker, file_picker, permission_handler
- flutter_localizations (EN/AR with RTL), go_router
- flutter_secure_storage, cached_network_image

### Design System
Matches web app luxury theme: Teal primary (#0F766E), Gold accent (#C8A951), Dark navy app bars (#0F1B2D), Warm white backgrounds (#FAFAF8).

### Permissions
- Camera (ticket photos, cheque scanning)
- Photo library (gallery uploads)
- Notifications (FCM push)
- Requested at point of use, not on app launch

### Backend Changes
- Add Firebase Admin SDK to Spring Boot backend
- Add FCM push sending to existing NotificationService.notify()
- Add FIREBASE_SERVICE_ACCOUNT_PATH env var
- No API endpoint changes needed

## Renter App Screens
Login, My Leases (home), Payments (upcoming/history + Razorpay checkout), Tickets (create with camera/gallery, chat replies, OTP closure, satisfaction rating), Notifications, Profile, Language toggle

## PM/Admin App Screens
Login, Dashboard (KPIs + alerts + activity), Properties (list/detail/units), Tickets (manage + assign + close with OTP), Payments (collect/deposit/clear/bounce cheques + cheque photo capture), Renters, Leases (full CRUD + Kanban), Finance (accounts, transactions, reports), Staff, Settings, Profile

## Push Notification Deep Links
Each notification type maps to a specific screen in the appropriate app. FCM data payload includes referenceType + referenceId for routing.

## CI/CD
GitHub Actions: test → build APK/AAB + IPA → deploy to Play Store internal track + TestFlight

# Renter App Cheque-Centric Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the renter-facing payments surfaces (mobile Flutter app + web Next.js renter portal) to be cheque-centric and informational only — removing the online-pay flow, adding a "Next cheque" hero, and showing `Deposited on {date}` / `Bounced on {date} · {reason}` subtitles.

**Architecture:** Surgical. Two new nullable fields on `RenterPaymentScheduleDTO` (`statusChangedAt`, `failureReason`). One screen deletion + one route removal on mobile. One CTA removal + one hero card on web. Backend online-pay plumbing stays; only the renter-facing entry points go away.

**Tech Stack:** Java 21 + Spring Boot 4 + JPA (backend); Flutter + Riverpod + GoRouter (mobile); Next.js 16 + TypeScript + next-intl (web).

**Spec:** `docs/superpowers/specs/2026-05-19-renter-app-cheque-redesign-design.md`

---

## Task 1: Add `statusChangedAt` + `failureReason` to RenterPaymentScheduleDTO

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/RenterPaymentScheduleDTO.java`

- [ ] **Step 1: Add the two nullable fields**

Edit the record class so it ends with two new fields. The final file should read:

```java
package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class RenterPaymentScheduleDTO {
    private UUID id;
    private int installmentNumber;
    private String dueDate;
    private BigDecimal amount;
    private String status;
    private String propertyName;
    private String unitIdentifier;
    private String renterName;
    private UUID leaseId;
    private BigDecimal penaltyAmount;
    private BigDecimal totalPayable;
    private int daysOverdue;
    private int gracePeriodDays;
    private String paymentMethod;
    /** ISO instant when the schedule last changed status. Null until it leaves PENDING. */
    private String statusChangedAt;
    /** Name of {@link com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason} for BOUNCED rows. */
    private String failureReason;
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `cd backend && ./gradlew compileJava 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/RenterPaymentScheduleDTO.java
git commit -m "feat(renter): add statusChangedAt + failureReason to renter schedule DTO"
```

---

## Task 2: Populate the new DTO fields in OnlinePaymentService

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/OnlinePaymentService.java` (around the mapper inside `getMyPayments`, lines ~75–100)

- [ ] **Step 1: Wire the new fields**

After the existing `dto.setTotalPayable(...)` line, append:

```java
                dto.setStatusChangedAt(schedule.getStatusChangedAt() != null
                        ? schedule.getStatusChangedAt().toString()
                        : null);
                dto.setFailureReason(schedule.getFailureReason() != null
                        ? schedule.getFailureReason().name()
                        : null);
```

The block inside the `for (PaymentSchedule schedule : schedules)` loop should now finish with:

```java
                dto.setPenaltyAmount(penalty);
                dto.setDaysOverdue(daysOverdue);
                dto.setTotalPayable(schedule.getAmount().add(penalty));

                dto.setStatusChangedAt(schedule.getStatusChangedAt() != null
                        ? schedule.getStatusChangedAt().toString()
                        : null);
                dto.setFailureReason(schedule.getFailureReason() != null
                        ? schedule.getFailureReason().name()
                        : null);

                result.add(dto);
            }
```

- [ ] **Step 2: Compile**

Run: `cd backend && ./gradlew compileJava 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/OnlinePaymentService.java
git commit -m "feat(renter): populate statusChangedAt + failureReason in my-payments DTO"
```

---

## Task 3: Remove `/payments/pay` mobile route + delete `pay_rent_screen.dart`

**Files:**
- Modify: `mobile/apps/renter/lib/router.dart`
- Delete: `mobile/apps/renter/lib/screens/pay_rent_screen.dart`

- [ ] **Step 1: Remove the import line in `router.dart`**

Find and delete the line that reads:

```dart
import 'screens/pay_rent_screen.dart';
```

- [ ] **Step 2: Remove the `/payments/pay` sub-route**

Inside `router.dart`, the `/payments` GoRoute currently has a nested route:

```dart
          GoRoute(
            path: '/payments',
            builder: (context, state) => const PaymentsScreen(),
            routes: [
              GoRoute(
                path: 'pay',
                builder: (context, state) => PayRentScreen(
                  paymentId: state.uri.queryParameters['paymentId'],
                ),
              ),
            ],
          ),
```

Replace with:

```dart
          GoRoute(
            path: '/payments',
            builder: (context, state) => const PaymentsScreen(),
          ),
```

- [ ] **Step 3: Delete the screen file**

Run: `rm mobile/apps/renter/lib/screens/pay_rent_screen.dart`

- [ ] **Step 4: Verify the app still analyzes**

Run: `cd mobile/apps/renter && flutter analyze 2>&1 | tail -10`
Expected: no errors. Warnings about unused imports elsewhere are fine.

- [ ] **Step 5: Commit**

```bash
git add mobile/apps/renter/lib/router.dart mobile/apps/renter/lib/screens/pay_rent_screen.dart
git commit -m "refactor(renter-mobile): remove online-pay screen and route"
```

---

## Task 4: Drop the tap-to-pay handler on the cheque card

**Files:**
- Modify: `mobile/apps/renter/lib/screens/payments_screen.dart` (the `_ChequeCard.build` method, around lines 305–402)

- [ ] **Step 1: Replace the InkWell + onTap with a non-interactive container**

Locate the start of the build method:

```dart
    return InkWell(
      onTap: () {
        // PENDING / OVERDUE → open Pay Rent for this payment with the
        // tapped paymentId pre-selected. Other statuses are read-only.
        if (status == 'PENDING' || status == 'OVERDUE') {
          final id = payment['id']?.toString();
          context.push(
              id != null ? '/payments/pay?paymentId=$id' : '/payments/pay');
        }
      },
      borderRadius: BorderRadius.circular(14),
      child: Container(
```

Replace with:

```dart
    return Container(
```

…and delete the matching closing `)` for the `InkWell(` later (the file ends `_ChequeCard.build` with a single `);` after the outermost `Container`; the InkWell wrapper goes away).

After the edit, the method body should open with:

```dart
  @override
  Widget build(BuildContext context) {
    final status = payment['status']?.toString() ?? 'PENDING';
    final amount = (payment['amount'] ?? 0) as num;
    final n = payment['installmentNumber'];
    final dueRaw = payment['dueDate']?.toString();
    final cheque = payment['chequeNumber']?.toString();
    final dueLabel = _formatDate(dueRaw);

    final accent = _accentFor(status);

    return Container(
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(14),
      ),
      child: IntrinsicHeight(
```

- [ ] **Step 2: Remove the unused `go_router` import if nothing else in this file uses `context.push`**

Run: `grep -n "context.push\|context.go" mobile/apps/renter/lib/screens/payments_screen.dart`
If no matches remain, delete this line near the top of the file:

```dart
import 'package:go_router/go_router.dart';
```

- [ ] **Step 3: Run flutter analyze**

Run: `cd mobile/apps/renter && flutter analyze lib/screens/payments_screen.dart 2>&1 | tail -10`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add mobile/apps/renter/lib/screens/payments_screen.dart
git commit -m "refactor(renter-mobile): make cheque card non-interactive"
```

---

## Task 5: Add status-specific subtitle to the cheque card

**Files:**
- Modify: `mobile/apps/renter/lib/screens/payments_screen.dart` (extend `_ChequeCard` with a subtitle line)

- [ ] **Step 1: Add a helper that returns the subtitle string**

Inside `_ChequeCard` (after `_formatDate`), add:

```dart
  /// Status-specific subtitle line. Empty string → don't render.
  String _subtitleFor(String status, Map<String, dynamic> payment) {
    final raw = payment['statusChangedAt']?.toString();
    final formatted = _formatDate(raw);
    final reason = payment['failureReason']?.toString();
    switch (status) {
      case 'COLLECTED':
        return formatted.isEmpty ? 'Collected' : 'Collected on $formatted';
      case 'DEPOSITED':
        return formatted.isEmpty ? 'Deposited' : 'Deposited on $formatted';
      case 'BOUNCED':
        final head = formatted.isEmpty ? 'Bounced' : 'Bounced on $formatted';
        return reason == null || reason.isEmpty ? head : '$head · $reason';
      case 'OVERDUE':
        return dueLabel.isEmpty ? 'Overdue' : 'Overdue since $dueLabel';
      default:
        return '';
    }
  }
```

Note: `dueLabel` is a local in `build`. Move it into a method parameter:

```dart
  String _subtitleFor(String status, Map<String, dynamic> payment, String dueLabel) {
    ...
  }
```

- [ ] **Step 2: Render the subtitle below the "Due …" line in `build`**

In the `Column` inside the `Padding(... Row(... Expanded(... child: Column(...)))` section, after the existing `Text([if (dueLabel.isNotEmpty) 'Due $dueLabel', ...].join(' · '), ...)` block, append:

```dart
                            Builder(builder: (_) {
                              final sub = _subtitleFor(status, payment, dueLabel);
                              if (sub.isEmpty) return const SizedBox.shrink();
                              return Padding(
                                padding: const EdgeInsets.only(top: 2),
                                child: Text(
                                  sub,
                                  style: GoogleFonts.inter(
                                    fontSize: 11,
                                    color: AppColors.textMuted,
                                    fontStyle: FontStyle.italic,
                                  ),
                                ),
                              );
                            }),
```

- [ ] **Step 3: Run flutter analyze**

Run: `cd mobile/apps/renter && flutter analyze lib/screens/payments_screen.dart 2>&1 | tail -10`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add mobile/apps/renter/lib/screens/payments_screen.dart
git commit -m "feat(renter-mobile): show deposited/bounced subtitle on cheque cards"
```

---

## Task 6: Add `_NextChequeHero` widget at top of payments screen

**Files:**
- Modify: `mobile/apps/renter/lib/screens/payments_screen.dart` (insert a new widget, render it in the main `ListView`)

- [ ] **Step 1: Add the hero widget after `_ProgressCard`**

Append (still within the same file) at the bottom of the file, just above `_ChequesShimmer`:

```dart
class _NextChequeHero extends StatelessWidget {
  final AsyncValue<List<dynamic>> paymentsAsync;
  const _NextChequeHero({required this.paymentsAsync});

  @override
  Widget build(BuildContext context) {
    return paymentsAsync.when(
      loading: () => const SizedBox.shrink(),
      error: (_, __) => const SizedBox.shrink(),
      data: (payments) {
        final next = _pickNext(payments);
        if (next == null) {
          return _buildEmpty(context);
        }
        return _buildHero(context, next);
      },
    );
  }

  /// OVERDUE earliest-due first, else PENDING earliest-due, else null.
  Map<String, dynamic>? _pickNext(List<dynamic> payments) {
    final candidates = payments
        .whereType<Map<String, dynamic>>()
        .where((p) {
          final s = p['status']?.toString();
          return s == 'PENDING' || s == 'OVERDUE';
        })
        .toList();
    if (candidates.isEmpty) return null;

    int statusRank(String? s) => s == 'OVERDUE' ? 0 : 1;
    candidates.sort((a, b) {
      final ra = statusRank(a['status']?.toString());
      final rb = statusRank(b['status']?.toString());
      if (ra != rb) return ra.compareTo(rb);
      final ad = DateTime.tryParse(a['dueDate']?.toString() ?? '') ?? DateTime(2100);
      final bd = DateTime.tryParse(b['dueDate']?.toString() ?? '') ?? DateTime(2100);
      final c = ad.compareTo(bd);
      if (c != 0) return c;
      final ai = (a['installmentNumber'] ?? 0) as num;
      final bi = (b['installmentNumber'] ?? 0) as num;
      return ai.compareTo(bi);
    });
    return candidates.first;
  }

  Widget _buildEmpty(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.surface,
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          const Icon(Icons.check_circle_outline, color: AppColors.success),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              'All caught up — no cheques due right now.',
              style: GoogleFonts.inter(
                fontSize: 13,
                color: AppColors.textPrimary,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHero(BuildContext context, Map<String, dynamic> p) {
    final amount = (p['amount'] ?? 0) as num;
    final dueRaw = p['dueDate']?.toString();
    final dueLabel = _formatDate(dueRaw);
    final cheque = p['chequeNumber']?.toString();
    final property = p['propertyName']?.toString();
    final unit = p['unitIdentifier']?.toString();
    final installment = p['installmentNumber'];
    final isOverdue = p['status']?.toString() == 'OVERDUE';

    return Container(
      padding: const EdgeInsets.fromLTRB(18, 16, 18, 16),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: isOverdue
              ? [AppColors.dangerLight, AppColors.surface]
              : [AppColors.accentLight, AppColors.surface],
        ),
        border: Border.all(color: AppColors.border),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            isOverdue ? 'NEXT CHEQUE · OVERDUE' : 'NEXT CHEQUE DUE',
            style: GoogleFonts.inter(
              fontSize: 10.5,
              fontWeight: FontWeight.w700,
              letterSpacing: 0.7,
              color: isOverdue ? AppColors.danger : AppColors.textMuted,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            'AED ${NumberFormat('#,##0').format(amount)}',
            style: GoogleFonts.sourceSerif4(
              fontSize: 28,
              fontWeight: FontWeight.w600,
              color: AppColors.textPrimary,
              letterSpacing: -0.4,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            [
              if (dueLabel.isNotEmpty) 'Due $dueLabel',
              if (installment != null) 'Cheque $installment',
              if (cheque != null && cheque.isNotEmpty) cheque,
            ].join(' · '),
            style: GoogleFonts.inter(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
          if (property != null || unit != null) ...[
            const SizedBox(height: 2),
            Text(
              [property, unit].whereType<String>().where((s) => s.isNotEmpty).join(' — '),
              style: GoogleFonts.inter(
                fontSize: 11,
                color: AppColors.textMuted,
              ),
            ),
          ],
        ],
      ),
    );
  }

  String _formatDate(String? iso) {
    if (iso == null || iso.isEmpty) return '';
    final dt = DateTime.tryParse(iso);
    if (dt == null) return '';
    return DateFormat('d MMM yyyy').format(dt);
  }
}
```

- [ ] **Step 2: Mount it in the main `ListView`**

In `PaymentsScreen.build`, the `ListView` children currently are:

```dart
            children: [
              _Header(leasesAsync: leasesAsync),
              const SizedBox(height: 16),
              _ProgressCard(paymentsAsync: paymentsAsync),
              const SizedBox(height: 18),
              paymentsAsync.when(...),
            ],
```

Insert the hero + spacing between `_Header` and `_ProgressCard`:

```dart
            children: [
              _Header(leasesAsync: leasesAsync),
              const SizedBox(height: 14),
              _NextChequeHero(paymentsAsync: paymentsAsync),
              const SizedBox(height: 16),
              _ProgressCard(paymentsAsync: paymentsAsync),
              const SizedBox(height: 18),
              paymentsAsync.when(...),
            ],
```

- [ ] **Step 3: Flutter analyze**

Run: `cd mobile/apps/renter && flutter analyze lib/screens/payments_screen.dart 2>&1 | tail -10`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add mobile/apps/renter/lib/screens/payments_screen.dart
git commit -m "feat(renter-mobile): add next-cheque hero on payments screen"
```

---

## Task 7: Strip online-pay surface from the web renter portal

**Files:**
- Modify: `web/src/app/[locale]/dashboard/renter-portal/payments/page.tsx`

- [ ] **Step 1: Remove Razorpay + gatewayConfig state and effects**

Delete these blocks from the file:

1. The `declare global { interface Window { Razorpay: any; } }` block near the top.
2. The `GatewayConfig` type alias.
3. State hooks: `const [gatewayConfig, setGatewayConfig] = ...` and `const [processingPaymentId, setProcessingPaymentId] = ...`.
4. The `loadRazorpayScript`, `handlePayNow`, `fetchGatewayConfig`, and any related effect calls.
5. The `useSession()` import + call if it is now unused.

After the changes, the imports at the top should be:

```tsx
"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import {
    Calendar,
    DollarSign,
    CheckCircle,
    XCircle,
    AlertTriangle,
    Clock,
    AlertCircle,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrencyCompact } from "@/lib/format";
```

(`CreditCard`, `Loader2`, `Download` may be unused after the surgery — remove them too if so. Run `grep -n CreditCard web/src/app/\[locale\]/dashboard/renter-portal/payments/page.tsx` after edits and prune any that no longer appear.)

- [ ] **Step 2: Replace the Pay Now button block**

Replace the existing `<div className="flex gap-3 border-t border-border pt-4"> ... </div>` block that contains the `handlePayNow` button with a non-interactive status line (or remove entirely if there's nothing else inside that div).

Concretely, delete this block:

```tsx
                                <div className="flex gap-3 border-t border-border pt-4">
                                    {isOnlinePending ? (
                                        ...
                                    ) : (
                                        <button onClick={() => handlePayNow(payment)} ...>
                                            ...
                                            {t("payNow")}
                                        </button>
                                    )}
                                </div>
```

- [ ] **Step 3: Type-check**

Run: `cd web && npx tsc --noEmit 2>&1 | tail -10`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add web/src/app/[locale]/dashboard/renter-portal/payments/page.tsx
git commit -m "refactor(renter-web): remove Razorpay pay-now flow from renter payments"
```

---

## Task 8: Add NextChequeHero + status subtitle to the web renter portal

**Files:**
- Modify: `web/src/app/[locale]/dashboard/renter-portal/payments/page.tsx`
- Modify: `web/messages/en.json`
- Modify: `web/messages/ar.json`

- [ ] **Step 1: Add i18n keys under `OnlinePayments` (or rename to `RenterPayments` if you wish — keep the existing namespace to avoid wider edits)**

In `web/messages/en.json`, inside the namespace already used by this page (search for `payNow` to find it), add:

```json
    "nextChequeDue": "Next cheque due",
    "nextChequeOverdue": "Next cheque · Overdue",
    "allCaughtUp": "All caught up — no cheques due right now.",
    "depositedOn": "Deposited on {date}",
    "collectedOn": "Collected on {date}",
    "bouncedOn": "Bounced on {date}",
    "overdueSince": "Overdue since {date}",
    "cheque": "Cheque {n}"
```

In `web/messages/ar.json`, in the same namespace:

```json
    "nextChequeDue": "الشيك القادم",
    "nextChequeOverdue": "الشيك القادم · متأخر",
    "allCaughtUp": "كل شيء على ما يرام — لا توجد شيكات مستحقة حالياً.",
    "depositedOn": "تم الإيداع في {date}",
    "collectedOn": "تم الاستلام في {date}",
    "bouncedOn": "ارتدّ الشيك في {date}",
    "overdueSince": "متأخر منذ {date}",
    "cheque": "شيك {n}"
```

- [ ] **Step 2: Extend the `Payment` type**

In `page.tsx`, expand the `Payment` type to include the two new fields:

```tsx
type Payment = {
    id: string;
    installmentNumber: number;
    dueDate: string;
    amount: number;
    status: string;
    chequeNumber: string | null;
    propertyName: string;
    unitIdentifier: string;
    renterName: string;
    leaseId: string;
    penaltyAmount: number;
    totalPayable: number;
    daysOverdue: number;
    gracePeriodDays: number;
    statusChangedAt: string | null;
    failureReason: string | null;
};
```

- [ ] **Step 3: Add a `NextChequeHero` component above the default export**

Above `export default function RenterPaymentsPage()`:

```tsx
function pickNextCheque(payments: Payment[]): Payment | null {
    const candidates = payments.filter(p => p.status === "PENDING" || p.status === "OVERDUE");
    if (candidates.length === 0) return null;
    const rank = (s: string) => (s === "OVERDUE" ? 0 : 1);
    candidates.sort((a, b) => {
        const r = rank(a.status) - rank(b.status);
        if (r !== 0) return r;
        const d = new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime();
        if (d !== 0) return d;
        return a.installmentNumber - b.installmentNumber;
    });
    return candidates[0];
}

function formatDate(iso: string | null | undefined, locale: string): string {
    if (!iso) return "";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return d.toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB", {
        day: "numeric", month: "short", year: "numeric",
    });
}

function NextChequeHero({ payments, t, locale }: { payments: Payment[]; t: (k: string, v?: Record<string, string | number>) => string; locale: string }) {
    const next = pickNextCheque(payments);
    if (!next) {
        return (
            <div className="bg-surface border border-border rounded-2xl p-5 mb-6 flex items-center gap-3">
                <CheckCircle size={20} className="text-success" />
                <p className="text-sm text-foreground">{t("allCaughtUp")}</p>
            </div>
        );
    }
    const isOverdue = next.status === "OVERDUE";
    return (
        <div className={cn(
            "rounded-2xl p-5 mb-6 border",
            isOverdue ? "bg-error/5 border-error/20" : "bg-accent/5 border-accent/20"
        )}>
            <p className={cn(
                "text-[10px] font-bold tracking-widest uppercase mb-2",
                isOverdue ? "text-error" : "text-muted"
            )}>
                {isOverdue ? t("nextChequeOverdue") : t("nextChequeDue")}
            </p>
            <p className="text-2xl font-bold text-foreground mb-1">
                AED {next.amount.toLocaleString()}
            </p>
            <p className="text-xs text-muted">
                {[
                    `${t("dueDate")}: ${formatDate(next.dueDate, locale)}`,
                    t("cheque", { n: next.installmentNumber }),
                    next.chequeNumber,
                    next.propertyName,
                    next.unitIdentifier,
                ].filter(Boolean).join(" · ")}
            </p>
        </div>
    );
}
```

The existing `OnlinePayments` namespace already exposes `dueDate`. The `cheque` key is new and added in Step 1.

- [ ] **Step 4: Render the hero**

In the JSX inside `RenterPaymentsPage`, immediately above the existing `payments.length > 0 ? ( ... )` ternary (or just above the section header for the list), insert:

```tsx
                {!loading && payments.length > 0 && (
                    <NextChequeHero payments={payments} t={t} locale={locale} />
                )}
```

Add `const locale = useLocale();` near the top with the other hooks. Import `useLocale`:

```tsx
import { useTranslations, useLocale } from "next-intl";
```

- [ ] **Step 5: Render the status subtitle on each card**

Find the row that renders the cheque amount + due date inside the `payments.map(...)`. Below the existing status/penalty block, add:

```tsx
                                {(payment.status === "DEPOSITED" || payment.status === "COLLECTED" || payment.status === "BOUNCED" || payment.status === "OVERDUE") && (
                                    <p className="text-[11px] italic text-muted mt-1">
                                        {payment.status === "DEPOSITED" && t("depositedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                        {payment.status === "COLLECTED" && t("collectedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                        {payment.status === "BOUNCED" && (
                                            <>
                                                {t("bouncedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                                {payment.failureReason ? ` · ${payment.failureReason}` : ""}
                                            </>
                                        )}
                                        {payment.status === "OVERDUE" && t("overdueSince", { date: formatDate(payment.dueDate, locale) || "—" })}
                                    </p>
                                )}
```

- [ ] **Step 6: Type-check + tests**

Run:
```
cd web && npx tsc --noEmit 2>&1 | tail
npm test -- --run 2>&1 | tail -5
```
Expected: no TS errors, all existing vitest tests still pass.

- [ ] **Step 7: Commit**

```bash
git add web/src/app/[locale]/dashboard/renter-portal/payments/page.tsx web/messages/en.json web/messages/ar.json
git commit -m "feat(renter-web): next-cheque hero + deposited/bounced subtitle on payments page"
```

---

## Task 9: Add vitest coverage for the web payments page

**Files:**
- Create: `web/src/app/[locale]/dashboard/renter-portal/payments/__tests__/page.test.tsx`

- [ ] **Step 1: Write the test**

Create the file with:

```tsx
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string, vars?: Record<string, string | number>) => {
        if (!vars) return key;
        let out = key;
        for (const [k, v] of Object.entries(vars)) out = out.replaceAll(`{${k}}`, String(v));
        return out;
    },
    useLocale: () => "en",
}));

vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: null }),
}));

import RenterPaymentsPage from "../page";

const samplePayments = [
    {
        id: "p1",
        installmentNumber: 3,
        dueDate: "2026-06-01",
        amount: 12500,
        status: "PENDING",
        chequeNumber: "CHQ-3",
        propertyName: "Belle Vue",
        unitIdentifier: "A-204",
        renterName: "Tenant",
        leaseId: "l1",
        penaltyAmount: 0,
        totalPayable: 12500,
        daysOverdue: 0,
        gracePeriodDays: 5,
        statusChangedAt: null,
        failureReason: null,
    },
    {
        id: "p2",
        installmentNumber: 2,
        dueDate: "2026-04-01",
        amount: 12500,
        status: "DEPOSITED",
        chequeNumber: "CHQ-2",
        propertyName: "Belle Vue",
        unitIdentifier: "A-204",
        renterName: "Tenant",
        leaseId: "l1",
        penaltyAmount: 0,
        totalPayable: 12500,
        daysOverdue: 0,
        gracePeriodDays: 5,
        statusChangedAt: "2026-04-03T10:00:00Z",
        failureReason: null,
    },
];

beforeEach(() => {
    global.fetch = vi.fn(async (url: any) => {
        const u = String(url);
        if (u.includes("my-payments")) {
            return { ok: true, json: async () => samplePayments } as Response;
        }
        return { ok: true, json: async () => null } as Response;
    }) as any;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

describe("RenterPaymentsPage", () => {
    it("renders the next-cheque hero with the earliest PENDING amount", async () => {
        render(<RenterPaymentsPage />);
        await waitFor(() => expect(screen.getByText(/AED 12,500/i)).toBeTruthy());
        expect(screen.queryByText(/payNow/i)).toBeNull();
        expect(screen.getByText(/nextChequeDue/i)).toBeTruthy();
    });

    it("shows the Deposited on subtitle for DEPOSITED rows", async () => {
        render(<RenterPaymentsPage />);
        await waitFor(() => expect(screen.getAllByText(/depositedOn/).length).toBeGreaterThan(0));
    });

    it("never renders a Pay Now button", async () => {
        render(<RenterPaymentsPage />);
        await waitFor(() => expect(screen.getByText(/nextChequeDue/i)).toBeTruthy());
        expect(screen.queryByText(/payNow/i)).toBeNull();
    });
});
```

- [ ] **Step 2: Run the test**

Run: `cd web && npm test -- --run web/src/app 2>&1 | tail -15`
Expected: 3 new tests pass; existing 35 still pass.

- [ ] **Step 3: Commit**

```bash
git add web/src/app/[locale]/dashboard/renter-portal/payments/__tests__/page.test.tsx
git commit -m "test(renter-web): cover next-cheque hero and deposited subtitle"
```

---

## Task 10: Open the PR

**Files:** none

- [ ] **Step 1: Push branch**

```bash
git push -u origin feat/renter-cheque-redesign
```

- [ ] **Step 2: Open PR**

```bash
gh pr create --title "feat(renter): cheque-centric payments redesign" --body "$(cat <<'EOF'
## Summary

Renter app + web renter portal are now informational and cheque-centric. No more online-pay surface.

- Backend DTO gains two nullable fields: \`statusChangedAt\` and \`failureReason\`.
- Mobile: \`pay_rent_screen.dart\` + \`/payments/pay\` route removed. New \`_NextChequeHero\` at the top of the payments screen. Cheque cards are non-interactive and show \`Deposited on {date}\` / \`Bounced on {date} · {reason}\` subtitles.
- Web: Razorpay/Pay-Now CTA removed from \`renter-portal/payments\`. New NextChequeHero card. Status subtitles wired on each row. en + ar i18n keys added.
- Backend \`OnlinePaymentController\` + Razorpay code left in place by design — follow-up PR can remove.

## Spec

\`docs/superpowers/specs/2026-05-19-renter-app-cheque-redesign-design.md\`

## Test plan

- [x] Backend \`./gradlew compileJava\` clean
- [x] Mobile \`flutter analyze\` clean
- [x] Web \`tsc --noEmit\` clean
- [x] Web vitest passes (existing 35 + 3 new)
- [ ] Manual smoke on staging: log in as a renter with PENDING + DEPOSITED + BOUNCED rows, confirm hero, subtitles, no pay button

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Done state

- `pay_rent_screen.dart` no longer exists; `/payments/pay` route gone.
- Mobile payments screen shows: header → next-cheque hero → progress card → cheque list (non-tappable, with status subtitles).
- Web renter portal payments page shows: header → next-cheque hero → cheque cards (no Pay Now button) with status subtitles.
- Penalties screen untouched.
- Home screen untouched.
- All checks green.

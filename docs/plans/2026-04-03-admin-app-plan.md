# RentAxis Admin App Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> **UI Implementation:** Use ui-ux-pro-max skill for all screen implementations.

**Goal:** Evolve the existing Manager app (`mobile/apps/manager`) into a full-featured RentAxis Admin app by adding 9 new core services, 14 new screens, enhancing 4 existing screens, and updating branding/routing.

**Architecture:** Flutter monorepo with shared `rentaxis_core` package. All new services go in the core package. All new screens go in `mobile/apps/manager/lib/screens/`. Existing patterns: Riverpod providers (FutureProvider.autoDispose for data, Provider for services), GoRouter for navigation, ConsumerStatefulWidget for screens with local state, Dio for HTTP. No models — raw `Map<String, dynamic>` and `List<dynamic>` throughout.

**Tech Stack:** Flutter 3.10+, Riverpod 2.6, GoRouter 14.8, Dio 5.7, Material 3, AppTheme (teal/gold/navy, Cinzel+JosefinSans)

**Design Doc:** `docs/plans/2026-04-03-admin-app-design.md`

---

## Phase 1: Core Services (rentaxis_core)

### Task 1: Staff Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/staff_service.dart`

**Step 1: Create staff_service.dart**

```dart
import 'package:dio/dio.dart';

class StaffService {
  final Dio _dio;
  StaffService(this._dio);

  Future<List<dynamic>> getStaff() async {
    final response = await _dio.get('/v1/staff');
    return response.data;
  }

  Future<Map<String, dynamic>> getStaffById(String id) async {
    final response = await _dio.get('/v1/staff/$id');
    return response.data;
  }

  Future<List<dynamic>> getStaffByProperty(String propertyId) async {
    final response = await _dio.get('/v1/staff/by-property/$propertyId');
    return response.data;
  }

  Future<Map<String, dynamic>> createStaff(Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/staff', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> updateStaff(String id, Map<String, dynamic> data) async {
    final response = await _dio.put('/v1/staff/$id', data: data);
    return response.data;
  }

  Future<void> deleteStaff(String id) async {
    await _dio.delete('/v1/staff/$id');
  }
}
```

**Step 2: Verify** — Run `cd mobile && dart analyze packages/rentaxis_core/lib/api/services/staff_service.dart`

**Step 3: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/staff_service.dart && git commit -m "feat: add StaffService to rentaxis_core"`

---

### Task 2: Vendor Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/vendor_service.dart`

**Step 1: Create vendor_service.dart**

```dart
import 'package:dio/dio.dart';

class VendorService {
  final Dio _dio;
  VendorService(this._dio);

  Future<List<dynamic>> getVendors() async {
    final response = await _dio.get('/v1/vendors');
    return response.data;
  }

  Future<Map<String, dynamic>> getVendorById(String id) async {
    final response = await _dio.get('/v1/vendors/$id');
    return response.data;
  }

  Future<Map<String, dynamic>> createVendor(Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/vendors', data: data);
    return response.data;
  }
}
```

**Step 2: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/vendor_service.dart && git commit -m "feat: add VendorService to rentaxis_core"`

---

### Task 3: Bank Account Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/bank_account_service.dart`

**Step 1: Create bank_account_service.dart**

```dart
import 'package:dio/dio.dart';

class BankAccountService {
  final Dio _dio;
  BankAccountService(this._dio);

  Future<List<dynamic>> getBankAccounts() async {
    final response = await _dio.get('/v1/bank-accounts');
    return response.data;
  }

  Future<Map<String, dynamic>> getBankAccountById(String id) async {
    final response = await _dio.get('/v1/bank-accounts/$id');
    return response.data;
  }

  Future<List<dynamic>> getBankAccountsByProperty(String propertyId) async {
    final response = await _dio.get('/v1/bank-accounts/by-property/$propertyId');
    return response.data;
  }
}
```

**Step 2: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/bank_account_service.dart && git commit -m "feat: add BankAccountService to rentaxis_core"`

---

### Task 4: Building Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/building_service.dart`

**Step 1: Create building_service.dart**

```dart
import 'package:dio/dio.dart';

class BuildingService {
  final Dio _dio;
  BuildingService(this._dio);

  Future<List<dynamic>> getBuildingsByProperty(String propertyId) async {
    final response = await _dio.get('/v1/buildings/property/$propertyId');
    return response.data;
  }
}
```

**Step 2: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/building_service.dart && git commit -m "feat: add BuildingService to rentaxis_core"`

---

### Task 5: Settings Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/settings_service.dart`

**Step 1: Create settings_service.dart**

```dart
import 'package:dio/dio.dart';

class SettingsService {
  final Dio _dio;
  SettingsService(this._dio);

  // Rent collection settings
  Future<Map<String, dynamic>?> getRentSettings(String propertyId) async {
    final response = await _dio.get('/v1/rent-settings/$propertyId');
    if (response.statusCode == 204) return null;
    return response.data;
  }

  // Gateway config
  Future<List<dynamic>> getAvailableGateways() async {
    final response = await _dio.get('/v1/gateway-config/gateways');
    return response.data;
  }

  Future<Map<String, dynamic>?> getGatewayConfig() async {
    final response = await _dio.get('/v1/gateway-config');
    if (response.statusCode == 204) return null;
    return response.data;
  }

  // Account mappings
  Future<List<dynamic>> getAccountMappings() async {
    final response = await _dio.get('/v1/finance/account-mappings');
    return response.data;
  }

  Future<List<dynamic>> getTransactionNatures() async {
    final response = await _dio.get('/v1/finance/account-mappings/natures');
    return response.data;
  }
}
```

**Step 2: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/settings_service.dart && git commit -m "feat: add SettingsService to rentaxis_core"`

---

### Task 6: Penalty Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/penalty_service.dart`

**Step 1: Create penalty_service.dart**

```dart
import 'package:dio/dio.dart';

class PenaltyService {
  final Dio _dio;
  PenaltyService(this._dio);

  Future<List<dynamic>> getPenalties(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/penalties');
    return response.data;
  }

  Future<Map<String, dynamic>> waivePenalty(String penaltyId, {String? reason}) async {
    final response = await _dio.put('/v1/penalties/$penaltyId/waive', data: {
      if (reason != null) 'reason': reason,
    });
    return response.data;
  }

  Future<List<dynamic>> recalculatePenalties(String leaseId) async {
    final response = await _dio.post('/v1/leases/$leaseId/penalties/recalculate');
    return response.data;
  }
}
```

**Step 2: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/penalty_service.dart && git commit -m "feat: add PenaltyService to rentaxis_core"`

---

### Task 7: Settlement Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/settlement_service.dart`

**Step 1: Create settlement_service.dart**

```dart
import 'package:dio/dio.dart';

class SettlementService {
  final Dio _dio;
  SettlementService(this._dio);

  Future<Map<String, dynamic>> getSettlementPreview(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/settlement/preview');
    return response.data;
  }

  Future<Map<String, dynamic>> getSettlement(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/settlement');
    return response.data;
  }
}
```

**Step 2: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/settlement_service.dart && git commit -m "feat: add SettlementService to rentaxis_core"`

---

### Task 8: Report Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/report_service.dart`

**Step 1: Create report_service.dart**

```dart
import 'package:dio/dio.dart';

class ReportService {
  final Dio _dio;
  ReportService(this._dio);

  Future<Map<String, dynamic>> getPropertyReport(String propertyId, {String? startDate, String? endDate}) async {
    final response = await _dio.get('/v1/finance/reports/property/$propertyId', queryParameters: {
      if (startDate != null) 'startDate': startDate,
      if (endDate != null) 'endDate': endDate,
    });
    return response.data;
  }

  Future<Map<String, dynamic>> getUnitReport(String unitId, {String? startDate, String? endDate}) async {
    final response = await _dio.get('/v1/finance/reports/unit/$unitId', queryParameters: {
      if (startDate != null) 'startDate': startDate,
      if (endDate != null) 'endDate': endDate,
    });
    return response.data;
  }

  Future<Map<String, dynamic>> getVatReturn({required String startDate, required String endDate}) async {
    final response = await _dio.get('/v1/finance/reports/vat-return', queryParameters: {
      'startDate': startDate,
      'endDate': endDate,
    });
    return response.data;
  }

  Future<List<dynamic>> getVendorLedger(String vendorId, {String? startDate, String? endDate}) async {
    final response = await _dio.get('/v1/finance/ledger/vendor/$vendorId', queryParameters: {
      if (startDate != null) 'startDate': startDate,
      if (endDate != null) 'endDate': endDate,
    });
    return response.data;
  }
}
```

**Step 2: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/report_service.dart && git commit -m "feat: add ReportService to rentaxis_core"`

---

### Task 9: Property Contact Service

**Files:**
- Create: `mobile/packages/rentaxis_core/lib/api/services/property_contact_service.dart`

**Step 1: Create property_contact_service.dart**

```dart
import 'package:dio/dio.dart';

class PropertyContactService {
  final Dio _dio;
  PropertyContactService(this._dio);

  Future<List<dynamic>> getContacts(String propertyId) async {
    final response = await _dio.get('/v1/properties/$propertyId/contacts');
    return response.data;
  }

  Future<Map<String, dynamic>> createContact(String propertyId, Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/properties/$propertyId/contacts', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> updateContact(String propertyId, String contactId, Map<String, dynamic> data) async {
    final response = await _dio.put('/v1/properties/$propertyId/contacts/$contactId', data: data);
    return response.data;
  }

  Future<void> deleteContact(String propertyId, String contactId) async {
    await _dio.delete('/v1/properties/$propertyId/contacts/$contactId');
  }
}
```

**Step 2: Commit** — `git add mobile/packages/rentaxis_core/lib/api/services/property_contact_service.dart && git commit -m "feat: add PropertyContactService to rentaxis_core"`

---

### Task 10: Update Core Package Barrel Export

**Files:**
- Modify: `mobile/packages/rentaxis_core/lib/rentaxis_core.dart`

**Step 1: Add new service exports after line 18 (after `export 'api/services/upload_service.dart';`)**

Add these lines:
```dart
export 'api/services/staff_service.dart';
export 'api/services/vendor_service.dart';
export 'api/services/bank_account_service.dart';
export 'api/services/building_service.dart';
export 'api/services/settings_service.dart';
export 'api/services/penalty_service.dart';
export 'api/services/settlement_service.dart';
export 'api/services/report_service.dart';
export 'api/services/property_contact_service.dart';
```

**Step 2: Verify** — Run `cd mobile/packages/rentaxis_core && dart analyze lib/`

**Step 3: Commit** — `git add mobile/packages/rentaxis_core/lib/rentaxis_core.dart && git commit -m "feat: export new services from rentaxis_core barrel"`

---

## Phase 2: New Screens — Staff & Vendors (People section)

### Task 11: Staff Screen (full CRUD)

**Files:**
- Create: `mobile/apps/manager/lib/screens/staff_screen.dart`

**Context:** Follow the exact pattern from `renters_screen.dart` — ConsumerStatefulWidget, FutureProvider.autoDispose for data, search bar, FAB for create, bottom sheet for form. Staff fields from backend: name, email, phone, role, propertyIds.

**Step 1: Implement staff_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- AppBar with title "Staff"
- Search TextField (same pattern as renters_screen.dart line 38-53)
- FutureProvider.autoDispose fetching `StaffService.getStaff()`
- Provider for `StaffService` (same pattern as renters_screen.dart line 5-8)
- RefreshIndicator with ListView.builder of `_StaffCard` widgets
- Each card shows: avatar initial, name, role badge, email, phone, property assignment count
- FAB → bottom sheet form with: name, email, phone, role dropdown (PROPERTY_MANAGER, TENANT_USER), property multi-select
- EmptyState / ErrorState / loading states (reuse core widgets)
- Delete via swipe or long-press confirm dialog

**Step 2: Verify** — `cd mobile/apps/manager && flutter analyze lib/screens/staff_screen.dart`

**Step 3: Commit** — `git add mobile/apps/manager/lib/screens/staff_screen.dart && git commit -m "feat: add staff management screen"`

---

### Task 12: Staff Detail Screen

**Files:**
- Create: `mobile/apps/manager/lib/screens/staff_detail_screen.dart`

**Context:** Follow pattern from `lease_detail_screen.dart` — ConsumerStatefulWidget with manual state loading, no FutureProvider (because we need multiple API calls and actions).

**Step 1: Implement staff_detail_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- Takes `staffId` as constructor param
- Loads staff detail via `StaffService.getStaffById(staffId)`
- Header card (navy gradient, same as lease_detail line 362-424) showing name, role, email, phone
- Property assignments section — list of assigned property names
- Edit button in AppBar → bottom sheet form pre-filled with current values
- Delete button in PopupMenuButton (AppBar actions)
- Loading/error states

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/staff_detail_screen.dart && git commit -m "feat: add staff detail screen"`

---

### Task 13: Vendors Screen (view + create)

**Files:**
- Create: `mobile/apps/manager/lib/screens/vendors_screen.dart`

**Context:** Same pattern as renters_screen.dart but for vendors. View + create only (no edit/delete on mobile per design).

**Step 1: Implement vendors_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- AppBar with title "Vendors"
- Search bar
- FutureProvider.autoDispose for `VendorService.getVendors()`
- ListView of vendor cards showing: name, contact info, category/type if available
- FAB → create vendor bottom sheet with: name, email, phone, address, TRN (tax registration number)
- Empty/error/loading states

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/vendors_screen.dart && git commit -m "feat: add vendors screen"`

---

### Task 14: Vendor Detail Screen

**Files:**
- Create: `mobile/apps/manager/lib/screens/vendor_detail_screen.dart`

**Context:** Simple read-only detail view.

**Step 1: Implement vendor_detail_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- Takes `vendorId` as constructor param
- Loads via `VendorService.getVendorById(vendorId)`
- Header card with vendor name, type
- Detail rows: email, phone, address, TRN
- Loading/error states

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/vendor_detail_screen.dart && git commit -m "feat: add vendor detail screen"`

---

## Phase 3: New Screens — Finance Section

### Task 15: Bank Accounts Screen (view only)

**Files:**
- Create: `mobile/apps/manager/lib/screens/bank_accounts_screen.dart`

**Step 1: Implement bank_accounts_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- AppBar with title "Bank Accounts"
- FutureProvider.autoDispose for `BankAccountService.getBankAccounts()`
- ListView of bank account cards showing: bank name, account number (masked last 4 digits), IBAN, linked property name
- No create/edit/delete (view only per design)
- Tap card to show full details in a bottom sheet

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/bank_accounts_screen.dart && git commit -m "feat: add bank accounts screen (view only)"`

---

### Task 16: Finance Reports Screen

**Files:**
- Create: `mobile/apps/manager/lib/screens/finance_reports_screen.dart`

**Context:** Hub screen with report type cards. Each card navigates to a report detail view.

**Step 1: Implement finance_reports_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- AppBar with title "Financial Reports"
- Grid of report type cards:
  1. **Organisation Summary** — icon: business, color: primary
  2. **Trial Balance** — icon: balance, color: accent
  3. **VAT Return** — icon: receipt_long, color: info
  4. **Property Report** — icon: apartment, color: success (needs property picker)
  5. **Unit Report** — icon: door_front, color: warning (needs unit picker)
  6. **Vendor Ledger** — icon: store, color: navyDark (needs vendor picker)
- Date range picker at top (start date, end date) shared across all reports
- Tapping a card loads the report data and navigates to report_detail_screen

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/finance_reports_screen.dart && git commit -m "feat: add finance reports hub screen"`

---

### Task 17: Report Detail Screen

**Files:**
- Create: `mobile/apps/manager/lib/screens/report_detail_screen.dart`

**Context:** Renders report data. Different report types have different structures. Use a flexible approach.

**Step 1: Implement report_detail_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- Takes `reportType` (String) and `reportData` (Map<String, dynamic>) as params
- For Organisation/Property/Unit reports: income vs expense summary cards, line items table
- For Trial Balance: debit/credit columns with account rows
- For VAT Return: VAT summary with input/output tax
- For Vendor Ledger: transaction list with running balance
- All amounts formatted with `Formatters.currency()`

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/report_detail_screen.dart && git commit -m "feat: add report detail screen"`

---

## Phase 4: New Screens — Lease Enhancements

### Task 18: Lease Penalties Screen

**Files:**
- Create: `mobile/apps/manager/lib/screens/lease_penalties_screen.dart`

**Step 1: Implement lease_penalties_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- Takes `leaseId` as constructor param
- FutureProvider.autoDispose for `PenaltyService.getPenalties(leaseId)`
- List of penalty cards showing: type, amount, status (active/waived), associated payment info, date
- Waive action on each active penalty — confirm dialog with optional reason text field
- Recalculate button in AppBar actions
- Color coding: active penalties in danger color, waived in muted

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/lease_penalties_screen.dart && git commit -m "feat: add lease penalties screen"`

---

### Task 19: Lease Settlement Screen

**Files:**
- Create: `mobile/apps/manager/lib/screens/lease_settlement_screen.dart`

**Step 1: Implement lease_settlement_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- Takes `leaseId` as constructor param
- Loads settlement preview via `SettlementService.getSettlementPreview(leaseId)`
- Settlement summary card showing: total rent paid, outstanding balance, penalties, security deposit, final settlement amount
- Line items breakdown (each charge/credit as a row)
- Status indicator if settlement already exists
- If already settled, load via `getSettlement()` and show final settlement details

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/lease_settlement_screen.dart && git commit -m "feat: add lease settlement screen"`

---

## Phase 5: New Screens — Settings

### Task 20: Settings Hub Screen

**Files:**
- Create: `mobile/apps/manager/lib/screens/settings_hub_screen.dart`

**Step 1: Implement settings_hub_screen.dart**

Use ui-ux-pro-max skill. Simple menu screen (same pattern as more_screen.dart) with:
- AppBar with title "Settings"
- Three menu items with icons:
  1. Rent Collection Settings → navigates to `/settings/rent`
  2. Payment Gateway → navigates to `/settings/gateway`
  3. Account Mappings → navigates to `/settings/mappings`
- Each item uses the `_MenuItem` pattern from more_screen.dart (icon + label + chevron)

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/settings_hub_screen.dart && git commit -m "feat: add settings hub screen"`

---

### Task 21: Rent Settings Screen (view only)

**Files:**
- Create: `mobile/apps/manager/lib/screens/rent_settings_screen.dart`

**Step 1: Implement rent_settings_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- AppBar with title "Rent Collection Settings"
- Property selector dropdown at top (loads properties from PropertyService)
- When property selected, loads settings via `SettingsService.getRentSettings(propertyId)`
- Display settings as read-only info rows: grace period, penalty rate, payment terms, etc.
- If no settings configured, show EmptyState with message "No rent settings configured for this property"

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/rent_settings_screen.dart && git commit -m "feat: add rent settings screen (view only)"`

---

### Task 22: Gateway Config Screen (view only)

**Files:**
- Create: `mobile/apps/manager/lib/screens/gateway_config_screen.dart`

**Step 1: Implement gateway_config_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- AppBar with title "Payment Gateway"
- Loads gateway config via `SettingsService.getGatewayConfig()` and available gateways via `getAvailableGateways()`
- If configured: show active gateway name, status indicator (green dot = active), masked key info
- If not configured: EmptyState with "No payment gateway configured"
- Available gateways list section showing supported providers

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/gateway_config_screen.dart && git commit -m "feat: add gateway config screen (view only)"`

---

### Task 23: Account Mappings Screen (view only)

**Files:**
- Create: `mobile/apps/manager/lib/screens/account_mappings_screen.dart`

**Step 1: Implement account_mappings_screen.dart**

Use ui-ux-pro-max skill. The screen should have:
- AppBar with title "Account Mappings"
- Loads mappings via `SettingsService.getAccountMappings()`
- List grouped by transaction nature (use `getTransactionNatures()` for categories)
- Each row shows: nature label, mapped account name, account code
- If no mappings: EmptyState

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/account_mappings_screen.dart && git commit -m "feat: add account mappings screen (view only)"`

---

## Phase 6: Enhance Existing Screens

### Task 24: Transform More Screen into Admin Hub

**Files:**
- Modify: `mobile/apps/manager/lib/screens/more_screen.dart`

**Context:** Current more_screen.dart has 3 sections: Management (Renters, Tickets, Finance), Account (Profile, Change Password), Settings (Language, About). Transform it into a richer hub with all new features.

**Step 1: Update more_screen.dart**

Replace the Management and Settings sections. New structure:

**People section:**
- Staff → `/staff`
- Renters → `/renters` (existing)

**Finance section:**
- Accounts & Transactions → `/finance` (existing)
- Bank Accounts → `/bank-accounts`
- Vendors → `/vendors`
- Reports → `/finance-reports`

**Operations section:**
- Tickets → `/tickets` (existing)

**Settings section:**
- Rent Settings → `/settings/rent`
- Payment Gateway → `/settings/gateway`
- Account Mappings → `/settings/mappings`

**Account section:**
- Profile → `/profile` (existing)
- Change Password → `/profile` (existing)
- Language (existing placeholder)
- About → update name to "RentAxis Admin"

Keep existing user profile card at top and logout button at bottom unchanged.

**Step 2: Commit** — `git add mobile/apps/manager/lib/screens/more_screen.dart && git commit -m "feat: transform more screen into admin hub with all features"`

---

### Task 25: Enhance Property Detail Screen

**Files:**
- Modify: `mobile/apps/manager/lib/screens/property_detail_screen.dart`

**Context:** Add two new sections to the property detail: Contacts and Buildings. Both read from API.

**Step 1: Read current file** — Read `mobile/apps/manager/lib/screens/property_detail_screen.dart` in full

**Step 2: Add service providers** — Add providers for `PropertyContactService` and `BuildingService` at top of file (same pattern as existing service providers)

**Step 3: Add data loading** — In `_loadData()` (or equivalent), also load contacts via `PropertyContactService.getContacts(propertyId)` and buildings via `BuildingService.getBuildingsByProperty(propertyId)`

**Step 4: Add Contacts section** — After the units grid, add a "Contacts" section showing contact cards (name, role, phone, email). Add a FAB or "Add Contact" button that opens a bottom sheet form.

**Step 5: Add Buildings section** — After contacts, add a "Buildings" section showing building names (read-only). Simple list of building name cards.

**Step 6: Commit** — `git add mobile/apps/manager/lib/screens/property_detail_screen.dart && git commit -m "feat: add contacts and buildings to property detail"`

---

### Task 26: Enhance Lease Detail Screen

**Files:**
- Modify: `mobile/apps/manager/lib/screens/lease_detail_screen.dart`

**Context:** Add navigation links to the new penalty and settlement sub-screens. Current file is at 679 lines. Add action buttons/sections after the existing attachments section.

**Step 1: Read current file** — Already read above (lease_detail_screen.dart)

**Step 2: Add navigation sections** — After `_buildAttachments()` (line 350), add:

```dart
const SizedBox(height: 20),
_buildPenaltiesSection(),
const SizedBox(height: 20),
_buildSettlementSection(),
```

**Step 3: Implement `_buildPenaltiesSection()`** — A card with icon, "Penalties" title, and a "View All" button that navigates to `/leases/${widget.leaseId}/penalties`. Show penalty count if available.

**Step 4: Implement `_buildSettlementSection()`** — Only show for ACTIVE or TERMINATED leases. A card with "Settlement" title and "View Settlement" button navigating to `/leases/${widget.leaseId}/settlement`.

**Step 5: Commit** — `git add mobile/apps/manager/lib/screens/lease_detail_screen.dart && git commit -m "feat: add penalty and settlement links to lease detail"`

---

### Task 27: Update Dashboard Quick Actions

**Files:**
- Modify: `mobile/apps/manager/lib/screens/dashboard_screen.dart`

**Context:** Add quick action shortcuts to new features (Staff, Reports) in the dashboard quick actions section.

**Step 1: Read current dashboard_screen.dart**

**Step 2: Add new quick action items** — In the quick actions grid, add:
- "Staff" with `Icons.badge_outlined` → navigates to `/staff`
- "Reports" with `Icons.assessment_outlined` → navigates to `/finance-reports`

**Step 3: Commit** — `git add mobile/apps/manager/lib/screens/dashboard_screen.dart && git commit -m "feat: add staff and reports quick actions to dashboard"`

---

## Phase 7: Router & Branding

### Task 28: Update Router with All New Routes

**Files:**
- Modify: `mobile/apps/manager/lib/router.dart`

**Context:** Current router has routes for: /, /properties, /leases, /payments, /tickets, /renters, /finance, /more, /profile. Need to add all new routes.

**Step 1: Add imports** — Add imports for all new screen files at top of router.dart

**Step 2: Add new routes** inside the ShellRoute's routes list:

```dart
// Staff
GoRoute(
  path: '/staff',
  builder: (context, state) => const StaffScreen(),
  routes: [
    GoRoute(
      path: ':id',
      builder: (context, state) => StaffDetailScreen(
        staffId: state.pathParameters['id']!,
      ),
    ),
  ],
),
// Vendors
GoRoute(
  path: '/vendors',
  builder: (context, state) => const VendorsScreen(),
  routes: [
    GoRoute(
      path: ':id',
      builder: (context, state) => VendorDetailScreen(
        vendorId: state.pathParameters['id']!,
      ),
    ),
  ],
),
// Bank Accounts
GoRoute(
  path: '/bank-accounts',
  builder: (context, state) => const BankAccountsScreen(),
),
// Finance Reports
GoRoute(
  path: '/finance-reports',
  builder: (context, state) => const FinanceReportsScreen(),
),
// Settings
GoRoute(
  path: '/settings',
  builder: (context, state) => const SettingsHubScreen(),
  routes: [
    GoRoute(
      path: 'rent',
      builder: (context, state) => const RentSettingsScreen(),
    ),
    GoRoute(
      path: 'gateway',
      builder: (context, state) => const GatewayConfigScreen(),
    ),
    GoRoute(
      path: 'mappings',
      builder: (context, state) => const AccountMappingsScreen(),
    ),
  ],
),
```

Also add sub-routes under the existing `/leases/:id` route:
```dart
GoRoute(
  path: 'penalties',
  builder: (context, state) => LeasePenaltiesScreen(
    leaseId: state.pathParameters['id']!,
  ),
),
GoRoute(
  path: 'settlement',
  builder: (context, state) => LeaseSettlementScreen(
    leaseId: state.pathParameters['id']!,
  ),
),
```

**Step 3: Verify** — `cd mobile/apps/manager && flutter analyze`

**Step 4: Commit** — `git add mobile/apps/manager/lib/router.dart && git commit -m "feat: add all new routes for admin app features"`

---

### Task 29: Update Branding

**Files:**
- Modify: `mobile/apps/manager/lib/screens/more_screen.dart` (About dialog name)
- Modify: `mobile/apps/manager/android/app/src/main/AndroidManifest.xml` (app label)
- Modify: `mobile/apps/manager/ios/Runner/Info.plist` (CFBundleDisplayName)

**Step 1: Update About dialog** — In more_screen.dart, change `applicationName: 'RentAxis Manager'` to `applicationName: 'RentAxis Admin'`

**Step 2: Update Android app label** — In AndroidManifest.xml, change `android:label` to "RentAxis Admin"

**Step 3: Update iOS display name** — In Info.plist, change `CFBundleDisplayName` to "RentAxis Admin"

**Step 4: Commit** — `git add -A && git commit -m "feat: rebrand manager app to RentAxis Admin"`

---

## Phase 8: Final Verification

### Task 30: Full Build & Analyze

**Step 1:** Run `cd mobile/packages/rentaxis_core && dart analyze lib/`
**Step 2:** Run `cd mobile/apps/manager && flutter analyze`
**Step 3:** Fix any analysis warnings or errors
**Step 4:** Run `cd mobile/apps/manager && flutter build apk --debug` to verify build
**Step 5:** Final commit if any fixes needed

---

## Summary

| Phase | Tasks | What |
|-------|-------|------|
| 1 | 1-10 | 9 new core services + barrel export update |
| 2 | 11-14 | Staff (2 screens) + Vendors (2 screens) |
| 3 | 15-17 | Bank Accounts + Finance Reports (3 screens) |
| 4 | 18-19 | Lease Penalties + Settlement (2 screens) |
| 5 | 20-23 | Settings hub + 3 settings screens |
| 6 | 24-27 | Enhance 4 existing screens |
| 7 | 28-29 | Router update + branding |
| 8 | 30 | Full build verification |

**Total: 30 tasks, 14 new screens, 9 new services, 4 enhanced screens**

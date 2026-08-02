/// Gate-pass state for the manager app.
///
/// All `autoDispose`: the approvals queue is shared with the guard app, and a
/// pass a guard decided a moment ago must not survive here as a card the manager
/// can still act on. Disposing on the last listener means re-entering a screen
/// re-reads the server.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../gatepass/guard_admin_service.dart';

/// App-local: core has no `/admin/users` wrapper. See [GuardAdminService].
final guardAdminServiceProvider = Provider<GuardAdminService>((ref) {
  final client = ref.watch(apiClientProvider);
  return GuardAdminService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

/// Passes awaiting approval.
///
/// The same `/v1/gatepass/approvals` the guard app reads, scoped server-side by
/// role: a manager gets the **whole tenant's** PENDING_APPROVAL queue, a guard
/// only their assigned properties. No client-side filter re-implements that.
///
/// Rows are `GatePassSummary` — no `qrToken`, no `numericCode`. An approver
/// decides whether a guest may come; they are not handed the credential that
/// admits them. Do not reach for those fields here; they are absent by design.
final approvalsProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(gatePassServiceProvider);
      return _asRows(await service.approvals());
    });

/// The tenant's properties, for the assignment picker.
final propertiesProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(_propertyServiceProvider);
      return _asRows(await service.getProperties());
    });

/// Every SECURITY_GUARD in the tenant.
///
/// `/admin/users` has no role filter, so the filter is here. Matching on the
/// wire string rather than an enum is the house convention (raw maps, no
/// models) and matches what `UserResponseDTO` serializes.
final guardsProvider = FutureProvider.autoDispose<List<Map<String, dynamic>>>((
  ref,
) async {
  final service = ref.watch(guardAdminServiceProvider);
  final users = _asRows(await service.users());
  return users.where((u) => u['role'] == 'SECURITY_GUARD').toList();
});

/// One guard's property posting, readable and writable.
///
/// A notifier rather than a `FutureProvider` because of what `PUT
/// /v1/gatepass/guards/{id}/properties` returns: the **accepted, de-duplicated**
/// list, which is not always the list that was sent. [save] therefore adopts the
/// response as the new state instead of assuming the input echoed — and instead
/// of invalidating and re-fetching, which would spend a round trip to learn what
/// the server just said.
class GuardPropertiesNotifier
    extends AutoDisposeFamilyAsyncNotifier<List<String>, String> {
  @override
  Future<List<String>> build(String userId) async {
    final service = ref.watch(gatePassServiceProvider);
    return _asIds(await service.guardProperties(userId));
  }

  /// Replace-all, matching the endpoint: [propertyIds] is the guard's complete
  /// desired posting, not a delta. Throws on failure — the caller reports it,
  /// and state is left untouched so the UI keeps showing the posting that is
  /// actually in force.
  Future<void> save(List<String> propertyIds) async {
    final service = ref.read(gatePassServiceProvider);
    final accepted = await service.setGuardProperties(arg, propertyIds);
    state = AsyncData(_asIds(accepted));
  }
}

final guardPropertiesProvider = AsyncNotifierProvider.autoDispose
    .family<GuardPropertiesNotifier, List<String>, String>(
      GuardPropertiesNotifier.new,
    );

List<Map<String, dynamic>> _asRows(List<dynamic> rows) =>
    rows.whereType<Map>().map((r) => Map<String, dynamic>.from(r)).toList();

List<String> _asIds(List<dynamic> ids) =>
    ids.map((id) => id.toString()).toList();

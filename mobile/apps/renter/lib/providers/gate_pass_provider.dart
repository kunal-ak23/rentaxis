import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:share_plus/share_plus.dart';

/// The renter's own gate passes, over core's shared [gatePassServiceProvider].
///
/// These are the creator-facing reads, so the rows **carry `qrToken` and
/// `numericCode`** — the gate credential itself. Everything downstream of here
/// is handling a secret; do not log a row wholesale.
///
/// All `autoDispose`: a pass's status changes underneath the renter (a guard
/// scans it, a manager approves it, it expires), so a kept-alive provider would
/// show a stale ACTIVE for a pass that has already been used. Disposing on the
/// last listener means re-entering the screen re-reads the gate.

/// The renter's passes, newest first.
///
/// The ordering is the backend's (`findBy...OrderByCreatedAtDesc`), not re-done
/// here — the same way the guard app trusts the ordering of `expected-today`.
final myPassesProvider = FutureProvider.autoDispose<List<Map<String, dynamic>>>(
  (ref) async {
    final service = ref.watch(gatePassServiceProvider);
    return _asRows(await service.mine());
  },
);

/// Guard-created visitors currently waiting for this resident's approval.
final residentGateApprovalsProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(gatePassServiceProvider);
      return _asRows(await service.residentApprovals());
    });

/// One pass by id, re-fetched rather than handed over from the list.
///
/// The detail screen takes an id in the path and reads it through here, so it is
/// correct on a cold deep link and cannot render a copy of a row that went stale
/// while the list sat on screen. It also sidesteps GoRouter `extra`, which this
/// app's router would discard on any auth-state change (the router rebuilds on
/// `authProvider`).
final passByIdProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, String>((ref, id) async {
      final service = ref.watch(gatePassServiceProvider);
      return service.byId(id);
    });

/// The renter's ACTIVE leases — the units they may raise a pass for.
///
/// Filtered to ACTIVE and nothing else, deliberately. The backend's
/// `requireUnitOnActiveLease` accepts a unit only on an ACTIVE lease and answers
/// **404** otherwise (not 403 — so a renter cannot probe unit ids). A screen that
/// offered a PENDING_SIGNATURE lease's unit would therefore turn a knowable
/// "your lease isn't active yet" into an unexplained not-found at submit.
///
/// Note this is stricter than the home screen's `_findActiveLease`, which also
/// accepts PENDING_SIGNATURE — it is picking a lease to *display*, which has no
/// such server-side rule behind it.
final activeLeasesProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(leaseServiceProvider);
      final leases = await service.getMyLeases();
      return leases
          .whereType<Map<String, dynamic>>()
          .where((l) => l['status'] == 'ACTIVE')
          .toList();
    });

/// The lease API, as an overridable seam.
///
/// Core exposes no `leaseServiceProvider`, and the screens each build their own
/// from `apiClientProvider` — which a test cannot fake without standing up a Dio
/// adapter. One provider here keeps [activeLeasesProvider] overridable the same
/// way [gatePassServiceProvider] is.
final leaseServiceProvider = Provider<LeaseService>((ref) {
  final client = ref.watch(apiClientProvider);
  return LeaseService(client.dio);
});

/// Sharing a pass with a guest, as an overridable seam.
///
/// A function rather than a direct `SharePlus.instance.share` call at the call
/// site so a widget test can assert *what the guest would receive* without a
/// platform channel. The share sheet itself is the plugin's business and is not
/// what these tests are about; the text is, because it is the only thing the
/// guest reads at the gate.
final shareTextProvider = Provider<Future<void> Function(String)>((ref) {
  return (text) async {
    await SharePlus.instance.share(ShareParams(text: text));
  };
});

/// Narrows the `List<dynamic>` Dio hands back.
///
/// `.cast()` is deliberately avoided: it defers the type error to whichever
/// widget first reads an element, surfacing a JSON shape change as a render
/// crash instead of as a failed provider with an error state the renter can
/// retry.
List<Map<String, dynamic>> _asRows(List<dynamic> raw) =>
    raw.whereType<Map<String, dynamic>>().toList();

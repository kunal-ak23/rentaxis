import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The guard's read models, over core's shared [gatePassServiceProvider].
///
/// Both are `autoDispose`: a gate phone lives in one of these lists all shift,
/// and a kept-alive provider would hand back a stale board after the app is
/// resumed hours later. Disposing on the last listener means re-entering a tab
/// re-reads the gate rather than replaying this morning.
///
/// **An empty list is a correct answer, not an error.** A guard with no property
/// assignments gets `[]` from both endpoints — the controller refuses to fall
/// back to "all properties", which would hand an unposted guard the whole
/// tenant's guest book. The screens must therefore distinguish "nothing today"
/// from "you are not posted anywhere yet", because a blank screen reads to a
/// guard as a broken app. [myPropertiesProvider] is what tells them apart.

/// Today's expected visitors at the guard's assigned properties.
///
/// `GatePassSummary` rows — no `qrToken`, no `numericCode`, no renter identity.
/// Do not reach for a credential here; it is deliberately not in this payload.
final expectedTodayProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(gatePassServiceProvider);
      return _asRows(await service.expectedToday());
    });

/// The guard's own posting: `{id, name}` rows, one per assigned property.
///
/// Read by the empty board to say *which* kind of empty it is. Empty here means
/// the guard is posted nowhere and no amount of waiting will produce a visitor —
/// that is a "go and ask your manager", not a quiet shift.
///
/// Carries the property's id and display name and nothing else, by design: the
/// Security role must not reach the property's financial or private fields
/// (SOW §3.1). See `GatePassApiService.myProperties`.
final myPropertiesProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(gatePassServiceProvider);
      return _asRows(await service.myProperties());
    });

/// Passes awaiting approval at the guard's assigned properties.
final approvalsProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      final service = ref.watch(gatePassServiceProvider);
      return _asRows(await service.approvals());
    });

/// Narrows the `List<dynamic>` Dio hands back.
///
/// `.cast()` is deliberately avoided: it defers the type error to whichever
/// widget first reads an element, surfacing a JSON shape change as a render
/// crash three screens away instead of as a failed provider with an error state
/// the guard can retry.
List<Map<String, dynamic>> _asRows(List<dynamic> raw) =>
    raw.whereType<Map<String, dynamic>>().toList();

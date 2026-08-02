/// Reading, wording and colouring of the renter's own gate passes, kept out of
/// the widgets so the parts that are easy to get wrong stay testable on their
/// own.
///
/// Everything here takes the raw `Map<String, dynamic>` the API returns (the
/// house convention — no models) and treats **any field as possibly absent**.
///
/// These passes come from the creator-facing paths (`create`/`mine`/`byId`/
/// `cancel`), so unlike the guard app's payloads they *do* carry `qrToken` and
/// `numericCode`. Those two are the credential: anyone holding either walks
/// through the gate. Nothing here logs them.
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final DateFormat _timeFormat = DateFormat('h:mm a');
final DateFormat _dayTimeFormat = DateFormat('d MMM yyyy, h:mm a');
final DateFormat _dayFormat = DateFormat('d MMM yyyy');

/// Reads a display string, treating blank as absent.
///
/// The API distinguishes null from empty; a screen does not — both mean "nothing
/// to show" — and rendering a stray '' would put an empty row where the renter
/// expects a value.
String? passString(Map<String, dynamic> pass, String key) {
  final value = pass[key];
  if (value is! String) return null;
  final trimmed = value.trim();
  return trimmed.isEmpty ? null : trimmed;
}

/// Parses one of the `Instant` fields into device-local time.
///
/// The backend serializes UTC with an explicit `Z`, so [DateTime.parse] yields a
/// UTC DateTime that must be converted before formatting — skipping `toLocal()`
/// would show a UAE renter every window four hours early. Unparseable or missing
/// values are null rather than an exception.
DateTime? passInstant(Map<String, dynamic> pass, String key) {
  final value = pass[key];
  if (value is! String || value.trim().isEmpty) return null;
  return DateTime.tryParse(value)?.toLocal();
}

/// The colour for a gate-pass status.
///
/// Deliberately only three colours for five statuses, because the renter is
/// asking one question — *will this work at the gate?* — and only ACTIVE answers
/// yes. PENDING_APPROVAL is the amber "not yet", and USED/EXPIRED/CANCELLED are
/// all equally spent; colouring them apart would imply a difference that does
/// not exist at the gate.
///
/// Mirrors `StatusHelper`'s mapping style and reuses its palette rather than
/// inventing colours.
Color gatePassStatusColor(String? status) {
  return switch (status) {
    'ACTIVE' => AppColors.statusActive,
    'PENDING_APPROVAL' => AppColors.statusPending,
    'USED' => AppColors.textMuted,
    'EXPIRED' => AppColors.textMuted,
    'CANCELLED' => AppColors.textMuted,
    _ => AppColors.textMuted,
  };
}

/// The status as a renter should read it.
///
/// `PENDING_APPROVAL` becomes "Awaiting approval": the raw enum reads as though
/// the *renter* owes an action, when in fact they are waiting on the manager.
String gatePassStatusLabel(String? status) {
  return switch (status) {
    'ACTIVE' => 'Active',
    'PENDING_APPROVAL' => 'Awaiting approval',
    'USED' => 'Used',
    'EXPIRED' => 'Expired',
    'CANCELLED' => 'Cancelled',
    null => 'Unknown',
    _ => status,
  };
}

/// True when the pass can still be cancelled.
///
/// Keys on the two statuses the backend accepts a cancel for. A USED pass 400s,
/// and an EXPIRED/CANCELLED one has nothing left to cancel — offering the button
/// anyway would put a server error behind a control the renter was invited to
/// press.
bool canCancel(String? status) =>
    status == 'ACTIVE' || status == 'PENDING_APPROVAL';

/// The validity window, phrased for a glance.
///
/// Collapses to a date plus bare times when the window opens and closes on the
/// same local day (the common single-visit case), and carries both dates
/// otherwise so a multi-day recurring pass cannot be misread as ending tonight.
String formatWindow(DateTime? from, DateTime? to) {
  if (from == null && to == null) return 'No time limit';
  if (from == null) return 'Until ${_dayTimeFormat.format(to!)}';
  if (to == null) return 'From ${_dayTimeFormat.format(from)}';

  final sameDay =
      from.year == to.year && from.month == to.month && from.day == to.day;
  if (sameDay) {
    return '${_dayFormat.format(from)}, '
        '${_timeFormat.format(from)} – ${_timeFormat.format(to)}';
  }
  return '${_dayTimeFormat.format(from)} – ${_dayTimeFormat.format(to)}';
}

/// The message a renter sends their guest.
///
/// Written to be actionable on its own, because it is read at the gate by
/// someone who was not in the app: it names where to go, carries the code the
/// guard can key in if the QR will not scan, states the window, and says to show
/// the pass. The QR itself cannot travel in a text message, so the numeric code
/// is the guest's fallback and has to be here.
///
/// Nothing about the pass's *status* is included: a shared pass is one the
/// renter chose to send, and a caveat about approval would be stale by the time
/// the guest reads it. The approval state is the renter's problem, on the
/// renter's screen.
String buildShareText({
  required Map<String, dynamic> pass,
  String? propertyName,
  String? unitIdentifier,
}) {
  final lines = <String>[];
  final guest = passString(pass, 'guestName');
  lines.add(
    guest == null
        ? 'You have a gate pass.'
        : 'Hi $guest, here is your gate pass.',
  );

  final place = [
    propertyName,
    unitIdentifier == null ? null : 'Unit $unitIdentifier',
  ].whereType<String>().join(' · ');
  if (place.isNotEmpty) lines.add('Where: $place');

  lines.add(
    'When: ${formatWindow(passInstant(pass, 'validFrom'), passInstant(pass, 'validTo'))}',
  );

  final code = passString(pass, 'numericCode');
  if (code != null) lines.add('Entry code: $code');

  lines.add(
    'Show the QR code in your pass at the gate, or give the guard the '
    'entry code above.',
  );

  return lines.join('\n');
}

/// Names the place a pass is for, by matching its `unitId` against the renter's
/// leases.
///
/// The lookup exists because `GatePassResponse` carries `unitId` and
/// `propertyId` but **no names** — unlike the guard-facing `GatePassSummary`,
/// which has `propertyName` and `unitNumber`. The renter's own `LeaseDTO` is
/// where the names live (`propertyName`, `unitIdentifier`), and it is already
/// the renter's data, so nothing is being widened here.
///
/// Returns nulls when no lease matches, which is a real case rather than an
/// error: a pass outlives the lease it was raised on, so an old pass on a
/// finished tenancy simply cannot be named. Callers must degrade rather than
/// insist.
({String? propertyName, String? unitIdentifier}) resolveUnitLabel(
  List<Map<String, dynamic>> leases,
  String? unitId,
) {
  if (unitId == null) return (propertyName: null, unitIdentifier: null);
  for (final lease in leases) {
    if (lease['unitId']?.toString() == unitId) {
      return (
        propertyName: passString(lease, 'propertyName'),
        unitIdentifier: passString(lease, 'unitIdentifier'),
      );
    }
  }
  return (propertyName: null, unitIdentifier: null);
}

/// Groups the numeric code so it can be read aloud without losing the place.
///
/// A guard keys this in while the guest reads it out; an unbroken run of six
/// digits is exactly the shape people drop a digit from.
String formatNumericCode(String code) {
  final digits = code.trim();
  if (digits.length < 6) return digits;
  final mid = (digits.length / 2).ceil();
  return '${digits.substring(0, mid)} ${digits.substring(mid)}';
}

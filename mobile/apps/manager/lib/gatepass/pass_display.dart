/// Reading and wording of the gate-pass payloads, kept out of the widgets so the
/// parts that are easy to get wrong are testable on their own.
///
/// A per-app copy, matching `apps/security/lib/gatepass/pass_display.dart` and
/// `apps/renter/lib/gatepass/pass_display.dart` — the three apps each keep their
/// own because each shows a different subset with different wording, and the
/// house convention is a per-app file rather than a shared one in core.
///
/// Everything here takes the raw `Map<String, dynamic>` the API returns (the
/// house convention — no models), and everything here assumes **any field may be
/// absent**. On the manager's approvals queue that is the contract, not caution:
/// `GatePassSummary.propertyName` and `unitNumber` are resolved by a batched
/// lookup server-side and are simply null when the row has gone (see
/// `GatePassController.toSummary`).
library;

import 'package:intl/intl.dart';

final DateFormat _timeFormat = DateFormat('h:mm a');
final DateFormat _dayTimeFormat = DateFormat('d MMM, h:mm a');
final DateFormat _timeFormatAr = DateFormat('h:mm a', 'ar');
final DateFormat _dayTimeFormatAr = DateFormat('d MMMM, h:mm a', 'ar');

/// Reads a display string, treating blank as absent.
///
/// The API distinguishes null from empty; a queue does not — both mean "nothing
/// to show" — and rendering a stray '' would put an empty row on the screen
/// where the manager expects a value.
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
/// would show a UAE manager every window four hours early. Unparseable or
/// missing values are null rather than an exception: a malformed timestamp must
/// not take down the approvals queue.
DateTime? passInstant(Map<String, dynamic> pass, String key) {
  final value = pass[key];
  if (value is! String || value.trim().isEmpty) return null;
  return DateTime.tryParse(value)?.toLocal();
}

/// The validity window, phrased for a glance.
///
/// Collapses to bare times when the window opens and closes on the same local
/// day (the common case, and the one where a date is noise), and carries the day
/// otherwise so an overnight or multi-day pass cannot be misread as ending this
/// morning.
///
/// [ar] selects Arabic month names and wording (`Formatters.date`-style);
/// the times themselves stay in Western digits, matching the rest of the app.
String formatWindow(DateTime? from, DateTime? to, {bool ar = false}) {
  final timeFormat = ar ? _timeFormatAr : _timeFormat;
  final dayTimeFormat = ar ? _dayTimeFormatAr : _dayTimeFormat;

  if (from == null && to == null) {
    return ar ? 'لا يوجد حد زمني' : 'No time limit given';
  }
  if (from == null) {
    final until = dayTimeFormat.format(to!);
    return ar ? 'حتى $until' : 'Until $until';
  }
  if (to == null) {
    final fromStr = dayTimeFormat.format(from);
    return ar ? 'من $fromStr' : 'From $fromStr';
  }

  final sameDay =
      from.year == to.year && from.month == to.month && from.day == to.day;
  if (sameDay) {
    return '${timeFormat.format(from)} – ${timeFormat.format(to)}';
  }
  return '${dayTimeFormat.format(from)} – ${dayTimeFormat.format(to)}';
}

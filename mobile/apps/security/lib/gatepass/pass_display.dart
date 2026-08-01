/// Reading and wording of the gate-pass payloads, kept out of the widgets so the
/// parts that are easy to get wrong are testable on their own.
///
/// Everything here takes the raw `Map<String, dynamic>` the API returns (the
/// house convention — no models), and everything here assumes **any field may be
/// absent**. That is not defensive habit, it is the contract: a scan rejected at
/// a property the guard is not assigned to comes back with every guest field
/// null on purpose, so the guard cannot read the guest book of a gate they do
/// not work (`GatePassScanService.scan`). See [describeRejection].
library;

import 'package:intl/intl.dart';

final DateFormat _timeFormat = DateFormat('h:mm a');
final DateFormat _dayTimeFormat = DateFormat('d MMM, h:mm a');
final DateFormat _dayFormat = DateFormat('d MMM');
final DateFormat _timeFormatAr = DateFormat('h:mm a', 'ar');
final DateFormat _dayTimeFormatAr = DateFormat('d MMMM, h:mm a', 'ar');
final DateFormat _dayFormatAr = DateFormat('d MMMM', 'ar');

/// Reads a display string, treating blank as absent.
///
/// The API distinguishes null from empty; a gate does not — both mean "nothing
/// to show" — and rendering a stray '' as a value would put an empty row on the
/// screen where the guard expects a name.
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
/// would show a UAE guard every window four hours early. Unparseable or missing
/// values are null rather than an exception: a malformed timestamp must not take
/// down the screen a guard is holding at the gate.
DateTime? passInstant(Map<String, dynamic> pass, String key) {
  final value = pass[key];
  if (value is! String || value.trim().isEmpty) return null;
  return DateTime.tryParse(value)?.toLocal();
}

/// The validity window, phrased for a glance.
///
/// Collapses to bare times when the window opens and closes on the same local
/// day (the overwhelmingly common case, and the one where a date is noise), and
/// carries the day otherwise so an overnight or multi-day pass cannot be misread
/// as ending this morning.
///
/// [ar] selects Arabic month names and wording, mirroring the manager app's
/// copy of this helper; the times themselves stay in Western digits.
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

/// Short label for the day a window falls on, used to head a visitor row that is
/// not for today.
String formatDay(DateTime day, {bool ar = false}) =>
    (ar ? _dayFormatAr : _dayFormat).format(day);

/// Turns the backend's terse rejection reason into something a guard can read
/// aloud to the person in front of them.
///
/// The strings matched here are the literals in `GatePassScanService` — they are
/// not an enum, so an unrecognised one is entirely possible after a backend
/// change. That case falls back to the server's own text rather than a generic
/// "denied": a guard reading an awkward phrase still knows why, whereas a guard
/// reading "cannot be used" has nothing to tell the guest and no way to escalate.
///
/// [ar] renders the same wording in Arabic. An unrecognised reason still falls
/// back to the server's raw (English) text in either language — there is
/// nothing to translate it from.
String describeRejection(String? reason, {bool ar = false}) {
  switch (reason) {
    case 'not authorized for this property':
      // The one rejection that arrives with no guest details at all, by design.
      // The copy must therefore stand entirely on its own — there is no name on
      // screen to qualify it.
      return ar
          ? 'هذا التصريح خاص بمبنى آخر. وجّه الزائر إلى بوابة مبناه.'
          : 'This pass is for another property. Direct the guest to the gate '
                'for their building.';
    case 'not found':
      return ar
          ? 'هذا الرمز غير معروف. اطلب من الزائر التحقق من تصريحه.'
          : 'This code is not recognised. Ask the guest to check their pass.';
    case 'already used':
      return ar
          ? 'تم استخدام هذا التصريح مسبقًا. وهو صالح لدخول واحد فقط.'
          : 'This pass has already been used. It is valid for one entry only.';
    case 'expired':
      return ar ? 'انتهت صلاحية هذا التصريح.' : 'This pass has expired.';
    case 'cancelled':
      return ar
          ? 'تم إلغاء هذا التصريح من قبل المقيم.'
          : 'This pass was cancelled by the resident.';
    case 'pending approval':
      return ar
          ? 'لا يزال هذا التصريح بانتظار الموافقة.'
          : 'This pass is still waiting for approval.';
    case 'outside validity window':
      return ar
          ? 'هذا التصريح غير صالح الآن. تحقق من الفترة الزمنية أدناه.'
          : 'This pass is not valid right now. Check the time window below.';
    case 'no entry recorded':
      return ar
          ? 'لم يُسجَّل دخول لهذا التصريح، لذا لا يمكن تسجيل الخروج.'
          : 'No entry was recorded for this pass, so an exit cannot be logged.';
    case 'scan in progress, please retry':
      return ar
          ? 'يجري حاليًا مسح آخر لهذا التصريح. حاول مرة أخرى بعد لحظات.'
          : 'Another scan of this pass is in progress. Try again in a moment.';
    case null:
      return ar ? 'لا يمكن استخدام هذا التصريح.' : 'This pass cannot be used.';
    default:
      return reason;
  }
}

/// True when the gate declined to identify the pass to this guard.
///
/// Keys on the guest fields actually being absent rather than on the reason
/// string, because the blinding is the controller's `pass == null` branch and
/// the reason is only how it got there — a new blinded rejection would keep the
/// null guest and would not keep this reason.
bool isBlindedRejection(Map<String, dynamic> response) =>
    passString(response, 'guestName') == null &&
    passString(response, 'unitNumber') == null;

/// Groups today's expected visitors by the property they are expected at.
///
/// Grouped by `propertyId` because it is the stable key, but each group also
/// carries the `propertyName` (`GatePassSummary`'s copy of the building's
/// `nameEn`) that [propertyGroupLabel] renders. The name is taken from the first
/// row in the group that has one: it is a property of the group, not the row, so
/// any row that carries it carries the same one — and an older backend that
/// omits the field leaves it null rather than making the grouping fail.
/// Most guards are posted to one property, where the grouping is invisible
/// anyway.
///
/// Ordering: groups appear in first-seen order (the backend's own ordering) and
/// rows within a group are sorted by when the window opens, so the next arrival
/// is nearest the top. Passes with no `validFrom` sort last rather than crashing
/// the comparator.
List<PropertyGroup> groupByProperty(List<Map<String, dynamic>> passes) {
  final groups = <String, List<Map<String, dynamic>>>{};
  for (final pass in passes) {
    final key = passString(pass, 'propertyId') ?? '';
    groups.putIfAbsent(key, () => []).add(pass);
  }

  return groups.entries.map((entry) {
    final rows = [...entry.value];
    rows.sort((a, b) {
      final aFrom = passInstant(a, 'validFrom');
      final bFrom = passInstant(b, 'validFrom');
      if (aFrom == null && bFrom == null) return 0;
      if (aFrom == null) return 1;
      if (bFrom == null) return -1;
      return aFrom.compareTo(bFrom);
    });
    String? name;
    for (final row in rows) {
      name = passString(row, 'propertyName');
      if (name != null) break;
    }
    return PropertyGroup(
      propertyId: entry.key,
      propertyName: name,
      passes: rows,
    );
  }).toList();
}

/// A heading naming the property a group of visitors is expected at.
///
/// Uses the building's own name when `GatePassSummary` carries one — that is the
/// word on the gate and the only version of this heading a guard can act on.
///
/// Falls back to a fragment of the property's id when the name is absent (an
/// older backend, or a property with no `nameEn`). The fragment is not meaningful
/// — it is a discriminator, not a name — but it still lets a two-property guard
/// see that these are two different gates, which is the minimum the heading owes
/// them.
String propertyGroupLabel(
  String propertyId,
  int index, {
  String? propertyName,
}) {
  if (propertyName != null && propertyName.trim().isNotEmpty) {
    return propertyName.trim();
  }
  if (propertyId.isEmpty) return 'Property ${index + 1}';
  final short = propertyId.replaceAll('-', '');
  return 'Property ${index + 1} · '
      '${short.substring(0, short.length < 6 ? short.length : 6).toUpperCase()}';
}

/// One property's worth of expected visitors.
class PropertyGroup {
  const PropertyGroup({
    required this.propertyId,
    required this.passes,
    this.propertyName,
  });

  final String propertyId;

  /// The building's name, or null when the payload did not carry one.
  final String? propertyName;

  final List<Map<String, dynamic>> passes;
}

/// Helpers shared by the facilities screens, split out from
/// `facilities_screen.dart` and `booking_approvals_screen.dart`.
///
/// Most of this is pure logic kept `BuildContext`-free so it can be unit
/// tested directly (see `test/facilities_parse_test.dart`); [FacilityScrollable]
/// is the one small exception — a tiny layout widget duplicated identically
/// across both screens before, now shared instead.
library;

import 'package:flutter/material.dart';

/// `errorMessage` moved to `rentaxis_core`'s `utils/error_message.dart` so
/// the renter app (which can only depend on `rentaxis_core`, not on this
/// manager-only file) can use it too. Re-exported here so existing imports
/// of this file — including `test/facilities_parse_test.dart` — keep working
/// unchanged.
export 'package:rentaxis_core/rentaxis_core.dart' show errorMessage;

/// Backend's cap on `spotNumbers` per bulk-create request
/// (`ParkingSpotBulkCreateRequest`) — also the ceiling for range expansion.
const int kMaxBulkSpotNumbers = 500;

/// Backend's `@Size(max = 32)` on `ParkingSpot`'s `spotNumber`/`level`
/// columns (`ParkingSpotCreateRequest`).
const int kSpotFieldMaxLength = 32;

/// A numeric range at the end of a trimmed entry: optional prefix, digits,
/// optional whitespace, dash, optional whitespace, optional prefix, digits.
/// Mirrors `web/src/lib/api/facilities.ts` `parseSpotNumbers`.
final RegExp _rangePattern = RegExp(r'^(.*?)(\d+)\s*-\s*(.*?)(\d+)$');

/// Splits comma/semicolon/newline-separated spot numbers, expanding numeric
/// ranges that share a prefix on both sides of the dash:
///   "B1-05, B1-06"  -> ["B1-05", "B1-06"]        (prefixes differ around the
///                                                  dash itself -> literal)
///   "P10-P20"       -> ["P10", "P11", ..., "P20"] (same prefix -> expanded)
///   "10-12"         -> ["10", "11", "12"]
///   "A1\nA2;A3"     -> ["A1", "A2", "A3"]
/// Zero-padding of the start bound is preserved ("P08-P10" -> P08, P09,
/// P10). A range that would expand to more than [kMaxBulkSpotNumbers]
/// entries — the backend's `spotNumbers` cap — is kept literal to guard
/// against typos. Blanks are dropped and duplicates removed, keeping the
/// order of first appearance. Ported from web's `parseSpotNumbers`.
List<String> parseSpotNumbers(String raw) {
  final seen = <String>{};
  final result = <String>[];
  void add(String value) {
    if (seen.add(value)) result.add(value);
  }

  for (final part in raw.split(RegExp(r'[,;\n]'))) {
    final entry = part.trim();
    if (entry.isEmpty) continue;
    final m = _rangePattern.firstMatch(entry);
    if (m != null && m.group(1) == m.group(3)) {
      final prefix = m.group(1)!;
      final startStr = m.group(2)!;
      final start = int.tryParse(startStr);
      final end = int.tryParse(m.group(4)!);
      if (start != null &&
          end != null &&
          end >= start &&
          end - start < kMaxBulkSpotNumbers) {
        for (var n = start; n <= end; n++) {
          add('$prefix${n.toString().padLeft(startStr.length, '0')}');
        }
        continue;
      }
    }
    add(entry);
  }
  return result;
}

/// Result of validating a bulk spot-numbers entry, decoupled from
/// localization so the branching can be unit tested without a
/// `BuildContext`.
enum BulkSpotValidation { ok, empty, tooMany, tooLong }

/// Validates already-[parseSpotNumbers]-parsed entries against the bulk-add
/// rules: at least one entry, no more than [kMaxBulkSpotNumbers], and no
/// single entry longer than [kSpotFieldMaxLength] (the backend's per-entry
/// cap — range expansion can produce entries this long even though the
/// whole-list length check passes).
BulkSpotValidation validateBulkSpotNumbers(List<String> parsed) {
  if (parsed.isEmpty) return BulkSpotValidation.empty;
  if (parsed.length > kMaxBulkSpotNumbers) return BulkSpotValidation.tooMany;
  if (parsed.any((s) => s.length > kSpotFieldMaxLength)) {
    return BulkSpotValidation.tooLong;
  }
  return BulkSpotValidation.ok;
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so empty
/// and error states are given something to scroll. Shared by
/// `facilities_screen.dart` and `booking_approvals_screen.dart` (was a private
/// `_Scrollable` duplicated in both).
class FacilityScrollable extends StatelessWidget {
  const FacilityScrollable({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}

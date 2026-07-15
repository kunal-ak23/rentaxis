import 'package:flutter_test/flutter_test.dart';
import 'package:security/gatepass/pass_display.dart';

void main() {
  group('passString', () {
    test('treats missing, non-string and blank alike as absent', () {
      expect(passString({}, 'guestName'), isNull);
      expect(passString({'guestName': null}, 'guestName'), isNull);
      expect(passString({'guestName': '   '}, 'guestName'), isNull);
      expect(passString({'guestName': 42}, 'guestName'), isNull);
      expect(passString({'guestName': ' Ahmed '}, 'guestName'), 'Ahmed');
    });
  });

  group('passInstant', () {
    test('converts the UTC instant the backend sends into local time', () {
      final parsed = passInstant({'validFrom': '2026-07-16T05:00:00Z'}, 'validFrom');

      expect(parsed, isNotNull);
      expect(parsed!.isUtc, isFalse,
          reason: 'formatting a UTC DateTime would show a UAE guard every '
              'window four hours early');
      expect(parsed.toUtc(), DateTime.utc(2026, 7, 16, 5));
    });

    test('a malformed or missing timestamp is null, not an exception', () {
      expect(passInstant({'validFrom': 'not a date'}, 'validFrom'), isNull);
      expect(passInstant({'validFrom': null}, 'validFrom'), isNull);
      expect(passInstant({}, 'validFrom'), isNull);
    });
  });

  group('formatWindow', () {
    test('drops the date when the window opens and closes on one day', () {
      final window = formatWindow(
        DateTime(2026, 7, 16, 9),
        DateTime(2026, 7, 16, 17),
      );

      expect(window, '9:00 AM – 5:00 PM');
    });

    test('keeps the day when the window spans more than one', () {
      final window = formatWindow(
        DateTime(2026, 7, 16, 21),
        DateTime(2026, 7, 17, 6),
      );

      expect(window, contains('16 Jul'));
      expect(window, contains('17 Jul'));
    });

    test('handles the all-null window a blinded rejection carries', () {
      expect(formatWindow(null, null), 'No time limit given');
    });

    test('handles a half-open window', () {
      expect(formatWindow(DateTime(2026, 7, 16, 9), null), startsWith('From '));
      expect(formatWindow(null, DateTime(2026, 7, 16, 17)), startsWith('Until '));
    });
  });

  group('describeRejection', () {
    test('the blinded rejection stands on its own, with no guest to qualify it',
        () {
      final copy = describeRejection('not authorized for this property');

      expect(copy, contains('another property'));
      // Nothing on that screen names a guest, so the copy must say what to do.
      expect(copy.toLowerCase(), contains('gate'));
    });

    test('every reason GatePassScanService emits has copy', () {
      const reasons = [
        'not authorized for this property',
        'not found',
        'already used',
        'expired',
        'cancelled',
        'pending approval',
        'outside validity window',
        'no entry recorded',
        'scan in progress, please retry',
      ];

      for (final reason in reasons) {
        final copy = describeRejection(reason);
        expect(copy, isNotEmpty);
        expect(copy, isNot(reason),
            reason: '$reason should be rewritten for a guard, not echoed');
      }
    });

    test('an unknown reason falls back to the server text, not to "denied"', () {
      // A guard reading an awkward phrase still knows why; a guard reading
      // "denied" has nothing to tell the guest.
      expect(describeRejection('some new backend reason'),
          'some new backend reason');
    });

    test('a null reason still says something', () {
      expect(describeRejection(null), 'This pass cannot be used.');
    });
  });

  group('isBlindedRejection', () {
    test('keys on the guest actually being absent, not on the reason string',
        () {
      expect(
        isBlindedRejection({
          'result': 'REJECTED',
          'reason': 'not authorized for this property',
          'guestName': null,
          'unitNumber': null,
        }),
        isTrue,
      );
      expect(
        isBlindedRejection({
          'result': 'REJECTED',
          'reason': 'already used',
          'guestName': 'Ahmed Khan',
          'unitNumber': '101',
        }),
        isFalse,
      );
    });
  });

  group('groupByProperty', () {
    test('groups by propertyId and orders each group by when it opens', () {
      final groups = groupByProperty([
        {'id': 'b', 'propertyId': 'p1', 'validFrom': '2026-07-16T12:00:00Z'},
        {'id': 'a', 'propertyId': 'p1', 'validFrom': '2026-07-16T05:00:00Z'},
        {'id': 'c', 'propertyId': 'p2', 'validFrom': '2026-07-16T09:00:00Z'},
      ]);

      expect(groups, hasLength(2));
      expect(groups.first.propertyId, 'p1');
      expect(groups.first.passes.map((p) => p['id']), ['a', 'b']);
      expect(groups.last.passes.map((p) => p['id']), ['c']);
    });

    test('a pass with no validFrom sorts last rather than breaking the sort',
        () {
      final groups = groupByProperty([
        {'id': 'none', 'propertyId': 'p1'},
        {'id': 'timed', 'propertyId': 'p1', 'validFrom': '2026-07-16T05:00:00Z'},
      ]);

      expect(groups.single.passes.map((p) => p['id']), ['timed', 'none']);
    });

    test('an empty list groups into nothing', () {
      expect(groupByProperty([]), isEmpty);
    });

    test('carries the property name off the rows', () {
      final groups = groupByProperty([
        {'id': 'a', 'propertyId': 'p1', 'propertyName': 'Marina Heights'},
        {'id': 'b', 'propertyId': 'p1', 'propertyName': 'Marina Heights'},
      ]);

      expect(groups.single.propertyName, 'Marina Heights');
    });

    test('takes the name from a later row when the first has none', () {
      // The name belongs to the group, not the row, so one row omitting it must
      // not cost the whole heading its name.
      final groups = groupByProperty([
        {'id': 'a', 'propertyId': 'p1'},
        {'id': 'b', 'propertyId': 'p1', 'propertyName': 'Marina Heights'},
      ]);

      expect(groups.single.propertyName, 'Marina Heights');
    });

    test('leaves the name null when no row carries one', () {
      final groups = groupByProperty([
        {'id': 'a', 'propertyId': 'p1'},
      ]);

      expect(groups.single.propertyName, isNull);
    });
  });

  group('propertyGroupLabel', () {
    test('shows the building name when there is one', () {
      expect(
        propertyGroupLabel('aaaaaaaa-1111-2222', 0,
            propertyName: 'Marina Heights'),
        'Marina Heights',
      );
    });

    test('prefers the name over the id fragment regardless of position', () {
      expect(
        propertyGroupLabel('bbbbbbbb-1111-2222', 3,
            propertyName: 'Jumeirah Gardens'),
        'Jumeirah Gardens',
      );
    });

    test('falls back to the id fragment when the name is absent', () {
      expect(
        propertyGroupLabel('a3f2e1aa-1111-2222', 0),
        'Property 1 · A3F2E1',
      );
    });

    test('falls back when the name is blank rather than heading with nothing',
        () {
      expect(
        propertyGroupLabel('a3f2e1aa-1111-2222', 0, propertyName: '   '),
        'Property 1 · A3F2E1',
      );
    });

    test('falls back to the bare ordinal when there is no id either', () {
      expect(propertyGroupLabel('', 1), 'Property 2');
    });
  });
}

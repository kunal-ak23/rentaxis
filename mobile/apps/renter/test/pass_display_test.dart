import 'package:flutter_test/flutter_test.dart';
import 'package:renter/gatepass/pass_display.dart';
import 'package:renter/gatepass/phone_format.dart';

void main() {
  group('passInstant', () {
    test('parses a UTC instant into local time', () {
      final parsed = passInstant({'validFrom': '2026-07-16T05:00:00Z'}, 'validFrom');
      expect(parsed, isNotNull);
      expect(parsed!.isUtc, isFalse);
      // Same moment, expressed locally — not the wall-clock digits reused.
      expect(parsed.toUtc(), DateTime.utc(2026, 7, 16, 5));
    });

    test('a missing or malformed instant is null, not an exception', () {
      // A bad timestamp must not take down the screen the renter is holding at
      // the gate.
      expect(passInstant({}, 'validFrom'), isNull);
      expect(passInstant({'validFrom': 'not a date'}, 'validFrom'), isNull);
      expect(passInstant({'validFrom': ''}, 'validFrom'), isNull);
      expect(passInstant({'validFrom': 12345}, 'validFrom'), isNull);
    });
  });

  group('passString', () {
    test('blank reads as absent', () {
      expect(passString({'purpose': '   '}, 'purpose'), isNull);
      expect(passString({'purpose': 'Delivery'}, 'purpose'), 'Delivery');
      expect(passString({'purpose': null}, 'purpose'), isNull);
      expect(passString({}, 'purpose'), isNull);
    });
  });

  group('canCancel', () {
    test('only the two statuses the backend accepts a cancel for', () {
      expect(canCancel('ACTIVE'), isTrue);
      expect(canCancel('PENDING_APPROVAL'), isTrue);
      expect(canCancel('USED'), isFalse);
      expect(canCancel('EXPIRED'), isFalse);
      expect(canCancel('CANCELLED'), isFalse);
      expect(canCancel(null), isFalse);
    });
  });

  group('formatWindow', () {
    test('collapses to one date and two times within a day', () {
      expect(
        formatWindow(DateTime(2026, 7, 16, 9), DateTime(2026, 7, 16, 18)),
        '16 Jul 2026, 9:00 AM – 6:00 PM',
      );
    });

    test('carries both dates across days so it cannot be misread', () {
      final window =
          formatWindow(DateTime(2026, 7, 16, 9), DateTime(2026, 8, 16, 18));
      expect(window, contains('16 Jul 2026'));
      expect(window, contains('16 Aug 2026'));
    });

    test('says something useful when an end is missing', () {
      expect(formatWindow(null, null), 'No time limit');
      expect(formatWindow(DateTime(2026, 7, 16, 9), null), startsWith('From'));
      expect(formatWindow(null, DateTime(2026, 7, 16, 18)), startsWith('Until'));
    });
  });

  group('formatNumericCode', () {
    test('splits the code so it can be read aloud', () {
      expect(formatNumericCode('481920'), '481 920');
    });

    test('leaves a short code alone', () {
      expect(formatNumericCode('4819'), '4819');
    });
  });

  group('resolveUnitLabel', () {
    final leases = [
      {
        'unitId': 'unit-1',
        'unitIdentifier': '1204',
        'propertyName': 'Marina Heights',
      },
    ];

    test('names the place from the matching lease', () {
      final place = resolveUnitLabel(leases, 'unit-1');
      expect(place.propertyName, 'Marina Heights');
      expect(place.unitIdentifier, '1204');
    });

    test('resolves to nulls when no lease matches', () {
      // A pass outlives the lease it was raised on; this is a real state, not a
      // failure, and the caller must degrade rather than insist.
      final place = resolveUnitLabel(leases, 'unit-gone');
      expect(place.propertyName, isNull);
      expect(place.unitIdentifier, isNull);
    });

    test('resolves to nulls for a pass with no unit at all', () {
      expect(resolveUnitLabel(leases, null).propertyName, isNull);
    });
  });

  group('buildShareText', () {
    Map<String, dynamic> pass() => {
          'guestName': 'Ahmed Khan',
          'numericCode': '481920',
          'validFrom': '2026-07-16T05:00:00Z',
          'validTo': '2026-07-16T13:00:00Z',
        };

    test('carries everything a guest needs to get through the gate', () {
      final text = buildShareText(
        pass: pass(),
        propertyName: 'Marina Heights',
        unitIdentifier: '1204',
      );

      expect(text, contains('Ahmed Khan'));
      expect(text, contains('Marina Heights'));
      expect(text, contains('Unit 1204'));
      expect(text, contains('481920'));
      expect(text, contains('gate'));
    });

    test('omits the address rather than printing a gap', () {
      final text = buildShareText(pass: pass());
      expect(text, isNot(contains('Where:')));
      expect(text, contains('481920'));
    });

    test('omits the code when the pass carries none', () {
      final row = pass()..remove('numericCode');
      final text = buildShareText(pass: row);
      expect(text, isNot(contains('Entry code')));
    });

    test('says nothing about approval status', () {
      // A shared pass is one the renter chose to send; a caveat about approval
      // would be stale by the time the guest reads it, and the approval state
      // is the renter's problem on the renter's screen.
      final text = buildShareText(pass: pass());
      expect(text.toLowerCase(), isNot(contains('approval')));
    });
  });

  group('phone normalization', () {
    test('strips exactly what the server strips', () {
      expect(normalizePhone('+971 50-123 4567'), '+971501234567');
    });

    test('accepts what the server accepts', () {
      expect(isValidE164('+971501234567'), isTrue);
    });

    test('rejects what the server rejects', () {
      // Kept in step with `OtpLoginService.normalize` / the E164 pattern: a
      // looser check here turns a field error into an opaque 400.
      expect(isValidE164('0501234567'), isFalse); // no country code
      expect(isValidE164('+9715'), isFalse); // too short
      expect(isValidE164('+971abc1234567'), isFalse); // not digits
      expect(isValidE164('+9715012345678901234'), isFalse); // too long
    });
  });
}

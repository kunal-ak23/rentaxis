import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/renters_screen.dart';

/// Client request 2026-10-29: a tenant needs a mobile. The New Renter form holds
/// it to the server's lenient rule (PhoneNumbers.LENIENT_PATTERN): digits, spaces,
/// "+", "-" and brackets, 7-15 digits, after Arabic-Indic digits and Unicode
/// spaces are made plain.
void main() {
  group('renterPhoneProblem', () {
    test('a blank mobile is required', () {
      expect(renterPhoneProblem(null), RenterPhoneProblem.required);
      expect(renterPhoneProblem('   '), RenterPhoneProblem.required);
    });

    test('something that is not a phone number is refused', () {
      expect(renterPhoneProblem('N/A'), RenterPhoneProblem.invalid);
      expect(renterPhoneProblem('12345'), RenterPhoneProblem.invalid);
      expect(renterPhoneProblem('call the office'), RenterPhoneProblem.invalid);
    });

    test('local and international forms pass', () {
      expect(renterPhoneProblem('+971 50 123 4567'), isNull);
      expect(renterPhoneProblem('050-123-4567'), isNull);
      expect(renterPhoneProblem('04 123 9911'), isNull);
    });

    test('Arabic-Indic digits and pasted non-breaking spaces pass', () {
      expect(renterPhoneProblem('٠٥٠ ١٢٣ ٤٥٦٧'), isNull);
      expect(renterPhoneProblem('+971 50 123 4567'), isNull);
    });
  });

  test('the mobile is sent with its digits and spaces made plain', () {
    expect(normalizeRenterPhone(' ٠٥٠ ١٢٣ ٤٥٦٧ '), '050 123 4567');
  });
}

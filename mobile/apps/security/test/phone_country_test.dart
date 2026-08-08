import 'package:flutter_test/flutter_test.dart';
import 'package:security/auth/phone_country.dart';

void main() {
  group('PhoneCountry.forLocale', () {
    test('IN resolves to India', () {
      expect(PhoneCountry.forLocale('IN'), PhoneCountry.india);
    });

    test('lowercase ae resolves to UAE', () {
      expect(PhoneCountry.forLocale('ae'), PhoneCountry.uae);
    });

    test('null falls back to UAE', () {
      expect(PhoneCountry.forLocale(null), PhoneCountry.uae);
    });

    test('an unoffered country code falls back to UAE', () {
      expect(PhoneCountry.forLocale('US'), PhoneCountry.uae);
    });
  });

  group('PhoneCountry.forE164', () {
    test('a +971 number resolves to UAE', () {
      expect(PhoneCountry.forE164('+971501234567'), PhoneCountry.uae);
    });

    test('a +91 number resolves to India', () {
      expect(PhoneCountry.forE164('+919876543210'), PhoneCountry.india);
    });

    test('an unoffered dial code resolves to null', () {
      expect(PhoneCountry.forE164('+12025550123'), isNull);
    });
  });

  group('PhoneCountry.toE164', () {
    test('composes the dial code and the national number', () {
      expect(PhoneCountry.uae.toE164('501234567'), '+971501234567');
      expect(PhoneCountry.india.toE164('9876543210'), '+919876543210');
    });
  });

  group('stripTrunkPrefix', () {
    test('drops a single leading trunk zero', () {
      expect(stripTrunkPrefix('0501234567'), '501234567');
    });

    test('strips non-digit characters', () {
      expect(stripTrunkPrefix('50-123 4567'), '501234567');
    });

    test('leaves a normal number without a trunk zero alone', () {
      expect(stripTrunkPrefix('501234567'), '501234567');
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:security/auth/phone_format.dart';

void main() {
  group('defaultPhonePrefixForCountry', () {
    test('uses India for Indian devices', () {
      expect(defaultPhonePrefixForCountry('IN'), '+91');
      expect(defaultPhonePrefixForCountry('in'), '+91');
    });

    test('uses UAE for UAE and unknown devices', () {
      expect(defaultPhonePrefixForCountry('AE'), '+971');
      expect(defaultPhonePrefixForCountry('US'), '+971');
      expect(defaultPhonePrefixForCountry(null), '+971');
    });
  });

  test('uses a matching validation example', () {
    expect(examplePhoneForPrefix('+91'), '+919876543210');
    expect(examplePhoneForPrefix('+971'), '+971501234567');
  });
}

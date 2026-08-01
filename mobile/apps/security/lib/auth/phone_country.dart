/// The countries a guard may hold a number in, and the national-number rules
/// that go with each.
///
/// The guard app is operated in the UAE and tested from India, so those are the
/// only two dialling codes offered. Picking from a list rather than typing the
/// code is what stops the two failure modes we actually saw at the gate: a
/// guard typing a local number with no country code at all, and a guard
/// deleting the `+` while editing — both of which reach the server as an opaque
/// 400 rather than a field error.
///
/// Adding a country is a one-line addition to [values]; nothing else branches
/// on the set.
library;

/// A dialling code plus enough shape to validate and illustrate it.
class PhoneCountry {
  const PhoneCountry({
    required this.isoCode,
    required this.dialCode,
    required this.flag,
    required this.nationalDigits,
    required this.exampleNational,
  });

  /// ISO 3166-1 alpha-2, matched against the device locale's country.
  final String isoCode;

  /// E.164 dialling code, including the leading '+'.
  final String dialCode;

  final String flag;

  /// Exact national-number lengths accepted, digits only (no dial code).
  /// Both countries are fixed-length, so a set of one is the normal case; it is
  /// a set so a country with several valid lengths does not need a new field.
  final Set<int> nationalDigits;

  /// A real-shaped national number for error messages and hints.
  final String exampleNational;

  /// The full E.164 number for [national], which must be digits only.
  String toE164(String national) => '$dialCode$national';

  /// A complete example number, for "e.g. …" text.
  String get exampleE164 => toE164(exampleNational);

  static const uae = PhoneCountry(
    isoCode: 'AE',
    dialCode: '+971',
    flag: '🇦🇪',
    // Mobile numbers are 9 digits after the code (5x xxx xxxx). Guards
    // habitually type the trunk '0' as well, which [stripTrunkPrefix] drops.
    nationalDigits: {9},
    exampleNational: '501234567',
  );

  static const india = PhoneCountry(
    isoCode: 'IN',
    dialCode: '+91',
    flag: '🇮🇳',
    nationalDigits: {10},
    exampleNational: '9876543210',
  );

  /// Offered in the picker, in order. UAE first: it is the production country.
  static const values = [uae, india];

  /// The country to preselect for a device in [countryCode]. Falls back to the
  /// UAE, which is where the app is deployed.
  static PhoneCountry forLocale(String? countryCode) {
    final upper = countryCode?.toUpperCase();
    return values.firstWhere((c) => c.isoCode == upper, orElse: () => uae);
  }

  /// The country owning [e164], or null if no offered code matches. Used to
  /// re-open an already-stored number in the picker rather than as text.
  static PhoneCountry? forE164(String e164) {
    // Longest dial code first, so '+971' is not shadowed by a shorter prefix.
    final byLength = [...values]
      ..sort((a, b) => b.dialCode.length.compareTo(a.dialCode.length));
    for (final country in byLength) {
      if (e164.startsWith(country.dialCode)) return country;
    }
    return null;
  }
}

/// Keeps only digits, then drops a single leading trunk '0'.
///
/// Both countries write local numbers with a trunk zero (055…, 098…) that must
/// not survive into E.164. Dropping it here means a guard can key the number
/// exactly as it appears on the visitor's phone.
String stripTrunkPrefix(String raw) {
  final digits = raw.replaceAll(RegExp(r'\D'), '');
  return digits.startsWith('0') ? digits.substring(1) : digits;
}

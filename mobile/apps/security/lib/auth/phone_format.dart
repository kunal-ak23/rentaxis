/// E.164 phone handling, kept deliberately identical to the server's
/// `PhoneNumbers.toE164` pattern on the backend.
///
/// The two must agree: the app sends what it normalizes here, and the backend
/// re-normalizes and answers 400 on anything that fails its own check. If this
/// drifts looser than the server, guards get an opaque 400 instead of a field
/// error; if it drifts tighter, they get rejected for numbers the server would
/// have accepted.
library;

/// Server-side pattern: a leading '+' then 8-15 digits, applied AFTER stripping
/// spaces and hyphens.
final RegExp _e164 = RegExp(r'^\+\d{8,15}$');

/// Strips the characters the server strips (spaces and hyphens) and nothing
/// else. Everything else is left in place so [isValidE164] can reject it,
/// rather than being silently scrubbed into a different number.
String normalizePhone(String raw) => raw.replaceAll(RegExp(r'[\s-]'), '');

/// True when [normalized] matches the server's E.164 pattern. Pass the output
/// of [normalizePhone] — this does not normalize for you.
bool isValidE164(String normalized) => _e164.hasMatch(normalized);

/// Prefills the country code for the two countries in which the security app
/// is currently operated and tested. Production remains UAE-first, while an
/// Indian device does not require the tester to replace `+971` by hand.
String defaultPhonePrefixForCountry(String? countryCode) {
  return switch (countryCode?.toUpperCase()) {
    'IN' => '+91',
    'AE' => '+971',
    _ => '+971',
  };
}

/// A locale-matched validation example for the supported country prefixes.
String examplePhoneForPrefix(String prefix) {
  return prefix == '+91' ? '+919876543210' : '+971501234567';
}

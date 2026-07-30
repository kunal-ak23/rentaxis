/// E.164 phone handling, kept deliberately identical to the server's
/// Backend `PhoneNumbers.toE164` pattern.
///
/// The two must agree: the app sends what it normalizes here, and the backend
/// re-normalizes and answers 400 on anything that fails its own check. If this
/// drifts looser than the server, renters get an opaque 400 instead of a field
/// error; if it drifts tighter, they get rejected for numbers the server would
/// have accepted.
///
/// **Duplicated from `apps/security/lib/auth/phone_format.dart`, on purpose.**
/// The two apps cannot import from each other and this is not worth a round trip
/// through `rentaxis_core` for four lines — but it does mean a change to the
/// server's pattern has to land in both copies. If a third caller ever appears,
/// promote it to core rather than making a third copy.
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

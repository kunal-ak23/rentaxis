/// Shared reading of the OTP endpoints' failure responses.
///
/// Lives in core because both sides of the guard login need it and they must not
/// drift: the guard app owns the copy for `POST /auth/otp/request` (see
/// `apps/security/lib/auth/otp_errors.dart`), while `POST /auth/otp/verify` is
/// consumed inside [AuthNotifier.loginWithOtp] and so its copy has to be decided
/// here. Both read the same two 429 shapes, so the reading is shared and only the
/// wording is split.
///
/// **The two 429 shapes.** The backend throttles OTP twice, and the two do not
/// answer alike:
///   * `OtpLoginService` throws `ResponseStatusException`, which
///     `GlobalExceptionHandler` renders as JSON `{"message": ...}`.
///   * `PublicRateLimitFilter` (per-IP, 10/min on `/api/auth/otp/**`) writes the
///     plain-text body `Rate limit exceeded`, ahead of any handler.
/// Anything reading the body must therefore survive a non-Map — see
/// [otpServerMessage].
library;

import 'package:dio/dio.dart';

/// True when a throttle is what failed, so callers can hold the UI in a wait
/// state rather than inviting the retry that only extends the throttle.
bool isOtpRateLimited(DioException e) => e.response?.statusCode == 429;

/// Reads the JSON `message` a `ResponseStatusException` carries through
/// `GlobalExceptionHandler`.
///
/// Defensive about the body type on purpose: the per-IP 429 is plain text, not
/// JSON, so indexing `data['message']` blindly would throw and turn a throttle
/// into an unhandled error. Anything that is not a JSON object with a non-empty
/// `message` returns null and lets the caller fall back to its own copy.
String? otpServerMessage(DioException e) {
  final data = e.response?.data;
  if (data is! Map) return null;
  final message = data['message'];
  if (message is! String || message.trim().isEmpty) return null;
  return message.trim();
}

/// True for the transport failures that are worth telling a guard to check their
/// network over, rather than their input.
bool isOtpNetworkError(DioException e) =>
    e.type == DioExceptionType.connectionError ||
    e.type == DioExceptionType.connectionTimeout ||
    e.type == DioExceptionType.receiveTimeout ||
    e.type == DioExceptionType.sendTimeout;

/// Copy for a failed `POST /auth/otp/verify`, as surfaced by
/// [AuthNotifier.loginWithOtp].
///
/// Takes [Object] rather than [DioException] because the caller catches the whole
/// login sequence — verify plus the `getTenants` call that follows it — so a
/// non-Dio error can reach here. Those keep the 401 wording, which is the
/// pre-existing behaviour for anything unrecognised.
///
/// **Why 429 must not read as "wrong code".** The per-phone cap is 10 failed
/// attempts per rolling hour (`OtpLoginService.MAX_ATTEMPTS_PER_HOUR`). Past it
/// every call answers 429 regardless of what is typed, so telling a locked-out
/// guard their code was invalid is an instruction to keep guessing at a gate
/// where nothing they type can work for the next hour.
///
/// **Why the server's own message is deliberately not used here.** Unlike the
/// request path — where [otpServerMessage] is the better copy — verify's 429
/// reads "Too many attempts. Please request a new code later.", and a new code
/// does not help: the cap counts *attempt* rows for the phone, and issuing
/// another code clears none of them. Repeating that message would send the guard
/// to the one action guaranteed not to unlock them.
String describeOtpVerifyError(Object error) {
  if (error is! DioException) return _invalidCode;

  if (isOtpRateLimited(error)) {
    // Wording covers both 429 sources without over-promising: the per-IP bucket
    // refills within the minute, the per-phone cap takes up to an hour. "Up to"
    // is true of both, and waiting is the correct action for either.
    return 'Too many attempts. Login is locked for up to an hour — please '
        'wait and try again. A new code will not unlock it.';
  }
  if (isOtpNetworkError(error)) {
    return 'No connection. Check your network and try again.';
  }
  return _invalidCode;
}

const String _invalidCode = 'Invalid or expired code';

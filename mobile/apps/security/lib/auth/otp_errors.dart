/// Maps `POST /auth/otp/request` failures to guard-facing copy.
///
/// A guard hitting these is standing at a gate with a queue behind them, so the
/// distinction that matters is "you did something wrong" vs "wait, the system is
/// holding you off" — a generic "login failed" for a 429 sends them into a retry
/// loop that only deepens the throttle.
///
/// The *reading* of a failure response lives in core
/// (`rentaxis_core/lib/api/otp_errors.dart`) and is shared with the verify path
/// inside [AuthNotifier.loginWithOtp]; only the wording is decided here. Both
/// paths face the same two 429 shapes — one JSON, one plain text — and a second
/// copy of that parser is exactly how the two would come to disagree about which
/// bodies are safe to index.
library;

import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Copy for a failed `POST /auth/otp/request` (Continue, and Resend).
///
/// 429 here is the issuance throttle: 3 requests per phone per 15 minutes
/// (`OtpLoginService.MAX_REQUESTS_PER_WINDOW` / `THROTTLE_WINDOW_MINUTES`), or
/// the per-IP bucket in `PublicRateLimitFilter`. Both are waits, not mistakes.
///
/// The server's own message is preferred here — unlike on verify, where it
/// misdirects; see `describeOtpVerifyError`.
String describeOtpRequestError(DioException e) {
  final status = e.response?.statusCode;

  if (status == 429) {
    return otpServerMessage(e) ??
        'Too many code requests. Please try again in about 15 minutes.';
  }
  if (status == 400) {
    // BusinessRuleViolationException — the phone failed the server's E.164
    // check. The client validator should have caught this already, so show the
    // server's own wording rather than guessing which rule tripped.
    return otpServerMessage(e) ??
        'Enter a full number with country code, e.g. +971501234567';
  }
  if (isOtpNetworkError(e)) {
    return 'No connection. Check your network and try again.';
  }
  return 'Could not send a code. Please try again.';
}

/// True when the throttle is what failed, so the caller can hold the UI in a
/// wait state rather than inviting an immediate retry.
bool isThrottled(DioException e) => isOtpRateLimited(e);

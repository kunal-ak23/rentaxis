/// Maps OTP endpoint failures to guard-facing copy.
///
/// A guard hitting these is standing at a gate with a queue behind them, so the
/// distinction that matters is "you did something wrong" vs "wait, the system is
/// holding you off" — a generic "login failed" for a 429 sends them into a retry
/// loop that only deepens the throttle.
library;

import 'package:dio/dio.dart';

/// Copy for a failed `POST /auth/otp/request` (Continue, and Resend).
///
/// 429 here is the issuance throttle: 3 requests per phone per 15 minutes
/// (`OtpLoginService.MAX_REQUESTS_PER_WINDOW` / `THROTTLE_WINDOW_MINUTES`), or
/// the per-IP bucket in `PublicRateLimitFilter`. Both are waits, not mistakes.
String describeOtpRequestError(DioException e) {
  final status = e.response?.statusCode;

  if (status == 429) {
    return _serverMessage(e) ??
        'Too many code requests. Please try again in about 15 minutes.';
  }
  if (status == 400) {
    // BusinessRuleViolationException — the phone failed the server's E.164
    // check. The client validator should have caught this already, so show the
    // server's own wording rather than guessing which rule tripped.
    return _serverMessage(e) ??
        'Enter a full number with country code, e.g. +971501234567';
  }
  if (_isNetwork(e)) {
    return 'No connection. Check your network and try again.';
  }
  return 'Could not send a code. Please try again.';
}

/// True when the throttle is what failed, so the caller can hold the UI in a
/// wait state rather than inviting an immediate retry.
bool isThrottled(DioException e) => e.response?.statusCode == 429;

/// Reads the JSON `message` a `ResponseStatusException` carries through
/// `GlobalExceptionHandler`.
///
/// Defensive about the body type on purpose: `PublicRateLimitFilter` writes a
/// plain-text `Rate limit exceeded` for the per-IP 429, not JSON, so indexing
/// `data['message']` blindly would throw and turn a throttle into an unhandled
/// error. Anything that is not a JSON object with a non-empty `message` returns
/// null and lets the caller fall back to its own copy.
String? _serverMessage(DioException e) {
  final data = e.response?.data;
  if (data is! Map) return null;
  final message = data['message'];
  if (message is! String || message.trim().isEmpty) return null;
  return message.trim();
}

bool _isNetwork(DioException e) =>
    e.type == DioExceptionType.connectionError ||
    e.type == DioExceptionType.connectionTimeout ||
    e.type == DioExceptionType.receiveTimeout ||
    e.type == DioExceptionType.sendTimeout;

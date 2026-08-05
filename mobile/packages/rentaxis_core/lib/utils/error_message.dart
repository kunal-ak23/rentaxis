import 'dart:convert';

import 'package:dio/dio.dart';

/// Extracts a user-facing message from a caught error.
///
/// The app's backend services document two response shapes for a failed
/// call: the usual `{error: true, message, ...}` envelope, and some
/// endpoints' bare `{error: "message text", ...}` shape (e.g. the booking
/// endpoints' 409) — so this prefers `message` when it is a String, else
/// `error` when it is a String, else falls back to a generic localized
/// message. A response body that arrived as a raw (unparsed) JSON string is
/// decoded first; anything else non-JSON (HTML, empty) is never surfaced
/// directly, and a non-Dio error always falls back.
///
/// Shared across apps (originally lived on the manager app's facilities
/// screens) — kept `BuildContext`-free so it stays trivially unit testable.
String errorMessage(Object error, String fallback) {
  if (error is DioException) {
    final body = _asErrorBody(error.response?.data);
    if (body != null) {
      final message = body['message'];
      if (message is String && message.isNotEmpty) return message;
      final err = body['error'];
      if (err is String && err.isNotEmpty) return err;
    }
  }
  return fallback;
}

Map<String, dynamic>? _asErrorBody(Object? data) {
  if (data is Map) return Map<String, dynamic>.from(data);
  if (data is String && data.trim().isNotEmpty) {
    try {
      final decoded = jsonDecode(data);
      if (decoded is Map) return Map<String, dynamic>.from(decoded);
    } catch (_) {
      // Not JSON (e.g. a proxy error page) — caller uses the generic
      // fallback rather than surfacing raw HTML/text.
    }
  }
  return null;
}

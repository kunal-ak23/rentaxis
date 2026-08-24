import 'dart:async';

import 'package:dio/dio.dart';

/// Which app is asking. Each app passes its own id in — nothing here hardcodes
/// one, so the shared gate serves the renter, manager and guard apps equally.
/// [wire] is the exact query value the backend contract expects.
enum AppId {
  renter('RENTER'),
  manager('MANAGER'),
  security('SECURITY');

  const AppId(this.wire);

  /// Value sent as `?app=` — must match the backend enum exactly.
  final String wire;
}

/// The platform half of the contract's key. Kept a plain string on the wire
/// (`ANDROID` / `IOS`) so the caller can derive it from `dart:io` Platform
/// without this file importing `dart:io` (which breaks widget tests).
class AppPlatform {
  const AppPlatform._();

  static const android = 'ANDROID';
  static const ios = 'IOS';
}

/// One row of the app-version contract. Every field has a permissive default so
/// a partial or surprising body can never produce a value that gates a user
/// out: a missing `minSupportedBuild` reads as 0 (nothing gated), a missing
/// url reads as '' (no link, just "please update").
class AppVersionInfo {
  const AppVersionInfo({
    required this.minSupportedBuild,
    required this.latestBuild,
    required this.latestVersionName,
    required this.storeUrl,
  });

  /// Builds below this are hard-blocked. 0 means nothing is ever blocked — the
  /// seed value, and the safe default for anything unparseable.
  final int minSupportedBuild;

  /// The newest build the store carries. Builds at or above [minSupportedBuild]
  /// but below this get a soft "update available" nudge.
  final int latestBuild;

  final String latestVersionName;

  /// Where to send the user to update. '' means the distribution channel is
  /// undecided — the client shows no link and just tells them to update.
  final String storeUrl;

  /// The permissive fallback: nothing is gated. Returned for any body the
  /// client can't make sense of, mirroring the backend's own fallback so the
  /// two ends agree on "safe = 0".
  static const permissive = AppVersionInfo(
    minSupportedBuild: 0,
    latestBuild: 0,
    latestVersionName: '',
    storeUrl: '',
  );

  factory AppVersionInfo.fromJson(Map<String, dynamic> json) => AppVersionInfo(
        minSupportedBuild: _asInt(json['minSupportedBuild']),
        latestBuild: _asInt(json['latestBuild']),
        latestVersionName: _asString(json['latestVersionName']),
        storeUrl: _asString(json['storeUrl']),
      );

  static int _asInt(Object? v) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v) ?? 0;
    return 0;
  }

  static String _asString(Object? v) => v is String ? v : '';
}

/// Reads the store-agnostic app-version floor from the public backend endpoint.
///
///   GET /v1/public/app-version?app={RENTER|MANAGER|SECURITY}&platform={ANDROID|IOS}
///
/// This is a gate-path call: it must NEVER throw and must NEVER hang. Any error,
/// timeout or non-200 resolves to `null`, which the provider reads as "fail
/// open" (proceed). The endpoint is public — it works before login — so the
/// gate can run on the very first launch.
class AppVersionService {
  AppVersionService(this._dio, {Duration? timeout})
      : _timeout = timeout ?? const Duration(seconds: 3);

  final Dio _dio;
  final Duration _timeout;

  /// Returns the configured row, or `null` on any failure. The whole call is
  /// bounded by [_timeout] regardless of the shared Dio's longer connect
  /// timeout, so a dead network fails open in ~3s rather than ~15s.
  Future<AppVersionInfo?> fetch({
    required AppId app,
    required String platform,
  }) {
    return _fetch(app: app, platform: platform)
        .timeout(_timeout, onTimeout: () => null);
  }

  Future<AppVersionInfo?> _fetch({
    required AppId app,
    required String platform,
  }) async {
    try {
      final response = await _dio.get<Object?>(
        '/v1/public/app-version',
        queryParameters: {'app': app.wire, 'platform': platform},
        options: Options(
          receiveTimeout: _timeout,
          sendTimeout: _timeout,
        ),
      );
      if (response.statusCode != 200) return null;
      final data = response.data;
      if (data is! Map) return null;
      return AppVersionInfo.fromJson(Map<String, dynamic>.from(data));
    } catch (_) {
      // Timeouts, connection errors, 5xx, malformed bodies — all fail open.
      return null;
    }
  }
}

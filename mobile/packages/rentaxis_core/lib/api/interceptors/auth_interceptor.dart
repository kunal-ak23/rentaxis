import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class AuthInterceptor extends Interceptor {
  final FlutterSecureStorage _storage;

  AuthInterceptor(this._storage);

  /// Called once when the server rejects a request the app believed was
  /// authenticated. Apps wire this to logout; their router already redirects
  /// to /login when auth state flips, so no navigation happens in here.
  ///
  /// There is no token-refresh call anywhere in the codebase, so an expired or
  /// server-revoked JWT used to leave every screen showing its own generic
  /// "load failed" with a retry that re-sent the same stale token and failed
  /// identically. The only way back was finding Profile > Logout by hand.
  static void Function()? onUnauthorized;

  /// A burst of parallel requests all 401 at once. Fire the callback for the
  /// first and stay quiet until something succeeds again, so the app logs out
  /// once rather than once per in-flight request.
  static bool _signalled = false;

  /// Test seam: lets a test start from a known state.
  static void resetUnauthorizedSignal() => _signalled = false;

  /// A 401 from a sign-in attempt means wrong credentials, not an expired
  /// session — logging out over it would be nonsense, and would clear the
  /// login screen's own error handling out from under it.
  static bool _isSignInAttempt(String path) =>
      path.endsWith('/auth/login') || path.endsWith('/auth/apple');

  @override
  Future<void> onRequest(
      RequestOptions options, RequestInterceptorHandler handler) async {
    final authToken = await _storage.read(key: 'authToken');
    final userId = await _storage.read(key: 'userId');
    final userRole = await _storage.read(key: 'userRole');
    final tenantId = await _storage.read(key: 'tenantId');
    final userTenantId = await _storage.read(key: 'userTenantId');

    // Signed JWT (auth hardening phase 1). The backend prefers this over the
    // legacy X-User-* headers when both are present and token verification is
    // enabled. The legacy headers below are still sent: a just-updated app may
    // talk to a not-yet-redeployed backend that only understands them, and
    // they are harmless once the backend prefers the Bearer token.
    if (authToken != null) {
      options.headers['Authorization'] = 'Bearer $authToken';
    }
    if (userId != null) options.headers['X-User-Id'] = userId;
    if (userRole != null) options.headers['X-User-Role'] = userRole;
    if (tenantId != null) options.headers['X-Tenant-Id'] = tenantId;
    if (userTenantId != null) {
      options.headers['X-User-Tenant-Id'] = userTenantId;
    }

    handler.next(options);
  }

  @override
  void onResponse(Response response, ResponseInterceptorHandler handler) {
    _signalled = false;
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    if (err.response?.statusCode == 401 &&
        !_isSignInAttempt(err.requestOptions.path) &&
        !_signalled) {
      _signalled = true;
      onUnauthorized?.call();
    }
    // Always pass the error through: screens keep their own handling, and the
    // logout is an addition to it rather than a replacement.
    handler.next(err);
  }
}

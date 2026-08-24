import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class AuthInterceptor extends Interceptor {
  final FlutterSecureStorage _storage;

  AuthInterceptor(this._storage);

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
  void onError(DioException err, ErrorInterceptorHandler handler) {
    handler.next(err);
  }
}

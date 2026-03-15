import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class AuthInterceptor extends Interceptor {
  final FlutterSecureStorage _storage;

  AuthInterceptor(this._storage);

  @override
  Future<void> onRequest(
      RequestOptions options, RequestInterceptorHandler handler) async {
    final userId = await _storage.read(key: 'userId');
    final userRole = await _storage.read(key: 'userRole');
    final tenantId = await _storage.read(key: 'tenantId');
    final userTenantId = await _storage.read(key: 'userTenantId');

    if (userId != null) options.headers['X-User-Id'] = userId;
    if (userRole != null) options.headers['X-User-Role'] = userRole;
    if (tenantId != null) options.headers['X-Tenant-Id'] = tenantId;
    if (userTenantId != null) {
      options.headers['X-User-Tenant-Id'] = userTenantId;
    }

    handler.next(options);
  }
}

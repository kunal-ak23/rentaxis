import 'package:dio/dio.dart';
import '../tenant_context.dart';

class TenantInterceptor extends Interceptor {
  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    final tenantId = TenantContext.currentTenantId;
    if (tenantId != null) {
      options.headers['X-Tenant-Id'] = tenantId;
    }
    handler.next(options);
  }
}

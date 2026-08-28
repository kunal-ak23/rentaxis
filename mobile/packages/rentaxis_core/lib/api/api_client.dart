import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'interceptors/auth_interceptor.dart';
import 'interceptors/tenant_interceptor.dart';

class ApiClient {
  late final Dio dio;
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  // Base URL configurable per environment. Overridable at build time for
  // local-stack testing: --dart-define=API_BASE_URL=http://localhost:8080/api
  // (Android emulator: http://10.0.2.2:8080/api). Defaults to prod.
  static const String _defaultBaseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'https://rentaxis.uaenorth.cloudapp.azure.com/api',
  );
  // Capture and cold-start environments can be slower than the normal mobile
  // budget. Keep the production default at 15 seconds, but allow an explicit
  // build-time override for deterministic E2E/tutorial runs.
  static const int _requestTimeoutSeconds = int.fromEnvironment(
    'API_TIMEOUT_SECONDS',
    defaultValue: 15,
  );

  ApiClient({String? baseUrl}) {
    dio = Dio(
      BaseOptions(
        baseUrl: baseUrl ?? _defaultBaseUrl,
        connectTimeout: Duration(seconds: _requestTimeoutSeconds),
        receiveTimeout: Duration(seconds: _requestTimeoutSeconds),
        headers: {'Content-Type': 'application/json'},
      ),
    );

    dio.interceptors.add(AuthInterceptor(_storage));
    dio.interceptors.add(TenantInterceptor());
    if (kDebugMode) {
      dio.interceptors.add(
        LogInterceptor(requestBody: true, responseBody: true),
      );
    }
  }
}

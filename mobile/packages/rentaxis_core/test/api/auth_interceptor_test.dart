import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/interceptors/auth_interceptor.dart';

/// Stubs flutter_secure_storage with an in-memory map (optionally seeded) and
/// returns the map so tests can assert what got persisted.
Map<String, String> _stubSecureStorage([Map<String, String>? seed]) {
  final store = <String, String>{...?seed};
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
        const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
        (call) async {
          switch (call.method) {
            case 'read':
              return store[call.arguments['key'] as String];
            case 'write':
              store[call.arguments['key'] as String] =
                  call.arguments['value'] as String;
              return null;
            case 'delete':
              store.remove(call.arguments['key'] as String);
              return null;
            case 'readAll':
              return store;
            default:
              return null;
          }
        },
      );
  return store;
}

Future<RequestOptions> _runInterceptor() async {
  final interceptor = AuthInterceptor(const FlutterSecureStorage());
  final options = RequestOptions(path: '/v1/anything');
  await interceptor.onRequest(options, RequestInterceptorHandler());
  return options;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('AuthInterceptor Authorization header', () {
    test('adds Bearer when storage holds an authToken, keeping legacy headers',
        () async {
      _stubSecureStorage({
        'authToken': 'jwt-abc',
        'userId': 'user-1',
        'userRole': 'RENTER',
        'tenantId': 'tenant-1',
        'userTenantId': 'tenant-1',
      });

      final options = await _runInterceptor();

      expect(options.headers['Authorization'], 'Bearer jwt-abc');
      // Legacy headers must survive: a just-updated app may talk to a
      // not-yet-redeployed backend that only understands the header path.
      expect(options.headers['X-User-Id'], 'user-1');
      expect(options.headers['X-User-Role'], 'RENTER');
      expect(options.headers['X-Tenant-Id'], 'tenant-1');
      expect(options.headers['X-User-Tenant-Id'], 'tenant-1');
    });

    test('omits Authorization when no authToken is stored (pre-JWT session)',
        () async {
      _stubSecureStorage({
        'userId': 'user-1',
        'userRole': 'RENTER',
        'tenantId': 'tenant-1',
        'userTenantId': 'tenant-1',
      });

      final options = await _runInterceptor();

      expect(options.headers.containsKey('Authorization'), isFalse);
      expect(options.headers['X-User-Id'], 'user-1');
      expect(options.headers['X-User-Role'], 'RENTER');
      expect(options.headers['X-Tenant-Id'], 'tenant-1');
      expect(options.headers['X-User-Tenant-Id'], 'tenant-1');
    });
  });
}

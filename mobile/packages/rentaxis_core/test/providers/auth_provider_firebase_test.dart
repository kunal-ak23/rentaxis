import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/auth_service.dart';
import 'package:rentaxis_core/models/auth_response.dart';
import 'package:rentaxis_core/providers/auth_provider.dart';

class _FakeAuthService extends AuthService {
  _FakeAuthService({this.firebaseError}) : super(Dio());

  Object? firebaseError;
  final List<String> exchangedTokens = [];

  @override
  Future<AuthResponse> loginWithFirebase(String idToken) async {
    exchangedTokens.add(idToken);
    final error = firebaseError;
    if (error != null) throw error;
    return AuthResponse(
      id: 'guard-1',
      email: 'guard@example.com',
      name: 'Test Guard',
      role: 'SECURITY_GUARD',
      tenantId: 'tenant-1',
      tenantIds: const ['tenant-1'],
    );
  }

  @override
  Future<List<dynamic>> getTenants() async => [];
}

void _stubSecureStorage() {
  final store = <String, String>{};
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
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(_stubSecureStorage);

  group('AuthNotifier.loginWithFirebase', () {
    test('establishes the session on success', () async {
      final service = _FakeAuthService();
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);

      final success = await notifier.loginWithFirebase('signed-token');

      expect(success, isTrue);
      expect(service.exchangedTokens, ['signed-token']);
      expect(notifier.state.isAuthenticated, isTrue);
      expect(notifier.state.error, isNull);
    });

    test(
      'rejects a token the backend will not map to an active guard',
      () async {
        final request = RequestOptions(path: '/v1/auth/firebase');
        final notifier = AuthNotifier(
          _FakeAuthService(
            firebaseError: DioException(
              requestOptions: request,
              response: Response<void>(
                requestOptions: request,
                statusCode: 401,
              ),
              type: DioExceptionType.badResponse,
            ),
          ),
        );
        addTearDown(notifier.dispose);

        final success = await notifier.loginWithFirebase('rejected-token');

        expect(success, isFalse);
        expect(notifier.state.isAuthenticated, isFalse);
        expect(
          notifier.state.error,
          'This phone is not assigned to an active security guard.',
        );
      },
    );
  });
}

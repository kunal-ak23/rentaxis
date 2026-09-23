import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/auth_service.dart';
import 'package:rentaxis_core/api/tenant_context.dart';
import 'package:rentaxis_core/models/auth_response.dart';
import 'package:rentaxis_core/providers/auth_provider.dart';

class _FakeAuthService extends AuthService {
  _FakeAuthService({this.loginError, this.tenants = const []}) : super(Dio());

  /// Thrown by [login] when set; cleared by tests to simulate a retry.
  Object? loginError;

  /// GET /auth/me/tenants payload: TenantInfo maps with id/name/slug keys.
  List<dynamic> tenants;

  final List<String?> loginTenantIds = [];

  @override
  Future<AuthResponse> login(
    String email,
    String password, {
    String? tenantId,
  }) async {
    loginTenantIds.add(tenantId);
    final error = loginError;
    if (error != null) throw error;
    return AuthResponse(
      id: 'user-1',
      email: email,
      name: 'Test User',
      role: 'TENANT_ADMIN',
      tenantId: tenantId ?? 'tenant-a',
      tenantIds: const ['tenant-a'],
    );
  }

  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'user-1',
    'email': 'user@example.com',
    'name': 'Test User',
    'role': 'TENANT_ADMIN',
  };

  @override
  Future<List<dynamic>> getTenants() async => tenants;
}

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

Future<void> _settle(AuthNotifier notifier) async {
  while (notifier.state.isLoading) {
    await Future<void>.delayed(const Duration(milliseconds: 1));
  }
}

DioException _ambiguousLogin409() {
  final request = RequestOptions(path: '/auth/login');
  return DioException(
    requestOptions: request,
    response: Response<Map<String, dynamic>>(
      requestOptions: request,
      statusCode: 409,
      data: {
        'tenants': [
          {'tenantId': 't-1', 'tenantName': 'Org One'},
          {'tenantId': 't-2', 'tenantName': 'Org Two'},
        ],
      },
    ),
    type: DioExceptionType.badResponse,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TenantContext.currentTenantId = null;
  });

  group('AuthNotifier.login multi-tenant disambiguation', () {
    test('surfaces the 409 candidate tenants instead of "invalid password"', () async {
      _stubSecureStorage();
      final service = _FakeAuthService(loginError: _ambiguousLogin409());
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      final success = await notifier.login('user@example.com', 'secret');

      expect(success, isFalse);
      expect(notifier.state.isAuthenticated, isFalse);
      expect(notifier.state.error, isNot('Invalid email or password'));
      expect(notifier.state.error, contains('multiple organizations'));
      expect(notifier.state.tenantChoices, hasLength(2));
      expect(notifier.state.tenantChoices.first['tenantId'], 't-1');
      expect(notifier.state.tenantChoices.first['tenantName'], 'Org One');
    });

    test('re-submitting with a chosen tenantId completes the login', () async {
      _stubSecureStorage();
      final service = _FakeAuthService(loginError: _ambiguousLogin409());
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      await notifier.login('user@example.com', 'secret');
      service.loginError = null;

      final success = await notifier.login(
        'user@example.com',
        'secret',
        tenantId: 't-2',
      );

      expect(success, isTrue);
      expect(service.loginTenantIds, [null, 't-2']);
      expect(notifier.state.isAuthenticated, isTrue);
      expect(notifier.state.tenantId, 't-2');
      expect(notifier.state.tenantChoices, isEmpty);
      expect(notifier.state.error, isNull);
    });

    test('non-409 failures keep the generic invalid-credentials error', () async {
      _stubSecureStorage();
      final request = RequestOptions(path: '/auth/login');
      final service = _FakeAuthService(
        loginError: DioException(
          requestOptions: request,
          response: Response<void>(requestOptions: request, statusCode: 401),
          type: DioExceptionType.badResponse,
        ),
      );
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      final success = await notifier.login('user@example.com', 'wrong');

      expect(success, isFalse);
      expect(notifier.state.error, 'Invalid email or password');
      expect(notifier.state.tenantChoices, isEmpty);
    });

    Future<AuthNotifier> failLogin(int status, [Object? body]) async {
      _stubSecureStorage();
      final request = RequestOptions(path: '/auth/login');
      final service = _FakeAuthService(
        loginError: DioException(
          requestOptions: request,
          response: Response<Object?>(
              requestOptions: request, statusCode: status, data: body),
          type: DioExceptionType.badResponse,
        ),
      );
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);
      expect(await notifier.login('user@example.com', 'right'), isFalse);
      return notifier;
    }

    test('a deactivated account shows the server message, not "invalid password"', () async {
      final notifier = await failLogin(403, {
        'error': 'ACCOUNT_INACTIVE',
        'message': 'This account has been deactivated. Contact your administrator.',
      });
      expect(notifier.state.error,
          'This account has been deactivated. Contact your administrator.');
    });

    test('a 403 without a message still does not blame the password', () async {
      final notifier = await failLogin(403);
      expect(notifier.state.error, isNot('Invalid email or password'));
    });

    test('the login rate limit asks the user to wait', () async {
      final notifier = await failLogin(429);
      expect(notifier.state.error,
          'Too many sign-in attempts. Wait a minute and try again.');
    });
  });

  group('AuthNotifier session restore tenant fallback', () {
    test('falls back to tenants[0].id (not the absent tenantId key) and persists it', () async {
      // userId saved but no tenantId — the AuthResponse.tenantId-was-null path.
      final store = _stubSecureStorage({'userId': 'user-1'});
      final service = _FakeAuthService(
        tenants: [
          {'id': 'tenant-a', 'name': 'Org A', 'slug': 'org-a'},
        ],
      );
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      expect(notifier.state.isAuthenticated, isTrue);
      expect(notifier.state.tenantId, 'tenant-a');
      expect(TenantContext.currentTenantId, 'tenant-a');
      // Persisted so the next restore agrees with this session.
      expect(store['tenantId'], 'tenant-a');
    });

    test('a saved tenantId still wins over the fallback', () async {
      final store = _stubSecureStorage({
        'userId': 'user-1',
        'tenantId': 'tenant-saved',
      });
      final service = _FakeAuthService(
        tenants: [
          {'id': 'tenant-a', 'name': 'Org A', 'slug': 'org-a'},
        ],
      );
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      expect(notifier.state.tenantId, 'tenant-saved');
      expect(TenantContext.currentTenantId, 'tenant-saved');
      expect(store['tenantId'], 'tenant-saved');
    });
  });
}

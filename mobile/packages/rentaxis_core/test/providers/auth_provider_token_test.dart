import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/auth_service.dart';
import 'package:rentaxis_core/api/tenant_context.dart';
import 'package:rentaxis_core/models/auth_response.dart';
import 'package:rentaxis_core/providers/auth_provider.dart';

class _FakeAuthService extends AuthService {
  _FakeAuthService({this.token, this.firebaseToken}) : super(Dio());

  /// Token the fake backend puts on the password-login response; null models
  /// an old backend that does not issue JWTs yet.
  String? token;

  /// Token on the Firebase-exchange response.
  String? firebaseToken;

  @override
  Future<AuthResponse> login(
    String email,
    String password, {
    String? tenantId,
  }) async {
    return AuthResponse(
      id: 'user-1',
      email: email,
      name: 'Test User',
      role: 'TENANT_ADMIN',
      tenantId: tenantId ?? 'tenant-a',
      tenantIds: const ['tenant-a'],
      token: token,
    );
  }

  @override
  Future<AuthResponse> loginWithFirebase(String idToken) async {
    return AuthResponse(
      id: 'guard-1',
      email: 'guard@example.com',
      name: 'Test Guard',
      role: 'SECURITY_GUARD',
      tenantId: 'tenant-1',
      tenantIds: const ['tenant-1'],
      token: firebaseToken,
    );
  }

  @override
  Future<List<dynamic>> getTenants() async => [];
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

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TenantContext.currentTenantId = null;
  });

  group('authToken lifecycle', () {
    test('password login persists the token under authToken', () async {
      final store = _stubSecureStorage();
      final service = _FakeAuthService(token: 'jwt-abc');
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      final success = await notifier.login('user@example.com', 'secret');

      expect(success, isTrue);
      expect(store['authToken'], 'jwt-abc');
      // Identity keys are written alongside — same lifecycle.
      expect(store['userId'], 'user-1');
    });

    test('firebase login persists the token under authToken', () async {
      final store = _stubSecureStorage();
      final service = _FakeAuthService(firebaseToken: 'jwt-guard');
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      final success = await notifier.loginWithFirebase('firebase-id-token');

      expect(success, isTrue);
      expect(store['authToken'], 'jwt-guard');
      expect(store['userId'], 'guard-1');
    });

    test('a tokenless login (old backend) clears any stale authToken',
        () async {
      final store = _stubSecureStorage({'authToken': 'stale-jwt'});
      final service = _FakeAuthService(token: null);
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      final success = await notifier.login('user@example.com', 'secret');

      expect(success, isTrue);
      // A token from a previous session must never outlive that session's
      // identity: storage must not pair the new userId with the old JWT.
      expect(store.containsKey('authToken'), isFalse);
      expect(store['userId'], 'user-1');
    });

    test('logout deletes authToken along with the identity keys', () async {
      final store = _stubSecureStorage();
      final service = _FakeAuthService(token: 'jwt-abc');
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      await notifier.login('user@example.com', 'secret');
      expect(store['authToken'], 'jwt-abc');

      await notifier.logout();

      expect(store.containsKey('authToken'), isFalse);
      expect(store.containsKey('userId'), isFalse);
      expect(notifier.state.isAuthenticated, isFalse);
    });

    test('switchTenant keeps the token (it carries tids; only the active '
        'X-Tenant-Id selector changes)', () async {
      final store = _stubSecureStorage();
      final service = _FakeAuthService(token: 'jwt-abc');
      final notifier = AuthNotifier(service);
      addTearDown(notifier.dispose);
      await _settle(notifier);

      await notifier.login('user@example.com', 'secret');

      await notifier.switchTenant('tenant-b');

      expect(store['authToken'], 'jwt-abc');
      expect(store['tenantId'], 'tenant-b');
      expect(notifier.state.tenantId, 'tenant-b');
    });
  });
}

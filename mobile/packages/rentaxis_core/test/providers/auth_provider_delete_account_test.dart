import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/auth_service.dart';
import 'package:rentaxis_core/api/tenant_context.dart';
import 'package:rentaxis_core/providers/auth_provider.dart';

class _FakeAuthService extends AuthService {
  _FakeAuthService({this.deleteError}) : super(Dio());

  Object? deleteError;
  int deleteCalls = 0;

  @override
  Future<void> deleteAccount() async {
    deleteCalls++;
    final error = deleteError;
    if (error != null) throw error;
  }

  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'user-1',
    'email': 'user@example.com',
    'name': 'Test User',
    'role': 'RENTER',
  };

  @override
  Future<List<dynamic>> getTenants() async => [
    {'id': 'tenant-a', 'name': 'Org', 'slug': 'org'},
  ];
}

/// In-memory flutter_secure_storage seeded with a signed-in session.
Map<String, String> _stubSignedInStorage() {
  final store = <String, String>{
    'userId': 'user-1',
    'userRole': 'RENTER',
    'tenantId': 'tenant-a',
    'authToken': 'jwt',
  };
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

DioException _http(int status, {Map<String, dynamic>? body}) {
  final request = RequestOptions(path: '/v1/account');
  return DioException(
    requestOptions: request,
    response: Response<Map<String, dynamic>>(
      requestOptions: request,
      statusCode: status,
      data: body,
    ),
    type: DioExceptionType.badResponse,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  tearDown(() => TenantContext.currentTenantId = null);

  test('success clears the session and the stored identity', () async {
    final store = _stubSignedInStorage();
    final service = _FakeAuthService();
    final notifier = AuthNotifier(service);
    addTearDown(notifier.dispose);
    await _settle(notifier);
    expect(notifier.state.isAuthenticated, isTrue);

    final error = await notifier.deleteAccount();

    expect(error, isNull);
    expect(service.deleteCalls, 1);
    expect(notifier.state.isAuthenticated, isFalse);
    expect(store.containsKey('userId'), isFalse);
    expect(store.containsKey('authToken'), isFalse);
    expect(TenantContext.currentTenantId, isNull);
  });

  test('a refusal keeps the user signed in and surfaces the reason', () async {
    _stubSignedInStorage();
    final service = _FakeAuthService(
      deleteError: _http(400, body: {
        'message': 'You are the only administrator of this organisation.',
        'error': true,
        'status': 400,
      }),
    );
    final notifier = AuthNotifier(service);
    addTearDown(notifier.dispose);
    await _settle(notifier);

    final error = await notifier.deleteAccount();

    expect(error, 'You are the only administrator of this organisation.');
    expect(notifier.state.isAuthenticated, isTrue);
  });

  test('403 explains the account is managed outside the app', () async {
    _stubSignedInStorage();
    final notifier = AuthNotifier(_FakeAuthService(deleteError: _http(403)));
    addTearDown(notifier.dispose);
    await _settle(notifier);

    final error = await notifier.deleteAccount();

    expect(error, contains('cannot be deleted from the app'));
    expect(notifier.state.isAuthenticated, isTrue);
  });

  test('a network failure never signs the user out of an account that still exists', () async {
    _stubSignedInStorage();
    final service = _FakeAuthService(
      deleteError: DioException(
        requestOptions: RequestOptions(path: '/v1/account'),
        type: DioExceptionType.connectionError,
      ),
    );
    final notifier = AuthNotifier(service);
    addTearDown(notifier.dispose);
    await _settle(notifier);

    final error = await notifier.deleteAccount();

    expect(error, contains('No connection'));
    expect(notifier.state.isAuthenticated, isTrue);
  });
}

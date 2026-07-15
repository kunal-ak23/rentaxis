import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/otp_errors.dart';
import 'package:rentaxis_core/api/services/auth_service.dart';
import 'package:rentaxis_core/models/auth_response.dart';
import 'package:rentaxis_core/providers/auth_provider.dart';

/// Records verify calls and replays a canned failure.
class _FakeAuthService extends AuthService {
  _FakeAuthService({this.verifyOtpError}) : super(Dio());

  Object? verifyOtpError;

  @override
  Future<AuthResponse> verifyOtp(String phone, String code) async {
    final error = verifyOtpError;
    if (error != null) throw error;
    return AuthResponse(
      id: 'guard-1',
      email: 'guard@example.com',
      name: 'Test Guard',
      role: 'SECURITY_GUARD',
      tenantId: 'tenant-1',
      tenantIds: ['tenant-1'],
    );
  }

  @override
  Future<List<dynamic>> getTenants() async => [];
}

/// [AuthNotifier]'s constructor reads secure storage; without this the channel
/// throws MissingPluginException and `_init` resolves through its catch instead
/// of the "no stored session" path.
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

/// [data] is `Object?` on purpose: the two 429 sources do not agree on a body.
/// `OtpLoginService` throws `ResponseStatusException`, rendered as JSON
/// `{"message": ...}`; `PublicRateLimitFilter` writes plain text.
DioException _httpError(int status, {Object? data}) {
  final options = RequestOptions(path: '/auth/otp/verify');
  return DioException(
    requestOptions: options,
    response: Response<Object?>(
      requestOptions: options,
      statusCode: status,
      data: data,
    ),
    type: DioExceptionType.badResponse,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(_stubSecureStorage);

  group('describeOtpVerifyError', () {
    test('429 reports a lockout and a wait, never "invalid code"', () {
      final message = describeOtpVerifyError(_httpError(429, data: {
        'message': 'Too many attempts. Please request a new code later.',
      }));

      expect(message, isNot(contains('Invalid')));
      expect(message.toLowerCase(), contains('locked'));
      expect(message.toLowerCase(), contains('wait'));
    });

    test('429 does not repeat the server\'s "request a new code" misdirection',
        () {
      // The cap counts attempt rows for the phone, so issuing another code
      // clears nothing — echoing the server here would send a locked-out guard
      // to the one action guaranteed not to help.
      final message = describeOtpVerifyError(_httpError(429, data: {
        'message': 'Too many attempts. Please request a new code later.',
      }));

      expect(message, contains('A new code will not unlock it.'));
    });

    test('survives the plain-text 429 body PublicRateLimitFilter writes', () {
      // Not JSON: indexing data['message'] on this would throw and turn a
      // throttle into an unhandled error.
      final message =
          describeOtpVerifyError(_httpError(429, data: 'Rate limit exceeded'));

      expect(message.toLowerCase(), contains('locked'));
    });

    test('401 keeps the invalid-code wording', () {
      expect(describeOtpVerifyError(_httpError(401)),
          'Invalid or expired code');
    });

    test('a non-Dio error falls back to the invalid-code wording', () {
      expect(describeOtpVerifyError(StateError('boom')),
          'Invalid or expired code');
    });

    test('a dropped connection blames the network, not the code', () {
      final error = DioException(
        requestOptions: RequestOptions(path: '/auth/otp/verify'),
        type: DioExceptionType.connectionError,
      );

      expect(describeOtpVerifyError(error), contains('No connection'));
    });
  });

  group('AuthNotifier.loginWithOtp', () {
    test('surfaces the lockout message on a 429', () async {
      final notifier = AuthNotifier(
        _FakeAuthService(verifyOtpError: _httpError(429, data: {
          'message': 'Too many attempts. Please request a new code later.',
        })),
      );
      addTearDown(notifier.dispose);

      final success = await notifier.loginWithOtp('+971501234567', '123456');

      expect(success, isFalse);
      expect(notifier.state.isAuthenticated, isFalse);
      expect(notifier.state.isLoading, isFalse);
      expect(notifier.state.error, isNot('Invalid or expired code'));
      expect(notifier.state.error!.toLowerCase(), contains('locked'));
    });

    test('surfaces the invalid-code message on a 401', () async {
      final notifier = AuthNotifier(
        _FakeAuthService(verifyOtpError: _httpError(401)),
      );
      addTearDown(notifier.dispose);

      final success = await notifier.loginWithOtp('+971501234567', '123456');

      expect(success, isFalse);
      expect(notifier.state.error, 'Invalid or expired code');
    });

    test('establishes the session on success', () async {
      final notifier = AuthNotifier(_FakeAuthService());
      addTearDown(notifier.dispose);

      final success = await notifier.loginWithOtp('+971501234567', '123456');

      expect(success, isTrue);
      expect(notifier.state.isAuthenticated, isTrue);
      expect(notifier.state.error, isNull);
    });
  });
}

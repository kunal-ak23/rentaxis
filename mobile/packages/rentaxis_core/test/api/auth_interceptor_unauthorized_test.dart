import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/interceptors/auth_interceptor.dart';

/// Reacting to a 401 on a session the app believed was valid.
///
/// There is no token-refresh call anywhere in the codebase, so an expired or
/// server-revoked JWT left every screen showing its own generic "load failed"
/// with a retry that re-sent the same stale token and failed identically. The
/// interceptor's onError was a bare `handler.next(err)`.
///
/// Driven through a real Dio rather than by calling onError with a bare
/// ErrorInterceptorHandler: that handler completes a future nobody awaits, so
/// hand-calling it reports an unhandled async error instead of the behaviour
/// under test. Going through Dio also exercises the path the app actually uses.
class _StubAdapter implements HttpClientAdapter {
  _StubAdapter(this.statusFor);

  /// Status to return for a given request path.
  final int Function(RequestOptions options) statusFor;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    return ResponseBody.fromString(
      '{}',
      statusFor(options),
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

void _stubSecureStorage() {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
        const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
        (call) async => call.method == 'readAll' ? <String, String>{} : null,
      );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late List<String> signals;

  Dio dioReturning(int Function(RequestOptions) statusFor) {
    final dio = Dio(BaseOptions(baseUrl: 'https://example.invalid'));
    dio.interceptors.add(AuthInterceptor(const FlutterSecureStorage()));
    dio.httpClientAdapter = _StubAdapter(statusFor);
    return dio;
  }

  /// Returns the status the caller saw, proving the error still propagates.
  Future<int?> get(Dio dio, String path) async {
    try {
      await dio.get<dynamic>(path);
      return null;
    } on DioException catch (e) {
      return e.response?.statusCode;
    }
  }

  setUp(() {
    _stubSecureStorage();
    signals = [];
    AuthInterceptor.resetUnauthorizedSignal();
    AuthInterceptor.onUnauthorized = () => signals.add('logout');
  });

  tearDown(() {
    AuthInterceptor.onUnauthorized = null;
    AuthInterceptor.resetUnauthorizedSignal();
  });

  test('a 401 on an ordinary request signals the app to log out', () async {
    final dio = dioReturning((_) => 401);

    final seen = await get(dio, '/v1/leases');

    expect(signals, ['logout']);
    // The logout is an addition to each screen's own handling, not a
    // replacement — swallowing the error here would blank every screen.
    expect(seen, 401, reason: 'the error must still reach the caller');
  });

  test('a failed sign-in does not log the user out', () async {
    // A 401 from /auth/login means wrong credentials, not an expired session.
    // Reacting to it would clear the login screen's own error out from under it.
    final dio = dioReturning((_) => 401);

    await get(dio, '/auth/login');
    await get(dio, '/auth/apple');

    expect(signals, isEmpty);
  });

  test('other statuses are left alone', () async {
    for (final status in [400, 403, 404, 500, 503]) {
      final dio = dioReturning((_) => status);
      await get(dio, '/v1/leases');
    }

    expect(signals, isEmpty);
  });

  test('a burst of parallel 401s logs out once, not once per request', () async {
    final dio = dioReturning((_) => 401);

    await Future.wait([
      get(dio, '/v1/leases'),
      get(dio, '/v1/gate-passes'),
      get(dio, '/v1/bookings'),
      get(dio, '/v1/tickets'),
    ]);

    expect(signals, ['logout']);
  });

  test('a later 401 signals again once something has succeeded in between',
      () async {
    // A success means the session is live again (a fresh login), so the next
    // expiry has to be reported rather than suppressed forever.
    final dio = dioReturning(
      (options) => options.path.contains('ok') ? 200 : 401,
    );

    await get(dio, '/v1/leases');
    expect(signals, ['logout']);

    await get(dio, '/v1/ok');
    await get(dio, '/v1/leases');

    expect(signals, ['logout', 'logout']);
  });

  test('no callback wired is not an error', () async {
    AuthInterceptor.onUnauthorized = null;
    final dio = dioReturning((_) => 401);

    expect(await get(dio, '/v1/leases'), 401);
  });
}

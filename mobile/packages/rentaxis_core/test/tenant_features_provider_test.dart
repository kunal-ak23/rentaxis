import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Answers every request with whatever [responder] decides, so the whole Dio
/// stack (interceptors included) is exercised without a network.
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.responder);

  final Future<ResponseBody> Function(RequestOptions) responder;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) =>
      responder(options);
}

ProviderContainer _containerReturning(
  Future<ResponseBody> Function(RequestOptions) responder,
) {
  final client = ApiClient(baseUrl: 'https://api.example/api');
  client.dio.httpClientAdapter = _FakeAdapter(responder);
  return ProviderContainer(
    overrides: [apiClientProvider.overrideWithValue(client)],
  );
}

ResponseBody _json(String body) => ResponseBody.fromString(
      body,
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // ApiClient's AuthInterceptor reads the session from flutter_secure_storage
  // on every request; without a stubbed channel the plugin call throws and the
  // request never reaches the fake adapter.
  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, (call) async {
      if (call.method == 'readAll') return <String, String>{};
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, null);
  });

  test('reads the flag map the backend returns', () async {
    final c = _containerReturning(
      (_) async => _json('{"LISTINGS":true,"MOBILE_FINANCE":true,'
          '"GATEPASS":false}'),
    );
    addTearDown(c.dispose);

    expect(
      await c.read(tenantFeaturesProvider.future),
      containsPair('MOBILE_FINANCE', true),
    );
    expect(c.read(mobileFinanceEnabledProvider), isTrue);
  });

  test('calls the flat per-tenant features endpoint', () async {
    RequestOptions? seen;
    final c = _containerReturning((options) async {
      seen = options;
      return _json('{}');
    });
    addTearDown(c.dispose);

    await c.read(tenantFeaturesProvider.future);

    expect(seen?.path, '/v1/tenant/features');
    expect(seen?.method, 'GET');
  });

  test('fails closed when the backend cannot be reached', () async {
    final c = _containerReturning(
      (_) async => throw DioException(
        requestOptions: RequestOptions(path: '/v1/tenant/features'),
        message: 'offline',
      ),
    );
    addTearDown(c.dispose);

    expect(await c.read(tenantFeaturesProvider.future), isEmpty);
    expect(c.read(mobileFinanceEnabledProvider), isFalse);
  });

  test('an absent flag reads as off, never as on', () async {
    final c = _containerReturning((_) async => _json('{"LISTINGS":true}'));
    addTearDown(c.dispose);

    await c.read(tenantFeaturesProvider.future);
    expect(c.read(mobileFinanceEnabledProvider), isFalse);
  });

  test('reads false while the flags are still loading', () {
    final c = _containerReturning((_) async => _json('{"MOBILE_FINANCE":true}'));
    addTearDown(c.dispose);

    // Nothing awaited: the first synchronous read must not show a screen that
    // a moment later gets snatched away.
    expect(c.read(mobileFinanceEnabledProvider), isFalse);
  });
}

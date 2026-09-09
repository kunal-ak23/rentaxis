import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/more_screen.dart';
import 'package:manager/screens/profile_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// App Store Review Guideline 5.1.1(v) and the in-app legal links: the
/// Manager app must offer account deletion on the profile screen and the
/// Privacy Policy / Terms of Use on the More menu, for every role.

/// Answers every request with 404 so the profile's GET /auth/me prefetch takes
/// its handled failure path instead of reaching the network.
class _NotFoundApi implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => ResponseBody.fromString(
    '{}',
    404,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );
}

ApiClient _fakeApiClient() {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = _NotFoundApi();
  return client;
}

class _StubAuthNotifier extends AuthNotifier {
  _StubAuthNotifier() : super(AuthService(Dio()));

  void signInAs(String role) {
    state = AuthState(
      isAuthenticated: true,
      isLoading: false,
      userId: 'u1',
      email: 'admin@example.com',
      name: 'Alia',
      role: role,
      tenantId: 't1',
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

Future<_StubAuthNotifier> _pump(WidgetTester tester, Widget screen) async {
  // Both screens are lazily built ListViews; a tall surface keeps the rows at
  // the bottom in existence for `find`.
  await tester.binding.setSurfaceSize(const Size(800, 2600));
  addTearDown(() => tester.binding.setSurfaceSize(null));

  final auth = _StubAuthNotifier();
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authProvider.overrideWith((ref) => auth),
        apiClientProvider.overrideWithValue(_fakeApiClient()),
      ],
      child: MaterialApp(theme: AppTheme.lightTheme, home: screen),
    ),
  );
  await tester.pumpAndSettle();
  auth.signInAs('TENANT_ADMIN');
  await tester.pumpAndSettle();
  return auth;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(_stubSecureStorage);
  tearDown(() => TenantContext.currentTenantId = null);

  testWidgets('profile offers the legal pages and account deletion', (
    tester,
  ) async {
    await _pump(tester, const ProfileScreen());

    expect(find.text('Privacy Policy'), findsOneWidget);
    expect(find.text('Terms of Use'), findsOneWidget);
    expect(find.text('Account & data deletion'), findsOneWidget);
    expect(find.text('Delete account'), findsOneWidget);
    expect(find.text('SIGN OUT'), findsOneWidget);
  });

  testWidgets('profile deletion asks before doing anything', (tester) async {
    await _pump(tester, const ProfileScreen());

    await tester.tap(find.text('Delete account'));
    await tester.pumpAndSettle();

    expect(find.textContaining('cannot be undone'), findsOneWidget);
    expect(find.text('Cancel'), findsOneWidget);
  });

  testWidgets('More menu links the privacy policy and terms', (tester) async {
    await _pump(tester, const MoreScreen());

    expect(find.text('Privacy Policy'), findsOneWidget);
    expect(find.text('Terms of Use'), findsOneWidget);
  });
}

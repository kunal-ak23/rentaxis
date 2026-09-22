import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:manager/router.dart';
import 'package:manager/screens/shell_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Minimal AuthService so AuthNotifier restores a signed-in TENANT_ADMIN
/// session without a network or a real keychain.
class _FakeAuthService implements AuthService {
  _FakeAuthService(this.role);
  final String role;

  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'u1',
    'email': 'admin@example.com',
    'name': 'Test Admin',
    'role': role,
  };

  @override
  Future<List<dynamic>> getTenants() async => [
    {'id': 't1', 'name': 'Demo Tenant', 'slug': 'demo'},
  ];

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName}');
}

/// Answers every request instantly with an empty JSON object. Screens then
/// render their own error/empty state rather than hanging on a 15s Dio
/// timeout, which would leave a pending timer at teardown.
class _OfflineAdapter implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async => ResponseBody.fromString(
    '{}',
    200,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, (call) async {
          if (call.method == 'read') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            return {'userId': 'u1', 'tenantId': 't1'}[args['key']];
          }
          if (call.method == 'readAll') return <String, String>{};
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, null);
  });

  group('manager finance gating', () {
    test('an admin still has no finance tab while the flag is off', () {
      expect(managerFinanceEnabled('TENANT_ADMIN', false), isFalse);
      expect(managerFinanceEnabled('PROPERTY_MANAGER', false), isFalse);
    });

    test('with the flag on, the existing role rule decides the destination', () {
      expect(managerFinanceEnabled('TENANT_ADMIN', true), isTrue);
      expect(managerFinanceRouteForRole('TENANT_ADMIN', true), '/finance');
      expect(managerFinanceRouteForRole('PROPERTY_MANAGER', true), '/payments');
    });

    test('with the flag off there is no finance destination at all', () {
      expect(managerFinanceRouteForRole('TENANT_ADMIN', false), isNull);
      expect(managerFinanceRouteForRole('PROPERTY_MANAGER', false), isNull);
    });

    test('a gated route is one the shell reports no index for', () {
      expect(managerShellIndexForLocation('/finance', financeEnabled: false),
          isNull);
      expect(managerShellIndexForLocation('/payments', financeEnabled: false),
          isNull);
      expect(managerShellIndexForLocation('/properties', financeEnabled: false),
          1);
    });

    test('every gated deep link is recognised as a finance route', () {
      for (final location in const [
        '/finance',
        '/finance-reports',
        '/payments',
        '/leases',
        '/leases/abc',
        '/leases/abc/settlement',
        '/leases/abc/penalties',
        '/scan',
        '/bank-accounts',
        '/portfolio-pnl',
        '/settings/mappings',
      ]) {
        expect(isManagerFinanceLocation(location), isTrue,
            reason: '$location must be gated');
      }
    });

    test('the surfaces whose endpoints still exist are NOT gated', () {
      // Pre-flight 6.2: /queue, /vendors and /settings/rent are backed by
      // endpoints accounting v2 kept, so the flag must not hide them.
      for (final location in const [
        '/',
        '/queue',
        '/vendors',
        '/vendors/abc',
        '/settings',
        '/settings/rent',
        '/properties',
        '/tickets',
      ]) {
        expect(isManagerFinanceLocation(location), isFalse,
            reason: '$location must stay reachable');
      }
    });
  });

  group('manager finance routes', () {
    /// Opens [startLocation] through the REAL [routerProvider] and reports
    /// where the router settled. The Dio stack answers every screen instantly
    /// (no network, so no 15s timeout timers), and the splash screen is never
    /// mounted because the location is set before the Router attaches.
    Future<String> locationAfterOpening(
      WidgetTester tester,
      String startLocation, {
      required bool financeEnabled,
    }) async {
      final client = ApiClient(baseUrl: 'https://api.example/api');
      client.dio.httpClientAdapter = _OfflineAdapter();
      final container = ProviderContainer(
        overrides: [
          apiClientProvider.overrideWithValue(client),
          authProvider.overrideWith(
            (ref) => AuthNotifier(_FakeAuthService('TENANT_ADMIN')),
          ),
          mobileFinanceEnabledProvider.overrideWithValue(financeEnabled),
          appGateProvider(
            AppId.manager,
          ).overrideWith((ref) async => AppGateDecision.ok),
        ],
      );
      addTearDown(container.dispose);
      // Let AuthNotifier._init settle so the redirect sees a signed-in user.
      await container.read(authProvider.notifier).stream.firstWhere(
        (s) => !s.isLoading,
      );
      final router = container.read(routerProvider);
      addTearDown(router.dispose);
      router.go(startLocation);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: MaterialApp.router(
            theme: AppTheme.lightTheme,
            routerConfig: router,
          ),
        ),
      );
      // The redirect runs while the first route is parsed, so the location is
      // final after one frame; the second drains the providers each screen
      // kicks off on build.
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      final settled = router.routerDelegate.currentConfiguration.uri.toString();
      // Leave the screen before teardown: the cheque-scan flow animates
      // forever, and a live ticker at teardown fails the test binding.
      router.go('/');
      await tester.pumpAndSettle();
      return settled;
    }

    for (final gated in const [
      '/finance',
      '/payments',
      '/leases',
      '/scan',
      '/bank-accounts',
      '/portfolio-pnl',
      '/finance-reports',
      '/settings/mappings',
    ]) {
      testWidgets('$gated redirects to Today while MOBILE_FINANCE is off', (
        tester,
      ) async {
        expect(
          await locationAfterOpening(tester, gated, financeEnabled: false),
          '/',
        );
      });

      testWidgets('$gated opens normally when MOBILE_FINANCE is on', (
        tester,
      ) async {
        expect(
          await locationAfterOpening(tester, gated, financeEnabled: true),
          gated,
        );
      });
    }

    testWidgets('an ungated route is unaffected by the flag being off', (
      tester,
    ) async {
      expect(
        await locationAfterOpening(tester, '/queue', financeEnabled: false),
        '/queue',
      );
    });
  });

  group('manager shell navigation', () {
    Future<void> pumpShell(WidgetTester tester,
        {required bool financeEnabled}) async {
      await tester.binding.setSurfaceSize(const Size(600, 900));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final client = ApiClient(baseUrl: 'https://api.example/api');
      client.dio.httpClientAdapter = _OfflineAdapter();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            apiClientProvider.overrideWithValue(client),
            authProvider.overrideWith(
              (ref) => AuthNotifier(_FakeAuthService('TENANT_ADMIN')),
            ),
            mobileFinanceEnabledProvider.overrideWithValue(financeEnabled),
          ],
          child: MaterialApp.router(
            theme: AppTheme.lightTheme,
            routerConfig: GoRouter(
              initialLocation: '/',
              routes: [
                ShellRoute(
                  builder: (context, state, child) => ShellScreen(child: child),
                  routes: [
                    GoRoute(
                      path: '/',
                      builder: (context, state) =>
                          const SizedBox(key: Key('home')),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('the Finance nav item is gone while the flag is off',
        (tester) async {
      await pumpShell(tester, financeEnabled: false);

      expect(find.text('Today'), findsOneWidget);
      expect(find.text('Portfolio'), findsOneWidget);
      expect(find.text('Queue'), findsOneWidget);
      expect(find.text('Finance'), findsNothing);
    });

    testWidgets('the Finance nav item returns when the flag is on',
        (tester) async {
      await pumpShell(tester, financeEnabled: true);

      expect(find.text('Finance'), findsOneWidget);
      expect(find.text('Queue'), findsOneWidget);
    });
  });
}

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/router.dart';
import 'package:renter/screens/shell_screen.dart';

/// Minimal AuthService so AuthNotifier restores a signed-in renter session
/// without a network or a real keychain.
class _FakeAuthService implements AuthService {
  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'u1',
    'email': 'resident@example.com',
    'name': 'Test Resident',
    'role': 'RENTER',
  };

  @override
  Future<List<dynamic>> getTenants() async => [
    {'id': 't1', 'name': 'Demo Tenant', 'slug': 'demo'},
  ];

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('${invocation.memberName}');
}

/// Answers every request instantly with an empty JSON object, so screens render
/// their own empty/error state rather than hanging on a 15s Dio timeout — which
/// would leave a pending timer at teardown.
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

  group('renter finance gating', () {
    test('the wallet tab is present only while the flag is on', () {
      expect(renterShellRoutes(true), [
        '/',
        '/browse',
        '/payments',
        '/services',
      ]);
      expect(renterShellRoutes(false), ['/', '/browse', '/services']);
    });

    test('services keeps its index whichever way the flag sits', () {
      expect(renterShellIndexForLocation('/services', financeEnabled: true), 3);
      expect(
        renterShellIndexForLocation('/services', financeEnabled: false),
        2,
      );
    });

    test('a gated route reports no index rather than a wrong one', () {
      expect(
        renterShellIndexForLocation('/payments', financeEnabled: false),
        isNull,
      );
      expect(
        renterShellIndexForLocation('/penalties', financeEnabled: false),
        isNull,
      );
      expect(
        renterShellIndexForLocation('/payments', financeEnabled: true),
        2,
      );
    });

    test('only the broken payments surface is gated', () {
      // Pre-flight 6.1: the renter's /payments screen calls
      // /v1/payments/lease/{id}, which accounting v2 removed. /penalties is
      // served by PenaltyAssessmentController and stays reachable (P5-R8), as
      // do gate passes, facilities and the rest of the Services hub.
      expect(isRenterFinanceLocation('/payments'), isTrue);
      for (final open in const [
        '/',
        '/browse',
        '/services',
        '/penalties',
        '/gatepass',
        '/tickets',
        '/facilities',
        '/meetings',
        '/wishlist',
        '/profile',
      ]) {
        expect(
          isRenterFinanceLocation(open),
          isFalse,
          reason: '$open must stay reachable',
        );
      }
    });
  });

  group('renter finance routes', () {
    /// Opens [startLocation] through the REAL [routerProvider] and reports
    /// where the router settled.
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
          authProvider.overrideWith((ref) => AuthNotifier(_FakeAuthService())),
          mobileFinanceEnabledProvider.overrideWithValue(financeEnabled),
          appGateProvider(
            AppId.renter,
          ).overrideWith((ref) async => AppGateDecision.ok),
        ],
      );
      addTearDown(container.dispose);
      await container.read(authProvider.notifier).stream.firstWhere(
        (s) => !s.isLoading,
      );
      final router = container.read(routerProvider);
      addTearDown(router.dispose);
      // Set the location BEFORE the Router attaches, so the video splash is
      // never mounted.
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
      router.go('/');
      await tester.pumpAndSettle();
      return settled;
    }

    testWidgets('a stale Wallet deep link lands Home while the flag is off', (
      tester,
    ) async {
      expect(
        await locationAfterOpening(tester, '/payments', financeEnabled: false),
        '/',
      );
    });

    testWidgets('the Wallet opens normally when the flag is on', (
      tester,
    ) async {
      expect(
        await locationAfterOpening(tester, '/payments', financeEnabled: true),
        '/payments',
      );
    });

    for (final open in const ['/penalties', '/services', '/gatepass']) {
      testWidgets('$open is unaffected by the flag being off', (tester) async {
        expect(
          await locationAfterOpening(tester, open, financeEnabled: false),
          open,
        );
      });
    }
  });

  group('renter shell navigation', () {
    Future<void> pumpShell(
      WidgetTester tester, {
      required bool financeEnabled,
    }) async {
      await tester.binding.setSurfaceSize(const Size(600, 900));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      final client = ApiClient(baseUrl: 'https://api.example/api');
      client.dio.httpClientAdapter = _OfflineAdapter();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            apiClientProvider.overrideWithValue(client),
            authProvider.overrideWith(
              (ref) => AuthNotifier(_FakeAuthService()),
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

    testWidgets('the Wallet tab is gone while the flag is off', (tester) async {
      await pumpShell(tester, financeEnabled: false);

      expect(find.text('Home'), findsOneWidget);
      expect(find.text('Explore'), findsOneWidget);
      expect(find.text('Services'), findsOneWidget);
      // Gate passes are not finance, so the raised action stays.
      expect(find.text('Pass'), findsOneWidget);
      expect(find.text('Wallet'), findsNothing);
    });

    testWidgets('the Wallet tab returns when the flag is on', (tester) async {
      await pumpShell(tester, financeEnabled: true);

      expect(find.text('Wallet'), findsOneWidget);
      expect(find.text('Services'), findsOneWidget);
      expect(find.text('Pass'), findsOneWidget);
    });
  });
}

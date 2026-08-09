import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/more_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Pins the More-menu role gating to the backend's class-level guards:
/// StaffController, VendorController and the finance controllers
/// (AccountController, BankAccountController, FinancialTransactionController)
/// are all `@PreAuthorize(hasAnyRole('SUPER_ADMIN','TENANT_ADMIN'))`, so a
/// PROPERTY_MANAGER must not be offered menu rows that can only 403 —
/// mirroring the web sidebar's canAccessFinance gating (rbac.ts).
class _FakeAuthService implements AuthService {
  _FakeAuthService(this.role);
  final String role;

  @override
  Future<Map<String, dynamic>> getProfile() async => {
    'id': 'u1',
    'email': 'user@example.com',
    'name': 'Test User',
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

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // AuthNotifier's _init reads the persisted session from
  // flutter_secure_storage; stub the platform channel so it finds a userId
  // and proceeds to the (fake) profile fetch instead of crashing the zone.
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

  Future<void> pumpMore(WidgetTester tester, String role) async {
    // Tall surface so the non-lazy assertions below see every ListView child.
    await tester.binding.setSurfaceSize(const Size(500, 3000));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authProvider.overrideWith(
            (ref) => AuthNotifier(_FakeAuthService(role)),
          ),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const MoreScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('PROPERTY_MANAGER sees no staff or finance entries', (
    tester,
  ) async {
    await pumpMore(tester, 'PROPERTY_MANAGER');

    // Positive controls prove the whole list rendered.
    expect(find.text('Renters'), findsOneWidget);
    expect(find.text('Tickets'), findsOneWidget);
    expect(find.text('Profile'), findsOneWidget);

    // Admin-only rows are hidden, not dead-ended into a 403.
    expect(find.text('Staff'), findsNothing);
    expect(find.text('Vendors'), findsNothing);
    expect(find.text('Accounts & Transactions'), findsNothing);
    expect(find.text('Bank Accounts'), findsNothing);
    expect(find.text('Reports'), findsNothing);
    expect(find.text('FINANCE'), findsNothing);
  });

  testWidgets('TENANT_ADMIN keeps the staff and finance entries', (
    tester,
  ) async {
    await pumpMore(tester, 'TENANT_ADMIN');

    expect(find.text('Staff'), findsOneWidget);
    expect(find.text('Vendors'), findsOneWidget);
    expect(find.text('Accounts & Transactions'), findsOneWidget);
    expect(find.text('Bank Accounts'), findsOneWidget);
    expect(find.text('Reports'), findsOneWidget);
  });
}

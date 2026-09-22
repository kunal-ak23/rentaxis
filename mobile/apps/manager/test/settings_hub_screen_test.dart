import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/settings_hub_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// GET /v1/rent-settings/{id} and /v1/gateway-config are
/// SUPER_ADMIN/TENANT_ADMIN only, so the hub must not offer those tiles to a
/// PROPERTY_MANAGER (they would be a guaranteed 403).
class _StubAuthNotifier extends AuthNotifier {
  _StubAuthNotifier() : super(AuthService(Dio()));

  void setRole(String? role) {
    state = AuthState(isAuthenticated: true, isLoading: false, role: role);
  }
}

void main() {
  Future<_StubAuthNotifier> pumpScreen(
    WidgetTester tester, {
    required String role,
    bool financeEnabled = true,
  }) async {
    final auth = _StubAuthNotifier();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          authProvider.overrideWith((ref) => auth),
          // Role gating is what these cases pin, so MOBILE_FINANCE is held ON
          // except where a case says otherwise.
          mobileFinanceEnabledProvider.overrideWithValue(financeEnabled),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const SettingsHubScreen(),
        ),
      ),
    );
    // Let AuthNotifier's async _init settle before stubbing the role, so the
    // stubbed state is the last write.
    await tester.pumpAndSettle();
    auth.setRole(role);
    await tester.pumpAndSettle();
    return auth;
  }

  testWidgets('PROPERTY_MANAGER does not see the admin-only tiles', (
    tester,
  ) async {
    await pumpScreen(tester, role: 'PROPERTY_MANAGER');

    expect(find.text('Rent Collection Settings'), findsNothing);
    expect(find.text('Payment Gateway'), findsNothing);
    expect(find.text('Account Mappings'), findsNothing);
  });

  testWidgets('TENANT_ADMIN sees all settings tiles', (tester) async {
    await pumpScreen(tester, role: 'TENANT_ADMIN');

    expect(find.text('Rent Collection Settings'), findsOneWidget);
    expect(find.text('Payment Gateway'), findsOneWidget);
    expect(find.text('Account Mappings'), findsOneWidget);
  });

  testWidgets('MOBILE_FINANCE off hides Account Mappings from an admin too', (
    tester,
  ) async {
    // /v1/finance/account-mappings was removed by accounting v2. The router
    // redirects /settings/mappings, but go_router applies redirect to push as
    // well — so a visible tile would PUSH the dashboard on top of Settings.
    // P5-R8: hidden AND redirected.
    await pumpScreen(tester, role: 'TENANT_ADMIN', financeEnabled: false);

    expect(find.text('Account Mappings'), findsNothing);
    // The two settings backed by endpoints v2 kept are untouched.
    expect(find.text('Rent Collection Settings'), findsOneWidget);
    expect(find.text('Payment Gateway'), findsOneWidget);
  });
}

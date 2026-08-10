import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/gateway_config_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_settings_api.dart';

/// Pins the gateway screen to the real GET /v1/gateway-config contract:
/// TenantGatewayConfigDTO serializes isActive, gatewayCode and apiKeyMasked
/// (never 'active'/'status'/'providerType'/'keyId').
void main() {
  final config = <String, dynamic>{
    'id': 'cfg-1',
    'gatewayId': 'gw-1',
    'gatewayCode': 'RAZORPAY',
    'gatewayName': 'Razorpay',
    'apiKey': null,
    'apiSecret': null,
    'webhookSecret': null,
    'apiKeyMasked': 'rzp_test****',
    'hasWebhookSecret': true,
    'isActive': true,
    'isTestMode': true,
  };

  Future<void> pumpScreen(WidgetTester tester, FakeSettingsApi api) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(fakeSettingsApiClient(api)),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const GatewayConfigScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('renders status, provider and masked key from the real DTO keys', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      FakeSettingsApi(
        gatewayConfig: config,
        gateways: [
          {'id': 'gw-2', 'code': 'TELR', 'name': 'Telr'},
        ],
      ),
    );

    // isActive: true → the green Active badge, not the pre-fix permanent
    // 'Inactive' (the screen used to read config['active']/config['status']).
    expect(find.text('ACTIVE'), findsOneWidget);
    expect(find.text('INACTIVE'), findsNothing);
    // Provider row reads gatewayCode (pre-fix it read providerType → '-').
    expect(find.text('RAZORPAY'), findsOneWidget);
    // Key row shows the server-masked key as-is (pre-fix it read keyId and
    // never rendered).
    expect(find.text('rzp_test****'), findsOneWidget);
    expect(find.text('Razorpay'), findsOneWidget);
    expect(find.text('Telr'), findsOneWidget);
  });

  testWidgets('an inactive config shows the Inactive badge', (tester) async {
    await pumpScreen(
      tester,
      FakeSettingsApi(gatewayConfig: {...config, 'isActive': false}),
    );

    expect(find.text('INACTIVE'), findsOneWidget);
    expect(find.text('ACTIVE'), findsNothing);
  });

  testWidgets('a 204 (no config) shows the empty state', (tester) async {
    await pumpScreen(tester, FakeSettingsApi());

    // EmptyState renders its title uppercased.
    expect(find.text('NO PAYMENT GATEWAY CONFIGURED'), findsOneWidget);
  });

  testWidgets('a 403 shows the permission message without a Retry', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      FakeSettingsApi(
        statusOverrides: {
          '/v1/gateway-config/gateways': 403,
          '/v1/gateway-config': 403,
        },
      ),
    );

    expect(
      find.text('You do not have permission to view gateway settings'),
      findsOneWidget,
    );
    expect(find.text('Retry'), findsNothing);
  });
}

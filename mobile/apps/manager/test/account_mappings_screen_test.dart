import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/account_mappings_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_settings_api.dart';

/// Pins the account-mappings screen to the real
/// GET /v1/finance/account-mappings contract: AccountMappingDTO serializes
/// debitAccountCode/debitAccountName and creditAccountCode/creditAccountName
/// (never a plain 'accountName'/'accountCode'), so each mapping must render
/// its debit and credit accounts as two labelled lines.
void main() {
  final mapping = <String, dynamic>{
    'id': 'map-1',
    'transactionNature': 'RENT_PAYMENT_CLEARED',
    'debitAccountId': 'acc-1',
    'debitAccountCode': '1010',
    'debitAccountName': 'Bank Current Account',
    'creditAccountId': 'acc-2',
    'creditAccountCode': '4010',
    'creditAccountName': 'Rental Income',
  };

  Future<void> pumpScreen(WidgetTester tester, FakeSettingsApi api) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(fakeSettingsApiClient(api)),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const AccountMappingsScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('renders debit and credit lines from the real DTO keys', (
    tester,
  ) async {
    await pumpScreen(tester, FakeSettingsApi(accountMappings: [mapping]));

    // Grouped card header uses transactionNature.
    expect(find.text('Rent Payment Cleared'), findsOneWidget);

    // Expand the nature's ExpansionTile.
    await tester.tap(find.text('Rent Payment Cleared'));
    await tester.pumpAndSettle();

    // Debit line: debitAccountName + debitAccountCode (pre-fix the screen
    // read mapping['accountName']/['accountCode'] and rendered '-').
    expect(find.text('Bank Current Account'), findsOneWidget);
    expect(find.text('Code: 1010'), findsOneWidget);
    expect(find.text('Debit'), findsOneWidget);

    // Credit line: creditAccountName + creditAccountCode.
    expect(find.text('Rental Income'), findsOneWidget);
    expect(find.text('Code: 4010'), findsOneWidget);
    expect(find.text('Credit'), findsOneWidget);

    // The pre-fix placeholder title must be gone.
    expect(find.text('-'), findsNothing);
  });

  testWidgets('no mappings shows the empty state', (tester) async {
    await pumpScreen(tester, FakeSettingsApi());

    // EmptyState renders its title upper-cased.
    expect(find.text('NO ACCOUNT MAPPINGS CONFIGURED'), findsOneWidget);
  });
}

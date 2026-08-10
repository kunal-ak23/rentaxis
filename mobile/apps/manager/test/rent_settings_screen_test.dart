import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/rent_settings_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_settings_api.dart';

/// Pins the rent-settings screen to the real GET /v1/rent-settings/{id}
/// contract: RentCollectionSettingsDTO serializes dueDayOfMonth,
/// gracePeriodDays, penaltyType, penaltyAmount and onlinePaymentEnabled
/// (never 'penaltyRate'/'autoApplyPenalty'/'reminderDaysBefore'), and
/// PenaltyType is NONE | FIXED_PER_DAY | PERCENTAGE.
void main() {
  final properties = [
    {'id': 'prop-1', 'name': 'Marina Heights'},
  ];

  final settings = <String, dynamic>{
    'id': 'rs-1',
    'propertyId': 'prop-1',
    'dueDayOfMonth': 5,
    'gracePeriodDays': 7,
    'penaltyType': 'PERCENTAGE',
    'penaltyAmount': 0.5,
    'onlinePaymentEnabled': true,
  };

  Future<void> pumpScreen(
    WidgetTester tester,
    FakeSettingsApi api, {
    Locale locale = const Locale('en'),
  }) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          apiClientProvider.overrideWithValue(fakeSettingsApiClient(api)),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          locale: locale,
          supportedLocales: const [Locale('en'), Locale('ar')],
          localizationsDelegates: const [
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          home: const RentSettingsScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> selectProperty(WidgetTester tester, String name) async {
    await tester.tap(find.byType(DropdownButtonFormField<String>));
    await tester.pumpAndSettle();
    await tester.tap(find.text(name).last);
    await tester.pumpAndSettle();
  }

  testWidgets('renders the real RentCollectionSettingsDTO fields', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      FakeSettingsApi(
        propertyRows: properties,
        rentSettingsByPropertyId: {'prop-1': settings},
      ),
    );
    await selectProperty(tester, 'Marina Heights');

    expect(find.text('Day 5'), findsOneWidget); // dueDayOfMonth
    expect(find.text('7 days'), findsOneWidget); // gracePeriodDays
    expect(find.text('PERCENTAGE'), findsOneWidget); // penaltyType
    expect(find.text('0.5%/day'), findsOneWidget); // penaltyAmount
    expect(find.text('Yes'), findsOneWidget); // onlinePaymentEnabled
    // The pre-fix screen read settings['penaltyRate'] and rendered '-%'.
    expect(find.text('-%'), findsNothing);
  });

  testWidgets('NONE penalty type hides the amount row', (tester) async {
    await pumpScreen(
      tester,
      FakeSettingsApi(
        propertyRows: properties,
        rentSettingsByPropertyId: {
          'prop-1': {
            ...settings,
            'penaltyType': 'NONE',
            'penaltyAmount': null,
          },
        },
      ),
    );
    await selectProperty(tester, 'Marina Heights');

    expect(find.text('NONE'), findsOneWidget);
    expect(find.text('Late Payment Penalty'), findsNothing);
  });

  testWidgets('Arabic maps the backend PERCENTAGE enum value', (tester) async {
    await pumpScreen(
      tester,
      FakeSettingsApi(
        propertyRows: properties,
        rentSettingsByPropertyId: {'prop-1': settings},
      ),
      locale: const Locale('ar'),
    );
    await selectProperty(tester, 'Marina Heights');

    // Pre-fix the Arabic map keyed 'PERCENTAGE_OF_RENT', so Arabic users saw
    // the raw English 'PERCENTAGE'.
    expect(find.text('نسبة من الإيجار'), findsOneWidget);
    expect(find.text('PERCENTAGE'), findsNothing);
  });

  testWidgets('a 403 shows the permission message without a Retry', (
    tester,
  ) async {
    await pumpScreen(
      tester,
      FakeSettingsApi(
        propertyRows: properties,
        statusOverrides: {'/v1/rent-settings/prop-1': 403},
      ),
    );
    await selectProperty(tester, 'Marina Heights');

    expect(
      find.text('You do not have permission to view rent collection settings'),
      findsOneWidget,
    );
    expect(find.text('Retry'), findsNothing);
  });
}

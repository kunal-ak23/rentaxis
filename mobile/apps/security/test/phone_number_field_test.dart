import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/auth/phone_country.dart';
import 'package:security/auth/phone_number_field.dart';

/// Pumps the field at a realistic phone width in both chrome variants and both
/// locales, opening the country menu each time. Any RenderFlex overflow is a
/// failure: the menu inherits the closed button's width unless told otherwise,
/// which is exactly the regression this guards.
void main() {
  Future<void> pumpField(
    WidgetTester tester, {
    required bool dark,
    required bool ar,
    double width = 360,
  }) async {
    final controller = TextEditingController();
    addTearDown(controller.dispose);
    var country = PhoneCountry.uae;

    await tester.pumpWidget(
      MaterialApp(
        theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
        locale: Locale(ar ? 'ar' : 'en'),
        supportedLocales: const [Locale('en'), Locale('ar')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: width,
              child: StatefulBuilder(
                builder: (context, setState) => PhoneNumberField(
                  controller: controller,
                  country: country,
                  onCountryChanged: (c) => setState(() => country = c),
                  ar: ar,
                  dark: dark,
                  label: 'Phone number',
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  for (final dark in [false, true]) {
    for (final ar in [false, true]) {
      final variant = '${dark ? 'dark' : 'light'}/${ar ? 'ar' : 'en'}';

      testWidgets('$variant renders the closed field without overflow', (
        tester,
      ) async {
        await pumpField(tester, dark: dark, ar: ar);
        expect(tester.takeException(), isNull);
        expect(find.text('+971'), findsOneWidget);
      });

      testWidgets('$variant opens the country menu without overflow', (
        tester,
      ) async {
        await pumpField(tester, dark: dark, ar: ar);
        await tester.tap(find.byKey(const Key('phoneCountrySelector')));
        await tester.pumpAndSettle();
        // Both countries offered, and no RenderFlex complained on the way.
        expect(find.text('+91'), findsWidgets);
        expect(tester.takeException(), isNull);
      });
    }
  }

  testWidgets('menu fits on a narrow phone', (tester) async {
    // 320dp is the narrowest mainstream width; the field also sits inside 32dp
    // of page padding on the login screen, so the real box is narrower still.
    await pumpField(tester, dark: true, ar: false, width: 240);
    await tester.tap(find.byKey(const Key('phoneCountrySelector')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('picking India rebuilds the field with the new code', (
    tester,
  ) async {
    await pumpField(tester, dark: false, ar: false);
    await tester.tap(find.byKey(const Key('phoneCountrySelector')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('India').last);
    await tester.pumpAndSettle();
    expect(find.text('+91'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

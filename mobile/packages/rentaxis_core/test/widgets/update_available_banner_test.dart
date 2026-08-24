import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  Widget host(Widget child) => MaterialApp(home: Scaffold(body: child));

  testWidgets('tapping Update opens the store url and dismisses',
      (tester) async {
    String? opened;
    var dismissed = 0;

    await tester.pumpWidget(host(
      UpdateAvailableBanner(
        storeUrl: 'https://store/app',
        launcher: (url) async => opened = url,
        onDismiss: () => dismissed++,
      ),
    ));

    await tester.tap(find.text('Update'));
    await tester.pump();

    expect(opened, 'https://store/app');
    expect(dismissed, 1);
  });

  testWidgets('the close button dismisses without opening anything',
      (tester) async {
    String? opened;
    var dismissed = 0;

    await tester.pumpWidget(host(
      UpdateAvailableBanner(
        storeUrl: 'https://store/app',
        launcher: (url) async => opened = url,
        onDismiss: () => dismissed++,
      ),
    ));

    await tester.tap(find.byIcon(Icons.close_rounded));
    await tester.pump();

    expect(dismissed, 1);
    expect(opened, isNull);
  });

  testWidgets('with an empty store url there is no Update action',
      (tester) async {
    await tester.pumpWidget(host(
      UpdateAvailableBanner(
        storeUrl: '',
        onDismiss: () {},
      ),
    ));

    expect(find.text('Update'), findsNothing);
    // But it is still dismissible.
    expect(find.byIcon(Icons.close_rounded), findsOneWidget);
  });
}

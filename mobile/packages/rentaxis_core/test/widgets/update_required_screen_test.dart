import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  testWidgets('renders the blocking headline and body', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(home: UpdateRequiredScreen()),
    );

    expect(find.text('Update required'), findsOneWidget);
    expect(
      find.textContaining('no longer supported'),
      findsOneWidget,
    );
  });

  testWidgets('cannot be dismissed with back (PopScope canPop is false)',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(home: UpdateRequiredScreen()),
    );

    final popScope = tester.widget<PopScope>(find.byType(PopScope));
    expect(popScope.canPop, isFalse);
  });

  testWidgets('with a store url, the update button opens it', (tester) async {
    String? opened;
    await tester.pumpWidget(
      MaterialApp(
        home: UpdateRequiredScreen(
          storeUrl: 'https://store/app',
          launcher: (url) async => opened = url,
        ),
      ),
    );

    final button = find.text('Update now');
    expect(button, findsOneWidget);

    await tester.tap(button);
    await tester.pump();

    expect(opened, 'https://store/app');
  });

  testWidgets('with an empty store url, no update button is shown',
      (tester) async {
    await tester.pumpWidget(
      const MaterialApp(home: UpdateRequiredScreen(storeUrl: '')),
    );

    expect(find.text('Update now'), findsNothing);
    // The block itself still stands.
    expect(find.text('Update required'), findsOneWidget);
  });
}

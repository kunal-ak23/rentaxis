import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  testWidgets('unstyled text on the gold card stays ink in dark mode', (
    tester,
  ) async {
    // Regression: the gold doesn't change in dark mode, but text with no
    // style of its own took the dark theme's light colour — white on gold.
    await tester.pumpWidget(
      MaterialApp(
        theme: MiftahTheme.dark,
        home: const Scaffold(body: MiftahGoldCard(child: Text('A-301'))),
      ),
    );

    final style = DefaultTextStyle.of(tester.element(find.text('A-301'))).style;
    expect(style.color, MiftahColors.textPrimary);
  });
}

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The raised centre action is the bar's anchor: the gold circle has to sit on
/// the bar's vertical midline whether the shell shows four tabs or — once a
/// per-tenant flag hides one — three. With equal-flex slots that only holds
/// when the slot count is odd, which is what these cases pin.
void main() {
  const barKey = Key('bar');

  Future<Rect> pumpBar(
    WidgetTester tester, {
    required int itemCount,
    required bool withCentre,
  }) async {
    await tester.binding.setSurfaceSize(const Size(400, 800));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          bottomNavigationBar: MiftahNavBar(
            key: barKey,
            currentIndex: 0,
            onTap: (_) {},
            centreIcon: withCentre ? Icons.qr_code_scanner_rounded : null,
            centreLabel: withCentre ? 'Pass' : null,
            onCentreTap: withCentre ? () {} : null,
            items: [
              for (var i = 0; i < itemCount; i++)
                MiftahNavItem(icon: Icons.circle, label: 'Tab $i'),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return tester.getRect(find.byKey(barKey));
  }

  double centreX(WidgetTester tester) =>
      tester.getCenter(find.byIcon(Icons.qr_code_scanner_rounded)).dx;

  testWidgets('four items put the raised action on the midline', (
    tester,
  ) async {
    final bar = await pumpBar(tester, itemCount: 4, withCentre: true);

    expect(centreX(tester), closeTo(bar.center.dx, 0.5));
  });

  testWidgets('three items put the raised action on the same midline', (
    tester,
  ) async {
    // Regression: splitting at `items.length ~/ 2` left the circle at 5/8 of
    // the width — visibly off centre on the renter bar once Wallet is hidden.
    final bar = await pumpBar(tester, itemCount: 3, withCentre: true);

    expect(centreX(tester), closeTo(bar.center.dx, 0.5));
  });

  testWidgets('the three tabs stay in order, left of and right of the action', (
    tester,
  ) async {
    await pumpBar(tester, itemCount: 3, withCentre: true);

    final pass = centreX(tester);
    final tab0 = tester.getCenter(find.text('Tab 0')).dx;
    final tab1 = tester.getCenter(find.text('Tab 1')).dx;
    final tab2 = tester.getCenter(find.text('Tab 2')).dx;

    expect(tab0, lessThan(tab1));
    expect(tab1, lessThan(pass));
    expect(pass, lessThan(tab2));
  });

  testWidgets('a bar with no centre action spreads its three tabs evenly', (
    tester,
  ) async {
    final bar = await pumpBar(tester, itemCount: 3, withCentre: false);

    expect(find.byIcon(Icons.qr_code_scanner_rounded), findsNothing);
    // Middle tab on the midline, outer two symmetric about it.
    final tab0 = tester.getCenter(find.text('Tab 0')).dx;
    final tab1 = tester.getCenter(find.text('Tab 1')).dx;
    final tab2 = tester.getCenter(find.text('Tab 2')).dx;
    expect(tab1, closeTo(bar.center.dx, 0.5));
    expect(tab1 - tab0, closeTo(tab2 - tab1, 0.5));
  });
}

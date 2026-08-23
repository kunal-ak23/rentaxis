import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/coupon_sheet.dart';

import 'support/fake_promotion_service.dart';

void main() {
  testWidgets('shows the code, the business name and the terms',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: CouponSheet(
          ad: testAd(
            ctaType: 'COUPON',
            couponCode: 'MIFTAH25',
            couponTermsEn: 'Dine-in only, Fridays',
          ),
        ),
      ),
    ));

    expect(find.text('MIFTAH25'), findsOneWidget);
    expect(find.text('Spice Bazaar'), findsOneWidget);
    expect(find.text('Dine-in only, Fridays'), findsOneWidget);
  });

  testWidgets('copies the code to the clipboard and confirms', (tester) async {
    final copied = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied.add((call.arguments as Map)['text'] as String);
        }
        return null;
      },
    );
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));

    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: CouponSheet(
          ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'),
        ),
      ),
    ));

    await tester.tap(find.byKey(const Key('coupon-copy')));
    await tester.pump();

    expect(copied, ['MIFTAH25']);
    expect(find.byType(SnackBar), findsOneWidget);
  });

  testWidgets('omits the terms block when there are none', (tester) async {
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: CouponSheet(
          ad: testAd(ctaType: 'COUPON', couponCode: 'MIFTAH25'),
        ),
      ),
    ));

    expect(find.byKey(const Key('coupon-terms')), findsNothing);
  });

  testWidgets('renders nothing sensitive when the code is missing',
      (tester) async {
    // Defensive: the backend requires a code for COUPON ads, but a card
    // arriving without one must degrade rather than show an empty pill.
    await tester.pumpWidget(MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(body: CouponSheet(ad: testAd(ctaType: 'COUPON'))),
    ));

    expect(find.byKey(const Key('coupon-copy')), findsNothing);
  });
}

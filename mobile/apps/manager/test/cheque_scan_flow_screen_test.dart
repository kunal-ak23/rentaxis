import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:manager/screens/cheque_scan/cheque_scan_flow_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    GoogleFonts.config.allowRuntimeFetching = false;
  });

  testWidgets('scan route renders a safe gallery fallback without a camera', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: const ChequeScanFlowScreen(),
      ),
    );
    // Camera/plugin lifecycles are intentionally continuous and therefore do
    // not have a globally settled frame. The route contract is visible on its
    // first stable build, so use fixed pumps rather than pumpAndSettle.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text('Position the cheque'), findsOneWidget);
    expect(find.textContaining('Lay the cheque flat'), findsOneWidget);
    expect(find.byIcon(Icons.image_outlined), findsOneWidget);
    expect(find.byIcon(Icons.flash_off_outlined), findsOneWidget);
  });

  testWidgets('scan route renders its Arabic instructions right to left', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('ar'),
        supportedLocales: const [Locale('en'), Locale('ar')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        theme: AppTheme.lightTheme,
        home: const ChequeScanFlowScreen(),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text('وضع الشيك'), findsOneWidget);
    expect(
      find.text(
        'ضع الشيك على سطح داكن. تأكد من ظهور جميع الزوايا الأربع داخل الإطار.',
      ),
      findsOneWidget,
    );
    expect(
      Directionality.of(tester.element(find.text('وضع الشيك'))),
      TextDirection.rtl,
    );
  });
}

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  testWidgets('the dark splash asks for light system bar icons', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: MiftahTheme.light,
        home: VideoSplashScreen(onComplete: () {}),
      ),
    );

    final region = tester.widget<AnnotatedRegion<SystemUiOverlayStyle>>(
      find.byType(AnnotatedRegion<SystemUiOverlayStyle>).first,
    );
    expect(region.value.statusBarIconBrightness, Brightness.light);
    expect(region.value.systemNavigationBarColor, AppColors.navyDark);
    // Let the splash's entrance animations run out.
    await tester.pump(const Duration(seconds: 10));
  });
}

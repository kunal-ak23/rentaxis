import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  testWidgets('shows the app creative and completes after the animation', (
    tester,
  ) async {
    var completed = false;

    await tester.pumpWidget(
      MaterialApp(home: VideoSplashScreen(onComplete: () => completed = true)),
    );

    final background = tester.widget<Image>(find.byType(Image).first);
    expect(background.image, const AssetImage('assets/splash_background.png'));
    expect(background.fit, BoxFit.cover);
    expect(completed, isFalse);

    await tester.pump(const Duration(milliseconds: 2500));

    expect(completed, isTrue);
  });
}

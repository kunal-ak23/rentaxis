import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

void main() {
  // Building the theme loads its fonts, which needs the binding.
  TestWidgetsFlutterBinding.ensureInitialized();

  test('light content gets dark icons on both bars, and the reverse', () {
    final light = MiftahTheme.systemBars(
      Brightness.light,
      navigationBar: MiftahColors.surface,
    );
    expect(light.statusBarIconBrightness, Brightness.dark);
    expect(light.systemNavigationBarIconBrightness, Brightness.dark);
    expect(light.statusBarBrightness, Brightness.light);
    expect(light.statusBarColor, Colors.transparent);
    expect(light.systemNavigationBarColor, MiftahColors.surface);

    final dark = MiftahTheme.systemBars(
      Brightness.dark,
      navigationBar: MiftahColors.darkSurface,
    );
    expect(dark.statusBarIconBrightness, Brightness.light);
    expect(dark.systemNavigationBarIconBrightness, Brightness.light);
    expect(dark.statusBarBrightness, Brightness.dark);
  });

  test(
    "an AppBar on a light screen no longer paints the navigation bar black",
    () {
      final style = MiftahTheme.light.appBarTheme.systemOverlayStyle!;
      expect(style.systemNavigationBarColor, MiftahColors.surface);
      expect(style.statusBarIconBrightness, Brightness.dark);
      expect(
        MiftahTheme
            .dark
            .appBarTheme
            .systemOverlayStyle!
            .statusBarIconBrightness,
        Brightness.light,
      );
    },
  );
}

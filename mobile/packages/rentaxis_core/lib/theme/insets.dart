import 'package:flutter/widgets.dart';

/// Layout insets shared by the manager and renter apps.
///
/// Both app shells use `Scaffold(extendBody: true, bottomNavigationBar: ...)`,
/// so Flutter exposes the floating bottom nav's full height (bar + margins +
/// gesture area) as `MediaQuery.padding.bottom` inside the body. Scrollables
/// that set an explicit `padding:` discard that ambient inset — use these
/// helpers instead of hardcoded bottom values so content always clears the
/// nav on every device.
class AppInsets {
  AppInsets._();

  /// Bottom inset that clears the shell's floating bottom nav plus [spacing]
  /// of breathing room. Falls back to [spacing] outside the shell.
  static double bottomNav(BuildContext context, {double spacing = 16}) =>
      MediaQuery.paddingOf(context).bottom + spacing;

  /// Convenience scroll padding for list/scroll views inside the shell.
  static EdgeInsets scroll(
    BuildContext context, {
    double horizontal = 16,
    double top = 16,
    double spacing = 16,
  }) =>
      EdgeInsets.fromLTRB(
        horizontal,
        top,
        horizontal,
        bottomNav(context, spacing: spacing),
      );
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Pumps one screen with the network seam faked.
///
/// Deliberately does not boot the real [RenterApp]: the gate-pass screens render
/// provider state and post to a service, and reaching them through the real app
/// would mean walking a fake login on every test — and would drag in this app's
/// router, which rebuilds on `authProvider`, for no coverage of anything these
/// screens do.
///
/// [routes] supplies the real router the screen under test navigates with (the
/// list pushes /gatepass/:id, the create form pushes the detail), so navigation
/// is not stubbed out.
///
/// [theme] defaults to the legacy `AppTheme.lightTheme` the older screen tests
/// were written against; pass `MiftahTheme.light` to render exactly what the
/// app renders (its filled buttons carry an infinite minimum width, which the
/// legacy theme does not, so a layout that passes here can still overflow in
/// the app unless the test uses the app's theme).
///
/// [surfaceSize] grows the test window past the default 800x600. The create
/// form is longer than that, and its fields live in a `ListView` — which builds
/// lazily, so an off-screen field is not merely out of view, it does not exist
/// for `find` to reach. A taller surface tests the form as one page rather than
/// making every test drive a scroll it is not about.
Future<ProviderContainer> pumpScreen(
  WidgetTester tester, {
  required List<RouteBase> routes,
  required String initialLocation,
  List<Override> overrides = const [],
  Size? surfaceSize,
  ThemeData? theme,
}) async {
  if (surfaceSize != null) {
    await tester.binding.setSurfaceSize(surfaceSize);
    addTearDown(() => tester.binding.setSurfaceSize(null));
  }

  final container = ProviderContainer(overrides: overrides);
  addTearDown(container.dispose);

  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp.router(
        theme: theme ?? AppTheme.lightTheme,
        routerConfig: GoRouter(
          initialLocation: initialLocation,
          routes: routes,
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return container;
}

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/app.dart';
import 'package:renter/router.dart';

import 'support/fake_app_version_service.dart';

/// Boots the real [RenterApp] router with the version gate wired to a fake
/// service, and drives it through the splash so `onComplete` runs. Proves the
/// three outcomes at the app boundary, not just in the pure rule.
void main() {
  Future<ProviderContainer> boot(
    WidgetTester tester, {
    required int installedBuild,
    required FakeAppVersionService service,
  }) async {
    final container = ProviderContainer(overrides: [
      installedBuildProvider.overrideWith((ref) async => installedBuild),
      currentPlatformProvider.overrideWithValue(AppPlatform.android),
      appVersionServiceProvider.overrideWithValue(service),
    ]);
    addTearDown(container.dispose);

    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: container,
        child: const RenterApp(),
      ),
    );
    // Advances past the splash's 2.5s timer and lets the gate future resolve.
    await tester.pumpAndSettle();
    return container;
  }

  testWidgets(
      'build below the floor lands on the blocking screen and is pinned there',
      (tester) async {
    final container = await boot(
      tester,
      installedBuild: 1,
      service: FakeAppVersionService(
        info: const AppVersionInfo(
          minSupportedBuild: 2,
          latestBuild: 3,
          latestVersionName: '1.2.0',
          storeUrl: 'https://store/renter',
        ),
      ),
    );

    // Diverted to the hard block instead of the login screen.
    expect(find.byType(UpdateRequiredScreen), findsOneWidget);
    expect(find.text('Update required'), findsOneWidget);

    // The redirect pins: an explicit attempt to navigate away is bounced back.
    container.read(routerProvider).go('/login');
    await tester.pumpAndSettle();
    expect(find.byType(UpdateRequiredScreen), findsOneWidget);

    container.read(routerProvider).go('/');
    await tester.pumpAndSettle();
    expect(find.byType(UpdateRequiredScreen), findsOneWidget);
  });

  testWidgets('a null gate answer (error/timeout) proceeds normally',
      (tester) async {
    await boot(
      tester,
      installedBuild: 1,
      // null info == the real service's error/timeout answer.
      service: FakeAppVersionService(info: null),
    );

    expect(find.byType(UpdateRequiredScreen), findsNothing);
  });

  testWidgets('a supported-but-behind build is not blocked (app usable)',
      (tester) async {
    await boot(
      tester,
      installedBuild: 2,
      service: FakeAppVersionService(
        info: const AppVersionInfo(
          minSupportedBuild: 1,
          latestBuild: 5,
          latestVersionName: '2.0.0',
          storeUrl: 'https://store/renter',
        ),
      ),
    );

    // No hard block for the soft case — the app is reachable.
    expect(find.byType(UpdateRequiredScreen), findsNothing);
  });
}

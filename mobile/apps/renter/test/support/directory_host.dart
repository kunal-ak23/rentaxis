import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/router.dart' show directoryRoutes;
import 'package:renter/widgets/promo_actions.dart';
import 'package:url_launcher/url_launcher.dart';

import 'fake_promotion_service.dart';

/// Records what the screen asked the OS to open, instead of opening it.
class RecordingLauncher {
  final List<Uri> uris = [];
  final List<LaunchMode> modes = [];

  Future<bool> call(Uri uri, {LaunchMode mode = LaunchMode.platformDefault}) async {
    uris.add(uri);
    modes.add(mode);
    return true;
  }

  Future<bool> callString(
    String url, {
    LaunchMode mode = LaunchMode.platformDefault,
  }) async {
    uris.add(Uri.parse(url));
    modes.add(mode);
    return true;
  }
}

/// Text on the stub page the directory screens are pushed over, so a pop is
/// observable.
const homeStub = 'HOME-STUB';

/// Pumps the app's real directory routes over a stub home and pushes
/// [location], the way a home tile does — so the AppBar has a back button.
///
/// [ar] wires the Arabic locale exactly like `RenterApp`: the localizations
/// delegates are what flip `Directionality` to RTL.
Future<GoRouter> pumpDirectory(
  WidgetTester tester,
  FakePromotionService fake,
  String location, {
  bool ar = false,
  RecordingLauncher? launcher,
  List<RouteBase>? routes,
}) async {
  final router = GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(path: '/', builder: (_, _) => const Scaffold(body: Text(homeStub))),
      ...(routes ?? directoryRoutes),
    ],
  );
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        promotionServiceProvider.overrideWithValue(fake),
        if (launcher != null)
          promoActionsProvider.overrideWithValue(
            PromoActions(
              launcher: launcher.call,
              stringLauncher: launcher.callString,
            ),
          ),
      ],
      child: MaterialApp.router(
        theme: MiftahTheme.light,
        locale: ar ? const Locale('ar') : const Locale('en'),
        supportedLocales: const [Locale('en'), Locale('ar')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        routerConfig: router,
      ),
    ),
  );
  await tester.pumpAndSettle();
  router.push(location);
  await tester.pumpAndSettle();
  return router;
}

/// Drives the lifecycle the way the engine does, over `flutter/lifecycle`, so
/// `ServicesBinding` generates the intermediate states a real backgrounding
/// produces (and the analyzer is not tripped by a `@protected` call).
void sendLifecycle(WidgetTester tester, AppLifecycleState state) {
  tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    SystemChannels.lifecycle.name,
    const StringCodec().encodeMessage(state.toString()),
    (_) {},
  );
}

/// The back button sits at the leading edge: left in LTR, right in RTL.
bool backButtonOnTheRight(WidgetTester tester) {
  final rect = tester.getRect(find.byType(BackButton));
  final width = tester.view.physicalSize.width / tester.view.devicePixelRatio;
  return rect.center.dx > width / 2;
}

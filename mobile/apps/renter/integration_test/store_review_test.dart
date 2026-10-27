// Play Store review recordings for the renter app, one scenario per run.
//
// Driven by mobile/scripts/record-store-review.sh, which starts screenrecord on
// `[store-review] record-start` and stops it on `[store-review] record-stop`:
//
//   flutter drive --profile --driver=test_driver/integration_test.dart \
//     --target=integration_test/store_review_test.dart \
//     --dart-define-from-file=<private defines> --dart-define=SCENARIO=offers
//
// The scenario signs in through the login form like a person would; no account
// is changed beyond what it puts back (a cancelled booking, a cancelled pass,
// the wishlist toggled off again). A new ticket cannot be deleted.
//
// ignore_for_file: avoid_print
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:integration_test/integration_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/app.dart';
import 'package:renter/screens/shell_screen.dart';
import 'package:renter/widgets/renter_document_button.dart';

import 'store_review/reviewer.dart';
import 'store_review/scenarios.dart';

const scenario = String.fromEnvironment('SCENARIO');

const _accounts = <String, Account>{
  'AHMED': Account(
    'AHMED',
    String.fromEnvironment('AHMED_EMAIL'),
    String.fromEnvironment('AHMED_PASSWORD'),
  ),
  'FATIMA': Account(
    'FATIMA',
    String.fromEnvironment('FATIMA_EMAIL'),
    String.fromEnvironment('FATIMA_PASSWORD'),
  ),
  'RAJESH': Account(
    'RAJESH',
    String.fromEnvironment('RAJESH_EMAIL'),
    String.fromEnvironment('RAJESH_PASSWORD'),
  ),
  'SARA': Account(
    'SARA',
    String.fromEnvironment('SARA_EMAIL'),
    String.fromEnvironment('SARA_PASSWORD'),
  ),
};

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  // Render every frame the app asks for, so transitions record smoothly.
  binding.framePolicy = LiveTestWidgetsFlutterBindingFramePolicy.fullyLive;

  testWidgets(
    'store review: $scenario',
    timeout: const Timeout(Duration(minutes: 5)),
    (tester) async {
      final script = scenarios[scenario];
      if (script == null) {
        fail(
          'SCENARIO must be one of ${scenarios.keys.join(', ')} '
          '(got "$scenario")',
        );
      }
      final account = _accounts[scenarioAccounts[scenario]]!;
      expect(
        account.email,
        isNotEmpty,
        reason: '${account.name}_EMAIL is missing from the defines file',
      );
      expect(
        account.password,
        isNotEmpty,
        reason: '${account.name}_PASSWORD is missing from the defines file',
      );

      // The app's own main(), with one seam: an opened document is not handed to
      // the system share sheet, which would cover the app on camera and wait for
      // a person to dismiss it. The download itself still runs.
      if (Platform.isAndroid) await Firebase.initializeApp();
      GoogleFonts.config.allowRuntimeFetching = false;
      final container = ProviderContainer(
        overrides: [
          locationServiceProvider.overrideWithValue(_NoLocation()),
          shareRenterDocumentProvider.overrideWithValue(
            (bytes, name, origin) async {},
          ),
        ],
      );
      addTearDown(container.dispose);
      // Debug builds log every request body, the login's password included.
      container
          .read(apiClientProvider)
          .dio
          .interceptors
          .removeWhere((i) => i is LogInterceptor);
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const RenterApp(),
        ),
      );

      final r = Reviewer(tester, container);
      final loginForm = find.byType(TextFormField);
      Future<void> signOutIfNeeded() async {
        if (container.read(authProvider).isAuthenticated) {
          await container.read(authProvider.notifier).logout();
        }
      }

      // Even a failed scenario leaves the next one a signed-out app.
      addTearDown(signOutIfNeeded);
      // Splash, then either the login form or a session left by an earlier
      // run. The login screen can show for a moment while the stored session
      // is still being read, so wait for auth to settle first.
      await r.waitUntil(
        () =>
            !container.read(authProvider).isLoading &&
            (r.has(loginForm) || r.has(find.byType(ShellScreen))),
        'the app to start',
        seconds: 60,
      );
      await r.dwell(1);
      await signOutIfNeeded();
      await r.waitUntil(
        () => r.has(loginForm) && !container.read(authProvider).isAuthenticated,
        'the login screen',
        seconds: 30,
      );
      await container
          .read(appLanguageProvider.notifier)
          .setLanguage(AppLanguage.en);
      await container.read(themeModeProvider.notifier).setMode(ThemeMode.light);
      await r.dwell(1.5);

      print('[store-review] record-start');
      await r.dwell(2);
      await r.signIn(account);
      await script(r);
      await r.dwell(2);
      print('[store-review] record-stop');

      // Off camera: leave the next scenario a clean login screen.
      await signOutIfNeeded();
      await r.dwell(1);
    },
  );
}

class _NoLocation extends LocationService {
  @override
  Future<Never?> getCurrentPosition() async => null;
}

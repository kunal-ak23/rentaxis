import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'auth/phone_auth_service.dart';
import 'screens/phone_login_screen.dart';
import 'screens/otp_screen.dart';
import 'screens/home_screen.dart';
import 'screens/scan_screen.dart';
import 'screens/result_screen.dart';
import 'screens/approvals_screen.dart';
import 'screens/walk_in_screen.dart';
import 'screens/walk_in_status_screen.dart';

/// Guard app router. Auth state comes from core's shared [authProvider] —
/// guards authenticate via Firebase phone auth
/// ([AuthNotifier.loginWithFirebase]), which
/// persists the same identity payload as password login, so the redirect rules
/// below mirror the renter/manager apps exactly.
///
/// **Why this listens instead of watching.** It used to `ref.watch(authProvider)`
/// and close over the result, which rebuilt the whole [GoRouter] on every
/// AuthState change. `MaterialApp.router` restarts a new `routerConfig` from
/// [initialLocation], so each rebuild silently threw away the navigation stack
/// and every route's `extra`. That went unnoticed while login was one shot
/// (password login ends authenticated, and a reset to '/' is where you wanted to
/// be anyway), but phone login has an intermediate screen and a failure path:
/// `loginWithFirebase` sets `isLoading: true` *before* the network call, which
/// rebuilt the router mid-verify, destroyed /otp along with the phone it was
/// verifying, and — on a wrong code — left the guard back on /login with the
/// 'Invalid or expired code' error set on a screen that no longer existed.
///
/// So the router is built once and refreshed via [refreshListenable]; the
/// redirect reads auth state per evaluation rather than closing over a snapshot.
/// Keep it that way: reintroducing `ref.watch` here would restore a rebuild that
/// looks harmless and breaks the OTP flow specifically.
final routerProvider = Provider<GoRouter>((ref) {
  const tutorialCapture = bool.fromEnvironment('TUTORIAL_CAPTURE');
  final refresh = ValueNotifier<int>(0);
  ref.listen<AuthState>(authProvider, (_, _) => refresh.value++);
  ref.onDispose(refresh.dispose);

  return GoRouter(
    // Dev affordance: --dart-define=START_ROUTE=/payments boots straight to a
    // screen. Defaults to the normal splash entry, so release is unchanged.
    initialLocation: const String.fromEnvironment(
      'START_ROUTE',
      defaultValue: '/splash',
    ),
    refreshListenable: refresh,
    redirect: (context, state) {
      final authState = ref.read(authProvider);
      final isLoggedIn = authState.isAuthenticated;
      final isLoading = authState.isLoading;
      final isLoginRoute = state.matchedLocation == '/login';
      final isOtpRoute = state.matchedLocation == '/otp';
      final isSplashRoute = state.matchedLocation == '/splash';
      final isUpdateRoute = state.matchedLocation == '/update-required';

      // Hard version gate wins over every other rule. Once the installed build
      // is below the supported floor, pin the guard on /update-required and let
      // nothing navigate away. Inert until the gate resolves (fail open) or
      // when it says ok — `valueOrNull` is null while still loading.
      final gateRequired = ref
              .read(appGateProvider(AppId.security))
              .valueOrNull
              ?.requiresUpdate ??
          false;
      if (gateRequired) {
        return isUpdateRoute ? null : '/update-required';
      }
      if (isUpdateRoute) {
        return isLoggedIn ? '/' : '/login';
      }

      if (isSplashRoute) return null;
      if (isLoading) return null; // Wait for stored session check to resolve
      if (!isLoggedIn && !isLoginRoute && !isOtpRoute) return '/login';
      if (isLoggedIn && (isLoginRoute || isOtpRoute)) return '/';
      return null;
    },
    routes: [
      GoRoute(
        path: '/splash',
        builder: (context, state) => VideoSplashScreen(
          backgroundAlignment: const Alignment(0.3, 0),
          onComplete: () async {
            // Consult the version gate before the usual auth routing. The gate
            // fails open: any error/timeout resolves to `ok`, so this only ever
            // diverts the guard when the backend explicitly raised the floor.
            final decision =
                await ref.read(appGateProvider(AppId.security).future);
            if (!context.mounted) return;
            if (decision.requiresUpdate) {
              GoRouter.of(context).go('/update-required');
              return;
            }
            final messenger = decision.updateAvailable
                ? ScaffoldMessenger.maybeOf(context)
                : null;
            GoRouter.of(context)
                .go(ref.read(authProvider).isAuthenticated ? '/' : '/login');
            if (messenger != null) {
              showUpdateAvailableBanner(messenger, storeUrl: decision.storeUrl);
            }
          },
        ),
      ),
      GoRoute(
        path: '/login',
        builder: (context, state) => const PhoneLoginScreen(),
      ),
      GoRoute(
        path: '/update-required',
        builder: (context, state) => UpdateRequiredScreen(
          storeUrl: ref
                  .read(appGateProvider(AppId.security))
                  .valueOrNull
                  ?.storeUrl ??
              '',
        ),
      ),
      GoRoute(
        path: '/otp',
        // The Firebase verification session travels as `extra`, so it survives
        // nothing: a deep
        // link, a hot reload, or a process restart all land here with extra ==
        // null. Bounce those back to /login to re-enter the number rather than
        // building a screen with no phone to verify. Route-level redirects run
        // after the top-level one, so the authenticated -> '/' rule above still
        // wins over this.
        redirect: (context, state) =>
            state.extra is PhoneVerificationSession ? null : '/login',
        // A restored Android activity can ask GoRouter to build the last route
        // before its redirect has completed. Keep the builder defensive too;
        // relying only on the redirect still permits a one-frame null cast.
        builder: (context, state) {
          final session = state.extra;
          return session is PhoneVerificationSession
              ? OtpScreen(session: session)
              : const PhoneLoginScreen();
        },
      ),
      GoRoute(path: '/', builder: (context, state) => const HomeScreen()),
      GoRoute(
        path: '/scan',
        builder: (context, state) => tutorialCapture
            ? ScanScreen(
                // The software emulator exposes no usable camera lens. Keep
                // the real scan screen and code fallback, but replace only the
                // platform viewfinder for deterministic tutorial capture.
                viewfinderBuilder: (context, onDetect) => DecoratedBox(
                  decoration: const BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topCenter,
                      end: Alignment.bottomCenter,
                      colors: [Color(0xFF263238), Color(0xFF080B0D)],
                    ),
                  ),
                  child: const Center(
                    child: Icon(Icons.qr_code_2, size: 112, color: Colors.white24),
                  ),
                ),
              )
            : const ScanScreen(),
      ),
      GoRoute(
        path: '/result',
        // The verdict travels as `extra`, exactly like /otp's phone, and with
        // the same consequence: a deep link, a hot reload or a process restart
        // arrives with extra == null. There is nothing to re-fetch — a
        // ScanResponse only exists as the answer to a scan, and re-scanning
        // needs the pass in hand — so send the guard back to the board rather
        // than building a verdict screen with no verdict.
        redirect: (context, state) =>
            state.extra is ScanResultArgs ? null : '/',
        builder: (context, state) {
          final args = state.extra;
          return args is ScanResultArgs
              ? ResultScreen(args: args)
              : const HomeScreen();
        },
      ),
      GoRoute(
        path: '/approvals',
        builder: (context, state) => const ApprovalsScreen(),
      ),
      GoRoute(
        path: '/walk-in',
        builder: (context, state) => const WalkInScreen(),
      ),
      GoRoute(
        path: '/walk-in/:id',
        builder: (context, state) =>
            WalkInStatusScreen(passId: state.pathParameters['id']!),
      ),
    ],
  );
});

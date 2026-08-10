import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart' show Alignment;
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
  final refresh = ValueNotifier<int>(0);
  ref.listen<AuthState>(authProvider, (_, _) => refresh.value++);
  ref.onDispose(refresh.dispose);

  return GoRouter(
    initialLocation: '/splash',
    refreshListenable: refresh,
    redirect: (context, state) {
      final authState = ref.read(authProvider);
      final isLoggedIn = authState.isAuthenticated;
      final isLoading = authState.isLoading;
      final isLoginRoute = state.matchedLocation == '/login';
      final isOtpRoute = state.matchedLocation == '/otp';
      final isSplashRoute = state.matchedLocation == '/splash';

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
          onComplete: () {
            if (ref.read(authProvider).isAuthenticated) {
              GoRouter.of(context).go('/');
            } else {
              GoRouter.of(context).go('/login');
            }
          },
        ),
      ),
      GoRoute(
        path: '/login',
        builder: (context, state) => const PhoneLoginScreen(),
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
      GoRoute(path: '/scan', builder: (context, state) => const ScanScreen()),
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

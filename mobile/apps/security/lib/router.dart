import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'screens/login_screen.dart';
import 'screens/otp_screen.dart';
import 'screens/home_screen.dart';
import 'screens/scan_screen.dart';
import 'screens/result_screen.dart';
import 'screens/approvals_screen.dart';

/// Guard app router. Auth state comes from core's shared [authProvider] —
/// guards authenticate via phone OTP ([AuthNotifier.loginWithOtp]), which
/// persists the same identity payload as password login, so this redirect
/// logic mirrors the renter/manager apps exactly.
final routerProvider = Provider<GoRouter>((ref) {
  final authState = ref.watch(authProvider);

  return GoRouter(
    initialLocation: '/',
    redirect: (context, state) {
      final isLoggedIn = authState.isAuthenticated;
      final isLoading = authState.isLoading;
      final isLoginRoute = state.matchedLocation == '/login';
      final isOtpRoute = state.matchedLocation == '/otp';

      if (isLoading) return null; // Wait for stored session check to resolve
      if (!isLoggedIn && !isLoginRoute && !isOtpRoute) return '/login';
      if (isLoggedIn && (isLoginRoute || isOtpRoute)) return '/';
      return null;
    },
    routes: [
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginScreen(),
      ),
      GoRoute(
        path: '/otp',
        builder: (context, state) => const OtpScreen(),
      ),
      GoRoute(
        path: '/',
        builder: (context, state) => const HomeScreen(),
      ),
      GoRoute(
        path: '/scan',
        builder: (context, state) => const ScanScreen(),
      ),
      GoRoute(
        path: '/result',
        builder: (context, state) => const ResultScreen(),
      ),
      GoRoute(
        path: '/approvals',
        builder: (context, state) => const ApprovalsScreen(),
      ),
    ],
  );
});

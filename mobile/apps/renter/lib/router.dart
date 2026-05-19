import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'screens/login_screen.dart';
import 'screens/set_password_screen.dart';
import 'screens/home_screen.dart';
import 'screens/payments_screen.dart';
import 'screens/tickets_screen.dart';
import 'screens/create_ticket_screen.dart';
import 'screens/ticket_detail_screen.dart';
import 'screens/notifications_screen.dart';
import 'screens/profile_screen.dart';
import 'screens/shell_screen.dart';
import 'screens/browse/browse_screen.dart';
import 'screens/browse/listing_detail_screen.dart';
import 'screens/wishlist/wishlist_screen.dart';
import 'screens/meetings_screen.dart';
import 'screens/meeting_detail_screen.dart';
import 'screens/create_meeting_screen.dart';
import 'screens/penalties_screen.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final authState = ref.watch(authProvider);

  return GoRouter(
    initialLocation: '/splash',
    redirect: (context, state) {
      final isLoggedIn = authState.isAuthenticated;
      final isLoading = authState.isLoading;
      final isLoginRoute = state.matchedLocation == '/login';
      final isSplashRoute = state.matchedLocation == '/splash';
      final isSetPasswordRoute = state.matchedLocation == '/set-password';

      if (isSplashRoute) return null; // Always allow splash
      if (isSetPasswordRoute) return null; // Allow unauthenticated access
      if (isLoading) return null;
      if (!isLoggedIn && !isLoginRoute) return '/login';
      if (isLoggedIn && isLoginRoute) return '/';
      return null;
    },
    routes: [
      GoRoute(
        path: '/splash',
        builder: (context, state) => VideoSplashScreen(
          onComplete: () {
            // Navigate to login or home based on auth state
            if (authState.isAuthenticated) {
              GoRouter.of(context).go('/');
            } else {
              GoRouter.of(context).go('/login');
            }
          },
        ),
      ),
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginScreen(),
      ),
      GoRoute(
        path: '/set-password',
        builder: (context, state) {
          final token = state.uri.queryParameters['token'] ?? '';
          return SetPasswordScreen(token: token);
        },
      ),
      ShellRoute(
        builder: (context, state, child) => ShellScreen(child: child),
        routes: [
          GoRoute(
            path: '/',
            builder: (context, state) => const HomeScreen(),
          ),
          GoRoute(
            path: '/payments',
            builder: (context, state) => const PaymentsScreen(),
          ),
          GoRoute(
            path: '/tickets',
            builder: (context, state) => const TicketsScreen(),
            routes: [
              GoRoute(
                path: 'create',
                builder: (context, state) => const CreateTicketScreen(),
              ),
              GoRoute(
                path: ':id',
                builder: (context, state) => TicketDetailScreen(
                  ticketId: state.pathParameters['id']!,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/profile',
            builder: (context, state) => const ProfileScreen(),
          ),
          GoRoute(
            path: '/notifications',
            builder: (context, state) => const NotificationsScreen(),
          ),
          GoRoute(
            path: '/browse',
            builder: (context, state) => const BrowseScreen(),
            routes: [
              GoRoute(
                path: ':slug',
                builder: (context, state) => ListingDetailScreen(
                  slug: state.pathParameters['slug']!,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/wishlist',
            builder: (context, state) => const WishlistScreen(),
          ),
          GoRoute(
            path: '/meetings',
            builder: (context, state) => const MeetingsScreen(),
            routes: [
              GoRoute(
                path: 'create',
                builder: (context, state) => const CreateMeetingScreen(),
              ),
              GoRoute(
                path: ':id',
                builder: (context, state) => MeetingDetailScreen(
                  meetingId: state.pathParameters['id']!,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/penalties',
            builder: (context, state) => const PenaltiesScreen(),
          ),
        ],
      ),
    ],
  );
});

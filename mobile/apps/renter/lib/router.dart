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
import 'screens/gatepass/gate_pass_list_screen.dart';
import 'screens/gatepass/gate_pass_create_screen.dart';
import 'screens/gatepass/gate_pass_detail_screen.dart';
import 'screens/gatepass/resident_approvals_screen.dart';
import 'screens/facilities/facilities_screen.dart';
import 'screens/facilities/my_requests_screen.dart';
import 'screens/services_hub_screen.dart';

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
      GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
      GoRoute(
        path: '/set-password',
        builder: (context, state) {
          final token = state.uri.queryParameters['token'] ?? '';
          return SetPasswordScreen(token: token);
        },
      ),
      ShellRoute(
        builder: (context, state, child) => ShellScreen(child: child),
        // Tab roots cross-fade; pushed create/detail screens slide up. Both
        // helpers live in rentaxis_core/widgets/page_transitions.dart.
        routes: [
          GoRoute(
            path: '/',
            pageBuilder: (context, state) =>
                fadeTransition(const HomeScreen(), state),
          ),
          GoRoute(
            path: '/payments',
            pageBuilder: (context, state) =>
                fadeTransition(const PaymentsScreen(), state),
          ),
          // Services hub — the tab root that now fronts Meetings, Tickets,
          // Facilities, Gate passes, Approvals and Penalties.
          GoRoute(
            path: '/services',
            pageBuilder: (context, state) =>
                fadeTransition(const ServicesHubScreen(), state),
          ),
          GoRoute(
            path: '/tickets',
            pageBuilder: (context, state) =>
                fadeTransition(const TicketsScreen(), state),
            routes: [
              GoRoute(
                path: 'approvals',
                pageBuilder: (context, state) =>
                    slideUpTransition(const ResidentApprovalsScreen(), state),
              ),
              GoRoute(
                path: 'create',
                pageBuilder: (context, state) =>
                    slideUpTransition(const CreateTicketScreen(), state),
              ),
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  TicketDetailScreen(ticketId: state.pathParameters['id']!),
                  state,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/profile',
            pageBuilder: (context, state) =>
                fadeTransition(const ProfileScreen(), state),
          ),
          GoRoute(
            path: '/notifications',
            pageBuilder: (context, state) =>
                fadeTransition(const NotificationsScreen(), state),
          ),
          GoRoute(
            path: '/browse',
            pageBuilder: (context, state) =>
                fadeTransition(const BrowseScreen(), state),
            routes: [
              GoRoute(
                path: ':slug',
                pageBuilder: (context, state) => slideUpTransition(
                  ListingDetailScreen(slug: state.pathParameters['slug']!),
                  state,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/wishlist',
            pageBuilder: (context, state) =>
                fadeTransition(const WishlistScreen(), state),
          ),
          GoRoute(
            path: '/meetings',
            pageBuilder: (context, state) =>
                fadeTransition(const MeetingsScreen(), state),
            routes: [
              GoRoute(
                path: 'create',
                pageBuilder: (context, state) =>
                    slideUpTransition(const CreateMeetingScreen(), state),
              ),
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  MeetingDetailScreen(meetingId: state.pathParameters['id']!),
                  state,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/penalties',
            pageBuilder: (context, state) =>
                fadeTransition(const PenaltiesScreen(), state),
          ),
          // The pass id travels in the path and the detail screen re-fetches it.
          // Nothing is passed through `extra` here on purpose: this router
          // rebuilds on `authProvider`, so an auth-state change mid-navigation
          // would discard an `extra` payload and leave the screen with nothing.
          GoRoute(
            path: '/gatepass',
            pageBuilder: (context, state) =>
                fadeTransition(const GatePassListScreen(), state),
            routes: [
              GoRoute(
                path: 'create',
                pageBuilder: (context, state) =>
                    slideUpTransition(const GatePassCreateScreen(), state),
              ),
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  GatePassDetailScreen(passId: state.pathParameters['id']!),
                  state,
                ),
              ),
            ],
          ),
          // Facility screens are parameterless and self-fetching for the same
          // reason as the gate-pass screens above: this router rebuilds on
          // authProvider and would discard `extra`.
          GoRoute(
            path: '/facilities',
            pageBuilder: (context, state) =>
                fadeTransition(const FacilitiesScreen(), state),
            routes: [
              GoRoute(
                path: 'requests',
                pageBuilder: (context, state) =>
                    slideUpTransition(const MyRequestsScreen(), state),
              ),
            ],
          ),
        ],
      ),
    ],
  );
});

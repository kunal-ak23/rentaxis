import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'screens/login_screen.dart';
import 'screens/shell_screen.dart';
import 'screens/dashboard_screen.dart';
import 'screens/properties_screen.dart';
import 'screens/property_detail_screen.dart';
import 'screens/leases_screen.dart';
import 'screens/lease_detail_screen.dart';
import 'screens/payments_screen.dart';
import 'screens/tickets_screen.dart';
import 'screens/create_ticket_screen.dart';
import 'screens/ticket_detail_screen.dart';
import 'screens/renters_screen.dart';
import 'screens/finance_screen.dart';
import 'screens/more_screen.dart';
import 'screens/profile_screen.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final authState = ref.watch(authProvider);

  return GoRouter(
    initialLocation: '/login',
    redirect: (context, state) {
      final isLoggedIn = authState.isAuthenticated;
      final isLoading = authState.isLoading;
      final isLoginRoute = state.matchedLocation == '/login';

      if (isLoading) return null;
      if (!isLoggedIn && !isLoginRoute) return '/login';
      if (isLoggedIn && isLoginRoute) return '/';
      return null;
    },
    routes: [
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginScreen(),
      ),
      ShellRoute(
        builder: (context, state, child) => ShellScreen(child: child),
        routes: [
          GoRoute(
            path: '/',
            builder: (context, state) => const DashboardScreen(),
          ),
          GoRoute(
            path: '/properties',
            builder: (context, state) => const PropertiesScreen(),
            routes: [
              GoRoute(
                path: ':id',
                builder: (context, state) => PropertyDetailScreen(
                  propertyId: state.pathParameters['id']!,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/leases',
            builder: (context, state) => const LeasesScreen(),
            routes: [
              GoRoute(
                path: ':id',
                builder: (context, state) => LeaseDetailScreen(
                  leaseId: state.pathParameters['id']!,
                ),
              ),
            ],
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
            path: '/renters',
            builder: (context, state) => const RentersScreen(),
          ),
          GoRoute(
            path: '/finance',
            builder: (context, state) => const FinanceScreen(),
          ),
          GoRoute(
            path: '/more',
            builder: (context, state) => const MoreScreen(),
          ),
          GoRoute(
            path: '/profile',
            builder: (context, state) => const ProfileScreen(),
          ),
        ],
      ),
    ],
  );
});

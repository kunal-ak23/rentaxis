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
import 'screens/staff_screen.dart';
import 'screens/staff_detail_screen.dart';
import 'screens/vendors_screen.dart';
import 'screens/vendor_detail_screen.dart';
import 'screens/bank_accounts_screen.dart';
import 'screens/finance_reports_screen.dart';
import 'screens/report_detail_screen.dart';
import 'screens/lease_penalties_screen.dart';
import 'screens/lease_settlement_screen.dart';
import 'screens/settings_hub_screen.dart';
import 'screens/rent_settings_screen.dart';
import 'screens/gateway_config_screen.dart';
import 'screens/account_mappings_screen.dart';
import 'screens/notifications_screen.dart';
import 'screens/listings/listings_list_screen.dart';
import 'screens/listings/listing_edit_screen.dart';
import 'screens/listings/listing_interests_screen.dart';
import 'screens/meetings_screen.dart';
import 'screens/meeting_detail_screen.dart';
import 'screens/create_meeting_screen.dart';
import 'screens/cheque_scan/cheque_scan_flow_screen.dart';
import 'screens/gatepass/gate_pass_approvals_screen.dart';
import 'screens/gatepass/guard_management_screen.dart';
import 'screens/gatepass/gate_access_policy_screen.dart';
import 'screens/gatepass/register_gate_vendor_screen.dart';
import 'screens/facilities/facilities_screen.dart';
import 'screens/facilities/booking_approvals_screen.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final authState = ref.watch(authProvider);

  return GoRouter(
    initialLocation: '/splash',
    redirect: (context, state) {
      final isLoggedIn = authState.isAuthenticated;
      final isLoading = authState.isLoading;
      final isLoginRoute = state.matchedLocation == '/login';
      final isSplashRoute = state.matchedLocation == '/splash';

      if (isSplashRoute) return null;
      if (isLoading) {
        return null;
      }
      if (!isLoggedIn && !isLoginRoute) {
        return '/login';
      }
      if (isLoggedIn && isLoginRoute) {
        return '/';
      }
      return null;
    },
    routes: [
      GoRoute(
        path: '/splash',
        builder: (context, state) => VideoSplashScreen(
          onComplete: () {
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
        path: '/scan',
        builder: (context, state) => ChequeScanFlowScreen(
          paymentId: state.uri.queryParameters['paymentId'],
        ),
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
                builder: (context, state) =>
                    LeaseDetailScreen(leaseId: state.pathParameters['id']!),
                routes: [
                  GoRoute(
                    path: 'penalties',
                    builder: (context, state) => LeasePenaltiesScreen(
                      leaseId: state.pathParameters['id']!,
                    ),
                  ),
                  GoRoute(
                    path: 'settlement',
                    builder: (context, state) => LeaseSettlementScreen(
                      leaseId: state.pathParameters['id']!,
                    ),
                  ),
                ],
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
                builder: (context, state) =>
                    TicketDetailScreen(ticketId: state.pathParameters['id']!),
              ),
            ],
          ),
          GoRoute(
            path: '/notifications',
            builder: (context, state) => const NotificationsScreen(),
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
                builder: (context, state) =>
                    MeetingDetailScreen(meetingId: state.pathParameters['id']!),
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
          GoRoute(
            path: '/staff',
            builder: (context, state) => const StaffScreen(),
            routes: [
              GoRoute(
                path: ':id',
                builder: (context, state) =>
                    StaffDetailScreen(staffId: state.pathParameters['id']!),
              ),
            ],
          ),
          // Both gate-pass screens are parameterless and re-fetch their own
          // state, so neither passes anything through GoRouter `extra` — which
          // this router would discard on any auth-state change, since it
          // rebuilds on `authProvider`.
          GoRoute(
            path: '/gate-passes/approvals',
            builder: (context, state) => const GatePassApprovalsScreen(),
          ),
          GoRoute(
            path: '/gate-passes/guards',
            builder: (context, state) => const GuardManagementScreen(),
          ),
          GoRoute(
            path: '/gate-passes/policy',
            builder: (context, state) => const GateAccessPolicyScreen(),
          ),
          GoRoute(
            path: '/gate-passes/vendors',
            builder: (context, state) => const RegisterGateVendorScreen(),
          ),
          // Facility screens are parameterless and self-fetching for the same
          // reason as the gate-pass screens above: this router rebuilds on
          // authProvider and would discard `extra`.
          GoRoute(
            path: '/facilities',
            pageBuilder: (context, state) =>
                fadeTransition(const FacilitiesScreen(), state),
          ),
          GoRoute(
            path: '/bookings',
            pageBuilder: (context, state) =>
                fadeTransition(const BookingApprovalsScreen(), state),
          ),
          GoRoute(
            path: '/vendors',
            builder: (context, state) => const VendorsScreen(),
            routes: [
              GoRoute(
                path: ':id',
                builder: (context, state) =>
                    VendorDetailScreen(vendorId: state.pathParameters['id']!),
              ),
            ],
          ),
          GoRoute(
            path: '/bank-accounts',
            builder: (context, state) => const BankAccountsScreen(),
          ),
          GoRoute(
            path: '/finance-reports',
            builder: (context, state) => const FinanceReportsScreen(),
          ),
          GoRoute(
            path: '/listings',
            builder: (context, state) => const ListingsListScreen(),
            routes: [
              GoRoute(
                path: ':id',
                builder: (context, state) =>
                    ListingEditScreen(listingId: state.pathParameters['id']!),
                routes: [
                  GoRoute(
                    path: 'interests',
                    builder: (context, state) => ListingInterestsScreen(
                      listingId: state.pathParameters['id']!,
                    ),
                  ),
                ],
              ),
            ],
          ),
          GoRoute(
            path: '/settings',
            builder: (context, state) => const SettingsHubScreen(),
            routes: [
              GoRoute(
                path: 'rent',
                builder: (context, state) => const RentSettingsScreen(),
              ),
              GoRoute(
                path: 'gateway',
                builder: (context, state) => const GatewayConfigScreen(),
              ),
              GoRoute(
                path: 'mappings',
                builder: (context, state) => const AccountMappingsScreen(),
              ),
            ],
          ),
        ],
      ),
    ],
  );
});

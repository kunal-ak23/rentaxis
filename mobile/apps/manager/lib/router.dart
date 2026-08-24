import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'screens/login_screen.dart';
import 'screens/shell_screen.dart';
import 'screens/queue_screen.dart';
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
    // Dev affordance: --dart-define=START_ROUTE=/payments boots straight to a
    // screen. Defaults to the normal splash entry, so release is unchanged.
    initialLocation: const String.fromEnvironment(
      'START_ROUTE',
      defaultValue: '/splash',
    ),
    redirect: (context, state) {
      final isLoggedIn = authState.isAuthenticated;
      final isLoading = authState.isLoading;
      final isLoginRoute = state.matchedLocation == '/login';
      final isSplashRoute = state.matchedLocation == '/splash';
      final isUpdateRoute = state.matchedLocation == '/update-required';

      // Hard version gate wins over every other rule. Once the installed build
      // is below the supported floor, pin the user on /update-required and let
      // nothing navigate away. Inert until the gate resolves (fail open) or
      // when it says ok — `valueOrNull` is null while still loading.
      final gateRequired =
          ref.read(appGateProvider(AppId.manager)).valueOrNull?.requiresUpdate ??
              false;
      if (gateRequired) {
        return isUpdateRoute ? null : '/update-required';
      }
      if (isUpdateRoute) {
        return isLoggedIn ? '/' : '/login';
      }

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
          backgroundAlignment: const Alignment(0.1, 0),
          onComplete: () async {
            // Consult the version gate before the usual auth routing. The gate
            // fails open: any error/timeout resolves to `ok`, so this only ever
            // diverts the user when the backend explicitly raised the floor.
            final decision =
                await ref.read(appGateProvider(AppId.manager).future);
            if (!context.mounted) return;
            if (decision.requiresUpdate) {
              GoRouter.of(context).go('/update-required');
              return;
            }
            final messenger = decision.updateAvailable
                ? ScaffoldMessenger.maybeOf(context)
                : null;
            GoRouter.of(context).go(authState.isAuthenticated ? '/' : '/login');
            if (messenger != null) {
              showUpdateAvailableBanner(messenger, storeUrl: decision.storeUrl);
            }
          },
        ),
      ),
      GoRoute(path: '/login', builder: (context, state) => const LoginScreen()),
      GoRoute(
        path: '/update-required',
        builder: (context, state) => UpdateRequiredScreen(
          storeUrl: ref
                  .read(appGateProvider(AppId.manager))
                  .valueOrNull
                  ?.storeUrl ??
              '',
        ),
      ),
      GoRoute(
        path: '/scan',
        builder: (context, state) => ChequeScanFlowScreen(
          paymentId: state.uri.queryParameters['paymentId'],
        ),
      ),
      ShellRoute(
        builder: (context, state, child) => ShellScreen(child: child),
        // Tab roots cross-fade; pushed create/detail screens slide up. Both
        // helpers live in rentaxis_core/widgets/page_transitions.dart.
        routes: [
          GoRoute(
            path: '/',
            pageBuilder: (context, state) =>
                fadeTransition(const DashboardScreen(), state),
          ),
          GoRoute(
            path: '/properties',
            pageBuilder: (context, state) =>
                fadeTransition(const PropertiesScreen(), state),
            routes: [
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  PropertyDetailScreen(propertyId: state.pathParameters['id']!),
                  state,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/leases',
            pageBuilder: (context, state) =>
                fadeTransition(const LeasesScreen(), state),
            routes: [
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  LeaseDetailScreen(leaseId: state.pathParameters['id']!),
                  state,
                ),
                routes: [
                  GoRoute(
                    path: 'penalties',
                    pageBuilder: (context, state) => slideUpTransition(
                      LeasePenaltiesScreen(
                        leaseId: state.pathParameters['id']!,
                      ),
                      state,
                    ),
                  ),
                  GoRoute(
                    path: 'settlement',
                    pageBuilder: (context, state) => slideUpTransition(
                      LeaseSettlementScreen(
                        leaseId: state.pathParameters['id']!,
                      ),
                      state,
                    ),
                  ),
                ],
              ),
            ],
          ),
          GoRoute(
            path: '/payments',
            pageBuilder: (context, state) =>
                fadeTransition(const PaymentsScreen(), state),
          ),
          GoRoute(
            path: '/tickets',
            pageBuilder: (context, state) =>
                fadeTransition(const TicketsScreen(), state),
            routes: [
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
            path: '/notifications',
            pageBuilder: (context, state) =>
                fadeTransition(const NotificationsScreen(), state),
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
            path: '/renters',
            pageBuilder: (context, state) =>
                fadeTransition(const RentersScreen(), state),
          ),
          GoRoute(
            // Queue — the single approvals surface the redesign introduces.
            path: '/queue',
            pageBuilder: (context, state) =>
                fadeTransition(const QueueScreen(), state),
          ),
          GoRoute(
            path: '/finance',
            pageBuilder: (context, state) =>
                fadeTransition(const FinanceScreen(), state),
          ),
          GoRoute(
            path: '/more',
            pageBuilder: (context, state) =>
                fadeTransition(const MoreScreen(), state),
          ),
          GoRoute(
            path: '/profile',
            pageBuilder: (context, state) =>
                fadeTransition(const ProfileScreen(), state),
          ),
          GoRoute(
            path: '/staff',
            pageBuilder: (context, state) =>
                fadeTransition(const StaffScreen(), state),
            routes: [
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  StaffDetailScreen(staffId: state.pathParameters['id']!),
                  state,
                ),
              ),
            ],
          ),
          // Both gate-pass screens are parameterless and re-fetch their own
          // state, so neither passes anything through GoRouter `extra` — which
          // this router would discard on any auth-state change, since it
          // rebuilds on `authProvider`.
          GoRoute(
            path: '/gate-passes/approvals',
            pageBuilder: (context, state) =>
                fadeTransition(const GatePassApprovalsScreen(), state),
          ),
          GoRoute(
            path: '/gate-passes/guards',
            pageBuilder: (context, state) =>
                fadeTransition(const GuardManagementScreen(), state),
          ),
          GoRoute(
            path: '/gate-passes/policy',
            pageBuilder: (context, state) =>
                fadeTransition(const GateAccessPolicyScreen(), state),
          ),
          GoRoute(
            path: '/gate-passes/vendors',
            pageBuilder: (context, state) =>
                fadeTransition(const RegisterGateVendorScreen(), state),
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
            pageBuilder: (context, state) =>
                fadeTransition(const VendorsScreen(), state),
            routes: [
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  VendorDetailScreen(vendorId: state.pathParameters['id']!),
                  state,
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/bank-accounts',
            pageBuilder: (context, state) =>
                fadeTransition(const BankAccountsScreen(), state),
          ),
          GoRoute(
            path: '/finance-reports',
            pageBuilder: (context, state) =>
                fadeTransition(const FinanceReportsScreen(), state),
          ),
          GoRoute(
            path: '/listings',
            pageBuilder: (context, state) =>
                fadeTransition(const ListingsListScreen(), state),
            routes: [
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  ListingEditScreen(listingId: state.pathParameters['id']!),
                  state,
                ),
                routes: [
                  GoRoute(
                    path: 'interests',
                    pageBuilder: (context, state) => slideUpTransition(
                      ListingInterestsScreen(
                        listingId: state.pathParameters['id']!,
                      ),
                      state,
                    ),
                  ),
                ],
              ),
            ],
          ),
          GoRoute(
            path: '/settings',
            pageBuilder: (context, state) =>
                fadeTransition(const SettingsHubScreen(), state),
            routes: [
              GoRoute(
                path: 'rent',
                pageBuilder: (context, state) =>
                    slideUpTransition(const RentSettingsScreen(), state),
              ),
              GoRoute(
                path: 'gateway',
                pageBuilder: (context, state) =>
                    slideUpTransition(const GatewayConfigScreen(), state),
              ),
              GoRoute(
                path: 'mappings',
                pageBuilder: (context, state) =>
                    slideUpTransition(const AccountMappingsScreen(), state),
              ),
            ],
          ),
        ],
      ),
    ],
  );
});

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'queue_screen.dart' show managerQueueCountProvider;

/// Full ledger/reporting data is deliberately limited to organisation admins.
/// Property managers still operate the day-to-day payment and cheque workflow,
/// so their Finance tab opens that permitted surface instead of a guaranteed
/// 403 from the chart-of-accounts endpoints.
bool hasFullFinanceAccess(String? role) =>
    role == 'TENANT_ADMIN' || role == 'SUPER_ADMIN';

/// Finance, lease and cheque screens are hidden until the apps are rewritten
/// for accounting v2 (spec D7): plans 1-4 removed or reshaped the endpoints
/// behind them, so what is left would 404 or render blank rows. The role rule
/// still applies on top of the flag.
bool managerFinanceEnabled(String? role, bool flag) => flag;

/// Every location the MOBILE_FINANCE flag covers, as one list so the shell,
/// the screens and the router redirect cannot drift apart.
///
/// `/leases` carries the two screens plan 3 broke outright — terminate (posts
/// `{notes}` with no date, now a 400) and settlement (reads preview fields that
/// no longer exist) — and both are sub-routes of it. `/queue`, `/vendors` and
/// `/settings/rent` are deliberately absent: their endpoints survived v2.
bool isManagerFinanceLocation(String location) =>
    location.startsWith('/finance') ||
    location.startsWith('/payments') ||
    location.startsWith('/leases') ||
    location.startsWith('/portfolio-pnl') ||
    location == '/scan' ||
    location == '/bank-accounts' ||
    location == '/settings/mappings';

/// Null when finance is hidden — callers must not fall back to a route.
String? managerFinanceRouteForRole(String? role, bool financeEnabled) {
  if (!financeEnabled) return null;
  return hasFullFinanceAccess(role) ? '/finance' : '/payments';
}

/// The *logical* nav slot for a location: 0 Today, 1 Portfolio, 2 Finance,
/// 3 Queue. Null means "no tab owns this location" — which, for a gated
/// finance location, is the whole point: the bar must not light up an item it
/// no longer shows. [ShellScreen] maps the logical slot onto the visible bar,
/// which is one item shorter while the flag is off.
int? managerShellIndexForLocation(
  String location, {
  required bool financeEnabled,
}) {
  if (!financeEnabled && isManagerFinanceLocation(location)) return null;
  if (location.startsWith('/properties')) return 1;
  if (location.startsWith('/finance') ||
      location.startsWith('/payments') ||
      location.startsWith('/portfolio-pnl')) {
    return 2;
  }
  if (location.startsWith('/queue')) return 3;
  return 0;
}

class ShellScreen extends ConsumerStatefulWidget {
  final Widget child;
  const ShellScreen({super.key, required this.child});

  @override
  ConsumerState<ShellScreen> createState() => _ShellScreenState();
}

class _ShellScreenState extends ConsumerState<ShellScreen> {
  late final NotificationNotifier _notificationNotifier;

  @override
  void initState() {
    super.initState();
    _notificationNotifier = ref.read(notificationProvider.notifier);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _notificationNotifier.startPolling();
      }
    });
  }

  @override
  void dispose() {
    _notificationNotifier.stopPolling();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).matchedLocation;
    final authState = ref.watch(authProvider);
    final financeEnabled = ref.watch(mobileFinanceEnabledProvider);
    final financeRoute = managerFinanceRouteForRole(authState.role, financeEnabled);
    final isAr = context.isAr;
    final queueCount = ref.watch(managerQueueCountProvider).valueOrNull ?? 0;

    // One list drives both the items and the tap targets, so an absent Finance
    // tab cannot shift Queue's index out from under the router.
    final tabs = <({String route, MiftahNavItem item})>[
      (
        route: '/',
        item: MiftahNavItem(
          icon: Icons.dashboard_rounded,
          label: isAr ? 'اليوم' : 'Today',
        ),
      ),
      (
        route: '/properties',
        item: MiftahNavItem(
          icon: Icons.apartment_rounded,
          label: isAr ? 'المحفظة' : 'Portfolio',
        ),
      ),
      if (financeRoute != null)
        (
          route: financeRoute,
          item: MiftahNavItem(
            icon: Icons.payments_rounded,
            label: isAr ? 'المالية' : 'Finance',
          ),
        ),
      (
        route: '/queue',
        item: MiftahNavItem(
          icon: Icons.inbox_rounded,
          label: isAr ? 'القائمة' : 'Queue',
          badge: queueCount > 0 ? queueCount : null,
        ),
      ),
    ];

    // Logical slot -> visible slot. With the Finance tab dropped, everything
    // after it moves down one. A gated location owns no tab, so it falls back
    // to Today — the same place the router redirect sends it.
    final logicalIndex =
        managerShellIndexForLocation(location, financeEnabled: financeEnabled) ??
            0;
    final currentIndex = financeRoute == null && logicalIndex > 2
        ? logicalIndex - 1
        : logicalIndex;

    // System back from a non-home tab returns to Today instead of exiting
    // the app; back on Today exits as usual.
    return PopScope(
      canPop: currentIndex == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) context.go('/');
      },
      child: Scaffold(
        // No shell AppBar: each screen owns its header in the redesign.
        body: SafeArea(bottom: false, child: widget.child),
        bottomNavigationBar: MiftahNavBar(
          currentIndex: currentIndex,
          onTap: (index) => context.go(
            index >= 0 && index < tabs.length ? tabs[index].route : '/',
          ),
          // Cheque scan is a finance surface too, so the raised action goes
          // with the Finance tab rather than pushing a screen the router
          // would immediately bounce.
          centreIcon: financeEnabled ? Icons.document_scanner_rounded : null,
          centreLabel: financeEnabled ? (isAr ? 'مسح' : 'Scan') : null,
          onCentreTap: financeEnabled ? () => context.push('/scan') : null,
          items: [for (final t in tabs) t.item],
        ),
      ),
    );
  }
}

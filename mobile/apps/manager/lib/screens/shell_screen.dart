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

String managerFinanceRouteForRole(String? role) =>
    hasFullFinanceAccess(role) ? '/finance' : '/payments';

int managerShellIndexForLocation(String location) {
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
    final currentIndex = managerShellIndexForLocation(location);
    final authState = ref.watch(authProvider);
    final financeRoute = managerFinanceRouteForRole(authState.role);
    final isAr = context.isAr;
    final queueCount = ref.watch(managerQueueCountProvider).valueOrNull ?? 0;

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
          onTap: (index) => context.go(switch (index) {
            0 => '/',
            1 => '/properties',
            2 => financeRoute,
            3 => '/queue',
            _ => '/',
          }),
          centreIcon: Icons.document_scanner_rounded,
          centreLabel: isAr ? 'مسح' : 'Scan',
          onCentreTap: () => context.push('/scan'),
          items: [
            MiftahNavItem(
              icon: Icons.dashboard_rounded,
              label: isAr ? 'اليوم' : 'Today',
            ),
            MiftahNavItem(
              icon: Icons.apartment_rounded,
              label: isAr ? 'المحفظة' : 'Portfolio',
            ),
            MiftahNavItem(
              icon: Icons.payments_rounded,
              label: isAr ? 'المالية' : 'Finance',
            ),
            MiftahNavItem(
              icon: Icons.inbox_rounded,
              label: isAr ? 'القائمة' : 'Queue',
              badge: queueCount > 0 ? queueCount : null,
            ),
          ],
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Resident shell.
///
/// The redesign collapses the old six tabs (Home · Browse · Saved · Meetings ·
/// Payments · Tickets) to four plus a raised centre action:
/// Home · Explore · **Pass** · Wallet · Services. Saved now lives inside
/// Explore; Meetings, Tickets, Facilities, Gate passes and Approvals live
/// inside the Services hub.
///
/// The bar is solid, so — unlike the old floating pill — content no longer has
/// to clear it. `extendBody`, the scrim gradient and every
/// `AppInsets.bottomNav(context)` padding are gone.
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

  static const _routes = ['/', '/browse', '/payments', '/services'];

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).matchedLocation;
    final selectedIndex = _calculateIndex(location);
    final isAr = context.isAr;

    // System back from a non-home tab returns to Home instead of exiting
    // the app; back on Home exits as usual (standard Android tab UX).
    return PopScope(
      canPop: selectedIndex == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) context.go('/');
      },
      child: Scaffold(
        // No shell AppBar: in the redesign each screen owns its own header —
        // Home carries the wordmark plus bell and avatar, Wallet carries
        // "Your cheques" plus a penalties pill, and so on.
        body: SafeArea(bottom: false, child: widget.child),
        bottomNavigationBar: MiftahNavBar(
          currentIndex: selectedIndex,
          onTap: (index) => context.go(_routes[index]),
          centreIcon: Icons.qr_code_scanner_rounded,
          centreLabel: isAr ? 'تصريح' : 'Pass',
          onCentreTap: () => context.go('/gatepass'),
          items: [
            MiftahNavItem(
              icon: Icons.home_rounded,
              label: isAr ? 'الرئيسية' : 'Home',
            ),
            MiftahNavItem(
              icon: Icons.travel_explore_rounded,
              label: isAr ? 'استكشاف' : 'Explore',
            ),
            MiftahNavItem(
              icon: Icons.account_balance_wallet_rounded,
              label: isAr ? 'المحفظة' : 'Wallet',
            ),
            MiftahNavItem(
              icon: Icons.grid_view_rounded,
              label: isAr ? 'الخدمات' : 'Services',
            ),
          ],
        ),
      ),
    );
  }

  int _calculateIndex(String location) {
    if (location.startsWith('/browse')) return 1;
    if (location.startsWith('/payments')) return 2;
    if (location.startsWith('/services')) return 3;
    return 0;
  }
}

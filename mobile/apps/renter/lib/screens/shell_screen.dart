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
///
/// Every location the MOBILE_FINANCE flag covers in the renter app.
///
/// Only `/payments`: its screen calls `/v1/payments/lease/{id}`, which
/// accounting v2 removed (pre-flight 6.1). `/penalties` is NOT here — it is
/// served by PenaltyAssessmentController, which v2 kept, so hiding it would be
/// a product choice rather than a breakage fix (ruling P5-R8).
bool isRenterFinanceLocation(String location) =>
    location.startsWith('/payments');

/// The bottom-nav destinations. The Wallet tab reads the renter's cheque
/// schedule, which accounting v2 replaces, so it is hidden until the app is
/// rewritten (spec D7). Dropping the entry rather than disabling it keeps the
/// remaining tabs' indices contiguous.
List<String> renterShellRoutes(bool financeEnabled) => financeEnabled
    ? const ['/', '/browse', '/payments', '/services']
    : const ['/', '/browse', '/services'];

/// The nav slot for a location, or null when no tab owns it — which is the
/// point for a gated location: the bar must not light up an item it no longer
/// shows. Locations that were never tabs (`/penalties`, `/gatepass`, …) have
/// always reported nothing here and still do.
int? renterShellIndexForLocation(
  String location, {
  required bool financeEnabled,
}) {
  if (!financeEnabled && isRenterFinanceLocation(location)) return null;
  final routes = renterShellRoutes(financeEnabled);
  for (var i = routes.length - 1; i >= 0; i--) {
    // Reverse order so '/' (a prefix of everything) is only matched last.
    if (routes[i] != '/' && location.startsWith(routes[i])) return i;
  }
  return location == '/' ? 0 : null;
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
    final financeEnabled = ref.watch(mobileFinanceEnabledProvider);
    final routes = renterShellRoutes(financeEnabled);
    // A location no tab owns (a pushed screen, or a gated one) keeps Home lit,
    // which is what this shell has always done.
    final selectedIndex =
        renterShellIndexForLocation(location, financeEnabled: financeEnabled) ??
        0;
    final isAr = context.isAr;

    // Labels and icons come off the same list as the tap targets, so the
    // missing Wallet tab cannot shift Services out from under the router.
    const labels = {
      '/': ('الرئيسية', 'Home', Icons.home_rounded),
      '/browse': ('استكشاف', 'Explore', Icons.travel_explore_rounded),
      '/payments': (
        'المحفظة',
        'Wallet',
        Icons.account_balance_wallet_rounded,
      ),
      '/services': ('الخدمات', 'Services', Icons.grid_view_rounded),
    };

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
          onTap: (index) => context.go(
            index >= 0 && index < routes.length ? routes[index] : '/',
          ),
          // Gate passes are not finance, so the raised Pass action is
          // unaffected by the flag.
          centreIcon: Icons.qr_code_scanner_rounded,
          centreLabel: isAr ? 'تصريح' : 'Pass',
          onCentreTap: () => context.go('/gatepass'),
          items: [
            for (final route in routes)
              MiftahNavItem(
                icon: labels[route]!.$3,
                label: isAr ? labels[route]!.$1 : labels[route]!.$2,
              ),
          ],
        ),
      ),
    );
  }
}

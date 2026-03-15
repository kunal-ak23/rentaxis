import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class ShellScreen extends ConsumerStatefulWidget {
  final Widget child;

  const ShellScreen({super.key, required this.child});

  @override
  ConsumerState<ShellScreen> createState() => _ShellScreenState();
}

class _ShellScreenState extends ConsumerState<ShellScreen> {
  @override
  void initState() {
    super.initState();
    // Start notification polling
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(notificationProvider.notifier).startPolling();
    });
  }

  @override
  void dispose() {
    // Note: polling is stopped by the notifier's dispose
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).matchedLocation;
    final notifState = ref.watch(notificationProvider);

    return Scaffold(
      body: widget.child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: _calculateIndex(location),
        onDestinationSelected: (index) {
          switch (index) {
            case 0:
              context.go('/');
            case 1:
              context.go('/payments');
            case 2:
              context.go('/tickets');
            case 3:
              context.go('/profile');
          }
        },
        backgroundColor: AppColors.surface,
        indicatorColor: AppColors.primary.withValues(alpha: 0.1),
        elevation: 8,
        shadowColor: Colors.black26,
        surfaceTintColor: Colors.transparent,
        destinations: [
          const NavigationDestination(
            icon: Icon(Icons.home_outlined),
            selectedIcon: Icon(Icons.home, color: AppColors.primary),
            label: 'Home',
          ),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: false,
              child: const Icon(Icons.payment_outlined),
            ),
            selectedIcon:
                const Icon(Icons.payment, color: AppColors.primary),
            label: 'Payments',
          ),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: notifState.unreadCount > 0,
              label: Text(
                '${notifState.unreadCount}',
                style: const TextStyle(fontSize: 9),
              ),
              child: const Icon(Icons.build_outlined),
            ),
            selectedIcon:
                const Icon(Icons.build, color: AppColors.primary),
            label: 'Tickets',
          ),
          const NavigationDestination(
            icon: Icon(Icons.person_outline),
            selectedIcon: Icon(Icons.person, color: AppColors.primary),
            label: 'More',
          ),
        ],
      ),
    );
  }

  int _calculateIndex(String location) {
    if (location.startsWith('/payments')) return 1;
    if (location.startsWith('/tickets')) return 2;
    if (location.startsWith('/profile')) return 3;
    return 0;
  }
}

import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
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
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(notificationProvider.notifier).startPolling();
    });
  }

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).matchedLocation;
    final notifState = ref.watch(notificationProvider);
    final selectedIndex = _calculateIndex(location);

    return Scaffold(
      extendBody: true, // Content extends behind the floating nav
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        elevation: 0,
        scrolledUnderElevation: 0.5,
        title: Image.asset(
          'assets/logo_horizontal.png',
          height: 32,
          fit: BoxFit.contain,
        ),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: IconButton(
              onPressed: () => context.push('/notifications'),
              icon: Badge(
                isLabelVisible: notifState.unreadCount > 0,
                label: Text(
                  notifState.unreadCount > 9
                      ? '9+'
                      : '${notifState.unreadCount}',
                  style: const TextStyle(fontSize: 9, color: Colors.white),
                ),
                backgroundColor: AppColors.danger,
                child: const Icon(Icons.notifications_outlined,
                    color: AppColors.navyDark),
              ),
            ),
          ),
        ],
      ),
      body: widget.child,
      bottomNavigationBar: _FrostedBottomNav(
        selectedIndex: selectedIndex,
        onTap: (index) {
          switch (index) {
            case 0:
              context.go('/');
            case 1:
              context.go('/browse');
            case 2:
              context.go('/payments');
            case 3:
              context.go('/tickets');
            case 4:
              context.go('/profile');
          }
        },
      ),
    );
  }

  int _calculateIndex(String location) {
    if (location.startsWith('/browse')) return 1;
    if (location.startsWith('/wishlist')) return 1;
    if (location.startsWith('/payments')) return 2;
    if (location.startsWith('/tickets')) return 3;
    if (location.startsWith('/profile')) return 4;
    return 0;
  }
}

class _FrostedBottomNav extends StatelessWidget {
  final int selectedIndex;
  final ValueChanged<int> onTap;

  const _FrostedBottomNav({
    required this.selectedIndex,
    required this.onTap,
  });

  static const _items = [
    (icon: Icons.home_outlined, activeIcon: Icons.home_rounded, label: 'Home'),
    (icon: Icons.search_outlined, activeIcon: Icons.search_rounded, label: 'Browse'),
    (icon: Icons.payment_outlined, activeIcon: Icons.payment_rounded, label: 'Payments'),
    (icon: Icons.handyman_outlined, activeIcon: Icons.handyman_rounded, label: 'Tickets'),
    (icon: Icons.person_outline_rounded, activeIcon: Icons.person_rounded, label: 'More'),
  ];

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 6),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(28),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 30, sigmaY: 30),
            child: Container(
              height: 72,
              decoration: BoxDecoration(
                color: AppColors.background.withValues(alpha: 0.82),
                borderRadius: BorderRadius.circular(28),
                border: Border.all(
                  color: AppColors.border.withValues(alpha: 0.6),
                  width: 1.2,
                ),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.12),
                    blurRadius: 24,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: List.generate(_items.length, (index) {
                  final item = _items[index];
                  final isSelected = index == selectedIndex;
                  return _NavItemWidget(
                    icon: item.icon,
                    activeIcon: item.activeIcon,
                    label: item.label,
                    isSelected: isSelected,
                    onTap: () => onTap(index),
                  );
                }),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _NavItemWidget extends StatelessWidget {
  final IconData icon;
  final IconData activeIcon;
  final String label;
  final bool isSelected;
  final VoidCallback onTap;

  const _NavItemWidget({
    required this.icon,
    required this.activeIcon,
    required this.label,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      behavior: HitTestBehavior.opaque,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 250),
        curve: Curves.easeOutCubic,
        padding: EdgeInsets.symmetric(
          horizontal: isSelected ? 18 : 14,
          vertical: 8,
        ),
        decoration: BoxDecoration(
          color: isSelected
              ? AppColors.primary.withValues(alpha: 0.12)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AnimatedSwitcher(
              duration: const Duration(milliseconds: 200),
              child: Icon(
                isSelected ? activeIcon : icon,
                key: ValueKey(isSelected),
                size: 24,
                color: isSelected ? AppColors.primary : AppColors.textMuted,
              ),
            ),
            const SizedBox(height: 3),
            Text(
              label,
              style: GoogleFonts.josefinSans(
                fontSize: 11,
                fontWeight: isSelected ? FontWeight.w700 : FontWeight.w400,
                color: isSelected ? AppColors.primary : AppColors.textMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

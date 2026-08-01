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
  void dispose() {
    ref.read(notificationProvider.notifier).stopPolling();
    super.dispose();
  }

  int _calculateIndex(String location) {
    if (location.startsWith('/properties')) return 1;
    if (location.startsWith('/tickets')) return 2;
    if (location.startsWith('/meetings')) return 3;
    if (location.startsWith('/payments')) return 4;
    return 0;
  }

  @override
  Widget build(BuildContext context) {
    final location = GoRouterState.of(context).matchedLocation;
    final currentIndex = _calculateIndex(location);
    final notifState = ref.watch(notificationProvider);

    // System back from a non-home tab returns to Dashboard instead of
    // exiting the app; back on Dashboard exits as usual.
    return PopScope(
      canPop: currentIndex == 0,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) context.go('/');
      },
      child: Scaffold(
        extendBody: true,
        appBar: AppBar(
          backgroundColor: AppColors.navyDark,
          elevation: 0,
          scrolledUnderElevation: 0.5,
          shape: Border(
            bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
          ),
          // Arabic wordmark in عربي, English wordmark in EN — one script each.
          title: ref.watch(appLanguageProvider) == AppLanguage.ar
              ? Image.asset(
                  'assets/logo_mark.png',
                  height: 52,
                  fit: BoxFit.contain,
                )
              : Image.asset(
                  'assets/logo_horizontal.png',
                  height: 28,
                  fit: BoxFit.contain,
                ),
          actions: [
            IconButton(
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
                child: const Icon(
                  Icons.notifications_outlined,
                  color: AppColors.accent,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsetsDirectional.only(end: 8),
              child: IconButton(
                onPressed: () => context.push('/more'),
                icon: const Icon(
                  Icons.person_outline_rounded,
                  color: AppColors.accent,
                ),
              ),
            ),
          ],
        ),
        body: widget.child,
        bottomNavigationBar: _FrostedBottomNav(
          selectedIndex: currentIndex,
          onTap: (index) {
            switch (index) {
              case 0:
                context.go('/');
              case 1:
                context.go('/properties');
              case 2:
                context.go('/tickets');
              case 3:
                context.go('/meetings');
              case 4:
                context.go('/payments');
            }
          },
        ),
      ),
    );
  }
}

class _FrostedBottomNav extends StatelessWidget {
  final int selectedIndex;
  final ValueChanged<int> onTap;

  const _FrostedBottomNav({required this.selectedIndex, required this.onTap});

  static const _icons = [
    (icon: Icons.dashboard_outlined, activeIcon: Icons.dashboard_rounded),
    (icon: Icons.apartment_outlined, activeIcon: Icons.apartment_rounded),
    (
      icon: Icons.confirmation_number_outlined,
      activeIcon: Icons.confirmation_number_rounded,
    ),
    (icon: Icons.event_outlined, activeIcon: Icons.event_rounded),
    (icon: Icons.payment_outlined, activeIcon: Icons.payment_rounded),
  ];

  static const _labelsEn = [
    'Dashboard',
    'Properties',
    'Tickets',
    'Meetings',
    'Payments',
  ];
  static const _labelsAr = [
    'لوحة التحكم',
    'العقارات',
    'الطلبات',
    'المواعيد',
    'المدفوعات',
  ];

  @override
  Widget build(BuildContext context) {
    final labels = context.isAr ? _labelsAr : _labelsEn;
    // Scrim behind the floating pill: content scrolling beneath it fades into
    // the page background instead of hard-clipping in the gap below the pill.
    final scrimColor = Theme.of(context).scaffoldBackgroundColor;
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            scrimColor.withValues(alpha: 0),
            scrimColor.withValues(alpha: 0.85),
            scrimColor,
          ],
          stops: const [0, 0.45, 1],
        ),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 6, 14, 8),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(20),
            child: BackdropFilter(
              filter: ImageFilter.blur(sigmaX: 14, sigmaY: 14),
              child: Container(
                height: 68,
                decoration: BoxDecoration(
                  color: AppColors.navyDark.withValues(alpha: 0.9),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(
                    color: AppColors.accent.withValues(alpha: 0.18),
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.22),
                      blurRadius: 24,
                      offset: const Offset(0, 8),
                    ),
                  ],
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: List.generate(_icons.length, (index) {
                    final item = _icons[index];
                    final isSelected = index == selectedIndex;
                    return Flexible(
                      child: _NavItemWidget(
                        icon: item.icon,
                        activeIcon: item.activeIcon,
                        label: labels[index],
                        isSelected: isSelected,
                        onTap: () => onTap(index),
                      ),
                    );
                  }),
                ),
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
          horizontal: isSelected ? 12 : 8,
          vertical: 8,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            AnimatedSwitcher(
              duration: const Duration(milliseconds: 200),
              child: Icon(
                isSelected ? activeIcon : icon,
                key: ValueKey(isSelected),
                size: 21,
                color: isSelected
                    ? AppColors.accent
                    : Colors.white.withValues(alpha: 0.5),
              ),
            ),
            const SizedBox(height: 3),
            FittedBox(
              fit: BoxFit.scaleDown,
              child: Text(
                label,
                maxLines: 1,
                // Naskh + no tracking for Arabic (joining); Josefin for EN.
                style: context.isAr
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 11,
                        fontWeight: isSelected
                            ? FontWeight.w600
                            : FontWeight.w400,
                        color: isSelected
                            ? AppColors.accent
                            : Colors.white.withValues(alpha: 0.5),
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 10.5,
                        fontWeight: isSelected
                            ? FontWeight.w600
                            : FontWeight.w400,
                        letterSpacing: 0.4,
                        color: isSelected
                            ? AppColors.accent
                            : Colors.white.withValues(alpha: 0.5),
                      ),
              ),
            ),
            const SizedBox(height: 3),
            AnimatedContainer(
              duration: const Duration(milliseconds: 250),
              width: 4,
              height: 4,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: isSelected ? AppColors.accent : Colors.transparent,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

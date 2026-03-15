import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class MoreScreen extends ConsumerWidget {
  const MoreScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('More')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // User profile card
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: AppColors.navyDark,
                borderRadius: BorderRadius.circular(16),
              ),
              child: Row(
                children: [
                  CircleAvatar(
                    radius: 24,
                    backgroundColor: AppColors.accent.withValues(alpha: 0.2),
                    child: Text(
                      (authState.name ?? 'U')[0].toUpperCase(),
                      style: const TextStyle(
                        color: AppColors.accent,
                        fontWeight: FontWeight.w700,
                        fontSize: 18,
                      ),
                    ),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          authState.name ?? 'User',
                          style: const TextStyle(
                            color: Colors.white,
                            fontWeight: FontWeight.w600,
                            fontSize: 16,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          authState.email ?? '',
                          style: TextStyle(
                            color: Colors.white.withValues(alpha: 0.6),
                            fontSize: 13,
                          ),
                        ),
                        if (authState.role != null) ...[
                          const SizedBox(height: 6),
                          StatusBadge(
                            label: authState.role!.replaceAll('_', ' '),
                            color: AppColors.accent,
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),

            // Menu sections
            _SectionHeader(title: 'Management'),
            _MenuItem(
              icon: Icons.people_outline,
              label: 'Renters',
              onTap: () => context.push('/renters'),
            ),
            _MenuItem(
              icon: Icons.confirmation_number_outlined,
              label: 'Tickets',
              onTap: () => context.push('/tickets'),
            ),
            _MenuItem(
              icon: Icons.account_balance_outlined,
              label: 'Finance',
              onTap: () => context.push('/finance'),
            ),
            const SizedBox(height: 16),

            _SectionHeader(title: 'Account'),
            _MenuItem(
              icon: Icons.person_outline,
              label: 'Profile',
              onTap: () => context.push('/profile'),
            ),
            _MenuItem(
              icon: Icons.lock_outline,
              label: 'Change Password',
              onTap: () => context.push('/profile'),
            ),
            const SizedBox(height: 16),

            _SectionHeader(title: 'Settings'),
            _MenuItem(
              icon: Icons.language,
              label: 'Language',
              trailing: const Text('English',
                  style:
                      TextStyle(fontSize: 13, color: AppColors.textMuted)),
              onTap: () {
                // Language switching - placeholder
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                      content: Text('Language switching coming soon')),
                );
              },
            ),
            _MenuItem(
              icon: Icons.info_outline,
              label: 'About',
              onTap: () {
                showAboutDialog(
                  context: context,
                  applicationName: 'RentAxis Manager',
                  applicationVersion: '1.0.0',
                  applicationLegalese:
                      'Property Management Suite for UAE Landlords',
                );
              },
            ),
            const SizedBox(height: 16),

            // Logout
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: () async {
                  final confirmed = await showDialog<bool>(
                    context: context,
                    builder: (ctx) => AlertDialog(
                      title: const Text('Logout'),
                      content: const Text(
                          'Are you sure you want to log out?'),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.pop(ctx, false),
                          child: const Text('Cancel'),
                        ),
                        ElevatedButton(
                          onPressed: () => Navigator.pop(ctx, true),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: AppColors.danger,
                          ),
                          child: const Text('Logout'),
                        ),
                      ],
                    ),
                  );
                  if (confirmed == true) {
                    ref.read(notificationProvider.notifier).stopPolling();
                    await ref.read(authProvider.notifier).logout();
                    if (context.mounted) context.go('/login');
                  }
                },
                icon: const Icon(Icons.logout, color: AppColors.danger),
                label: const Text('Logout'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.danger,
                  side: const BorderSide(color: AppColors.danger),
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
              ),
            ),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  final String title;
  const _SectionHeader({required this.title});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: AppColors.textMuted,
          letterSpacing: 0.5,
        ),
      ),
    );
  }
}

class _MenuItem extends StatelessWidget {
  final IconData icon;
  final String label;
  final Widget? trailing;
  final VoidCallback onTap;

  const _MenuItem({
    required this.icon,
    required this.label,
    this.trailing,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
        decoration: BoxDecoration(
          border: Border(
            bottom: BorderSide(color: AppColors.border.withValues(alpha: 0.5)),
          ),
        ),
        child: Row(
          children: [
            Icon(icon, size: 22, color: AppColors.textSecondary),
            const SizedBox(width: 14),
            Expanded(
              child: Text(
                label,
                style: const TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
            if (trailing != null) trailing!,
            if (trailing == null)
              const Icon(Icons.chevron_right,
                  size: 20, color: AppColors.textMuted),
          ],
        ),
      ),
    );
  }
}

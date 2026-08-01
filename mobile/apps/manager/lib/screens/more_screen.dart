import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// More / settings hub, per admin design 1i: dark chrome profile header,
/// grouped navigation cards, appearance + language preferences, sign out.
class MoreScreen extends ConsumerWidget {
  const MoreScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState = ref.watch(authProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: ListView(
        padding: EdgeInsets.only(bottom: AppInsets.bottomNav(context)),
        children: [
          _ChromeHeader(authState: authState, l: l),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _sectionLabel(l.marketplace, l.ar, m),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.apartment_outlined,
                      label: l.listings,
                      onTap: () => context.push('/listings'),
                    ),
                  ],
                ),
                _sectionLabel(l.people, l.ar, m),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.badge_outlined,
                      label: l.staff,
                      onTap: () => context.push('/staff'),
                    ),
                    _MenuRow(
                      icon: Icons.people_outline,
                      label: l.renters,
                      onTap: () => context.push('/renters'),
                    ),
                  ],
                ),
                _sectionLabel(l.gate, l.ar, m),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.how_to_reg_outlined,
                      label: l.gatePassApprovals,
                      onTap: () => context.push('/gate-passes/approvals'),
                    ),
                    _MenuRow(
                      icon: Icons.shield_outlined,
                      label: l.securityGuards,
                      onTap: () => context.push('/gate-passes/guards'),
                    ),
                    _MenuRow(
                      icon: Icons.tune,
                      label: l.gateAccessPolicy,
                      onTap: () => context.push('/gate-passes/policy'),
                    ),
                    _MenuRow(
                      icon: Icons.handyman_outlined,
                      label: l.registerUnitVendor,
                      onTap: () => context.push('/gate-passes/vendors'),
                    ),
                  ],
                ),
                _sectionLabel(l.finance, l.ar, m),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.account_balance_outlined,
                      label: l.accountsTransactions,
                      onTap: () => context.push('/finance'),
                    ),
                    _MenuRow(
                      icon: Icons.account_balance_wallet_outlined,
                      label: l.bankAccounts,
                      onTap: () => context.push('/bank-accounts'),
                    ),
                    _MenuRow(
                      icon: Icons.store_outlined,
                      label: l.vendors,
                      onTap: () => context.push('/vendors'),
                    ),
                    _MenuRow(
                      icon: Icons.assessment_outlined,
                      label: l.reports,
                      onTap: () => context.push('/finance-reports'),
                    ),
                  ],
                ),
                _sectionLabel(l.operations, l.ar, m),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.confirmation_number_outlined,
                      label: l.tickets,
                      onTap: () => context.push('/tickets'),
                    ),
                  ],
                ),
                _sectionLabel(l.preferences, l.ar, m),
                _AppearanceCard(l: l),
                const SizedBox(height: 10),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.language,
                      label: l.language,
                      trailing: const _LanguageToggle(),
                    ),
                    _MenuRow(
                      icon: Icons.settings_outlined,
                      label: l.appSettings,
                      onTap: () => context.push('/settings'),
                    ),
                  ],
                ),
                _sectionLabel(l.account, l.ar, m),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.person_outline,
                      label: l.profile,
                      onTap: () => context.push('/profile'),
                    ),
                    _MenuRow(
                      icon: Icons.info_outline,
                      label: l.about,
                      onTap: () {
                        showAboutDialog(
                          context: context,
                          applicationName: 'Miftah Admin',
                          applicationVersion: '1.0.0',
                          applicationLegalese: l.aboutLegalese,
                        );
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 20),
                _SignOutButton(l: l),
                const SizedBox(height: 18),
                Center(
                  child: Text(
                    l.versionFooter,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 11.5,
                            color: m.textMuted,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 10.5,
                            letterSpacing: 2.0,
                            color: m.textMuted,
                          ),
                  ),
                ),
                const SizedBox(height: 16),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _sectionLabel(String text, bool ar, MiftahColors m) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 4, top: 18, bottom: 9),
      child: Text(
        ar ? text : text.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 12,
                color: AppColors.accentDark,
              )
            : GoogleFonts.josefinSans(
                fontSize: 10,
                letterSpacing: 2.6,
                color: AppColors.accentDark,
              ),
      ),
    );
  }
}

class _ChromeHeader extends StatelessWidget {
  final AuthState authState;
  final _L l;
  const _ChromeHeader({required this.authState, required this.l});

  String get _initials {
    final name = (authState.name ?? '').trim();
    if (name.isEmpty) return 'M';
    final parts = name.split(RegExp(r'\s+'));
    if (parts.length == 1) return parts.first[0].toUpperCase();
    return (parts[0][0] + parts[1][0]).toUpperCase();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
      child: Row(
        children: [
          Container(
            width: 48,
            height: 48,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              border: Border.all(
                color: AppColors.accent.withValues(alpha: 0.35),
              ),
            ),
            alignment: Alignment.center,
            child: Text(
              _initials,
              style: GoogleFonts.cinzel(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                color: AppColors.accent,
              ),
            ),
          ),
          const SizedBox(width: 13),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  authState.name ?? l.managerFallback,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                          color: AppColors.gold400,
                        )
                      : GoogleFonts.cinzel(
                          fontSize: 18,
                          color: AppColors.gold400,
                        ),
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Text(
                  l.roleLine(authState.role, authState.email),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 12,
                          color: AppColors.goldMid,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 11,
                          letterSpacing: 1.6,
                          color: AppColors.goldMid,
                        ),
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _AppearanceCard extends ConsumerWidget {
  final _L l;
  const _AppearanceCard({required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final mode = ref.watch(themeModeProvider);

    Widget option(String label, ThemeMode value) {
      final selected = mode == value;
      return Expanded(
        child: GestureDetector(
          onTap: () => ref.read(themeModeProvider.notifier).setMode(value),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            height: 34,
            margin: const EdgeInsets.symmetric(horizontal: 3),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(999),
              gradient: selected ? MiftahGradients.gold : null,
              border: selected ? null : Border.all(color: m.borderStrong),
            ),
            alignment: Alignment.center,
            child: Text(
              l.ar ? label : label.toUpperCase(),
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 12,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                      color: selected ? AppColors.primary : m.textSecondary,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 10,
                      letterSpacing: 1.6,
                      fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                      color: selected ? AppColors.primary : m.textSecondary,
                    ),
            ),
          ),
        ),
      );
    }

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.ar ? l.appearance : l.appearance.toUpperCase(),
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textMuted)
                : GoogleFonts.josefinSans(
                    fontSize: 9.5,
                    letterSpacing: 2.2,
                    color: m.textMuted,
                  ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              option(l.light, ThemeMode.light),
              option(l.dark, ThemeMode.dark),
              option(l.system, ThemeMode.system),
            ],
          ),
        ],
      ),
    );
  }
}

class _LanguageToggle extends ConsumerWidget {
  const _LanguageToggle();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final language = ref.watch(appLanguageProvider);

    Widget option({
      required bool selected,
      required VoidCallback onTap,
      required Widget child,
    }) {
      return GestureDetector(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(999),
            color: selected ? AppColors.accent : Colors.transparent,
          ),
          child: child,
        ),
      );
    }

    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.35)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          option(
            selected: language == AppLanguage.en,
            onTap: () => ref
                .read(appLanguageProvider.notifier)
                .setLanguage(AppLanguage.en),
            child: Text(
              'EN',
              style: GoogleFonts.josefinSans(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.4,
                color: language == AppLanguage.en
                    ? AppColors.primary
                    : m.textMuted,
              ),
            ),
          ),
          option(
            selected: language == AppLanguage.ar,
            onTap: () => ref
                .read(appLanguageProvider.notifier)
                .setLanguage(AppLanguage.ar),
            child: Text(
              'عربي',
              style: GoogleFonts.notoNaskhArabic(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: language == AppLanguage.ar
                    ? AppColors.primary
                    : m.textMuted,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SignOutButton extends ConsumerWidget {
  final _L l;
  const _SignOutButton({required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    return SizedBox(
      width: double.infinity,
      child: OutlinedButton(
        onPressed: () async {
          final confirmed = await showDialog<bool>(
            context: context,
            builder: (ctx) => AlertDialog(
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
              ),
              title: Text(
                l.signOut,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(fontWeight: FontWeight.w600)
                    : GoogleFonts.cinzel(
                        fontWeight: FontWeight.w600,
                        fontSize: 18,
                      ),
              ),
              content: Text(
                l.signOutConfirm,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic()
                    : GoogleFonts.josefinSans(),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: Text(
                    l.cancel,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontWeight: FontWeight.w600,
                          )
                        : GoogleFonts.josefinSans(fontWeight: FontWeight.w600),
                  ),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(ctx, true),
                  style: TextButton.styleFrom(
                    foregroundColor: AppColors.danger,
                  ),
                  child: Text(
                    l.signOut,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontWeight: FontWeight.w600,
                          )
                        : GoogleFonts.josefinSans(fontWeight: FontWeight.w600),
                  ),
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
        style: OutlinedButton.styleFrom(
          foregroundColor: m.danger,
          side: BorderSide(color: m.danger.withValues(alpha: 0.5)),
          padding: const EdgeInsets.symmetric(vertical: 13),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
        child: Text(
          l.ar ? l.signOut : l.signOut.toUpperCase(),
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                )
              : GoogleFonts.josefinSans(
                  fontSize: 11.5,
                  fontWeight: FontWeight.w600,
                  letterSpacing: 2.2,
                ),
        ),
      ),
    );
  }
}

class _MenuCard extends StatelessWidget {
  final List<_MenuRow> items;
  const _MenuCard({required this.items});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          for (var i = 0; i < items.length; i++) ...[
            if (i > 0) Divider(height: 1, color: m.divider),
            items[i],
          ],
        ],
      ),
    );
  }
}

class _MenuRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _MenuRow({
    required this.icon,
    required this.label,
    this.trailing,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        child: Row(
          children: [
            Icon(icon, size: 21, color: AppColors.accentDark),
            const SizedBox(width: 13),
            Expanded(
              child: Text(
                label,
                style: context.isAr
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 14.5,
                        color: m.textPrimary,
                      )
                    : GoogleFonts.josefinSans(
                        fontSize: 14,
                        color: m.textPrimary,
                      ),
              ),
            ),
            if (trailing != null)
              trailing!
            else
              Icon(Icons.chevron_right, size: 18, color: m.textMuted),
          ],
        ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get marketplace => ar ? 'السوق' : 'Marketplace';
  String get listings => ar ? 'الإعلانات العقارية' : 'Listings';
  String get people => ar ? 'الأشخاص' : 'People';
  String get staff => ar ? 'الموظفون' : 'Staff';
  String get renters => ar ? 'المستأجرون' : 'Renters';
  String get gate => ar ? 'البوابة' : 'Gate';
  String get gatePassApprovals =>
      ar ? 'موافقات تصاريح الدخول' : 'Gate Pass Approvals';
  String get securityGuards => ar ? 'حراس الأمن' : 'Security Guards';
  String get gateAccessPolicy =>
      ar ? 'سياسة الدخول للبوابة' : 'Gate Access Policy';
  String get registerUnitVendor =>
      ar ? 'تسجيل مورّد للوحدة' : 'Register Unit Vendor';
  String get finance => ar ? 'المالية' : 'Finance';
  String get accountsTransactions =>
      ar ? 'الحسابات والمعاملات' : 'Accounts & Transactions';
  String get bankAccounts => ar ? 'الحسابات البنكية' : 'Bank Accounts';
  String get vendors => ar ? 'المورّدون' : 'Vendors';
  String get reports => ar ? 'التقارير' : 'Reports';
  String get operations => ar ? 'العمليات' : 'Operations';
  String get tickets => ar ? 'طلبات الصيانة' : 'Tickets';
  String get preferences => ar ? 'التفضيلات' : 'Preferences';
  String get appearance => ar ? 'المظهر' : 'Appearance';
  String get light => ar ? 'فاتح' : 'Light';
  String get dark => ar ? 'داكن' : 'Dark';
  String get system => ar ? 'تلقائي' : 'System';
  String get language => ar ? 'اللغة' : 'Language';
  String get appSettings => ar ? 'إعدادات التطبيق' : 'App Settings';
  String get account => ar ? 'الحساب' : 'Account';
  String get profile => ar ? 'الملف الشخصي' : 'Profile';
  String get about => ar ? 'حول التطبيق' : 'About';
  String get aboutLegalese => ar
      ? 'منظومة إدارة العقارات لملّاك الإمارات'
      : 'Property Management Suite for UAE Landlords';
  String get signOut => ar ? 'تسجيل الخروج' : 'Sign out';
  String get signOutConfirm => ar
      ? 'هل أنت متأكد من تسجيل الخروج؟'
      : 'Are you sure you want to sign out?';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get managerFallback => ar ? 'مدير العقارات' : 'Manager';
  String get versionFooter =>
      ar ? 'مفتاح للإدارة · 1.0.0' : 'MIFTAH ADMIN · V1.0.0';

  String roleLine(String? role, String? email) {
    final r = role?.replaceAll('_', ' ');
    if (ar) {
      final roleAr = switch (role) {
        'TENANT_ADMIN' => 'مدير الحساب',
        'PROPERTY_MANAGER' => 'مدير العقارات',
        'SUPER_ADMIN' => 'مشرف عام',
        _ => r ?? '',
      };
      return roleAr.isEmpty ? (email ?? '') : roleAr;
    }
    return (r ?? email ?? '').toUpperCase();
  }
}

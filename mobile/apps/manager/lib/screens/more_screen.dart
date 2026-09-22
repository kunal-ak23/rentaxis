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
    // Staff, vendor and finance endpoints are TENANT_ADMIN/SUPER_ADMIN-only
    // (class-level @PreAuthorize on StaffController, VendorController,
    // AccountController & co). Hide those entries for PROPERTY_MANAGER users
    // instead of dead-ending them on a 403 — mirrors the web sidebar's
    // canAccessFinance gating in rbac.ts.
    final isAdmin =
        authState.role == 'TENANT_ADMIN' || authState.role == 'SUPER_ADMIN';
    // MOBILE_FINANCE: the ledger/report screens are hidden until the app is
    // rewritten for accounting v2 (spec D7).
    final financeEnabled = ref.watch(mobileFinanceEnabledProvider);
    // Real version from the bundle: the About dialog said 1.0.0 while the app
    // shipped 1.2.0, the kind of mismatch App Review flags.
    final version = ref
        .watch(installedVersionLabelProvider)
        .maybeWhen(data: (v) => v, orElse: () => '');

    return Scaffold(
      backgroundColor: m.background,
      body: ListView(
        padding: EdgeInsets.only(bottom: 24),
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
                    if (isAdmin)
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
                _sectionLabel(l.community, l.ar, m),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.pool_outlined,
                      label: l.facilities,
                      onTap: () => context.push('/facilities'),
                    ),
                    _MenuRow(
                      icon: Icons.event_available_outlined,
                      label: l.bookingRequests,
                      onTap: () => context.push('/bookings'),
                    ),
                  ],
                ),
                if (isAdmin) ...[
                  _sectionLabel(l.finance, l.ar, m),
                  _MenuCard(
                    items: [
                      // Accounts, bank accounts and reports ride on endpoints
                      // accounting v2 removed. Rather than vanish, the group
                      // collapses to one disabled "coming soon" row, so the
                      // capability reads as pending instead of missing.
                      if (financeEnabled) ...[
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
                      ] else
                        _MenuRow(
                          icon: Icons.account_balance_outlined,
                          label: l.accountsTransactions,
                          sub: l.comingSoon,
                        ),
                      // Vendors survives: /v1/vendors and the vendor ledger
                      // are both still served (pre-flight 6.2).
                      _MenuRow(
                        icon: Icons.store_outlined,
                        label: l.vendors,
                        onTap: () => context.push('/vendors'),
                      ),
                      if (financeEnabled)
                        _MenuRow(
                          icon: Icons.assessment_outlined,
                          label: l.reports,
                          onTap: () => context.push('/finance-reports'),
                        ),
                    ],
                  ),
                ],
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
                      icon: Icons.privacy_tip_outlined,
                      label: l.privacyPolicy,
                      onTap: () => launchLegalUrl(
                        miftahLegalUrl(MiftahLegalPage.privacy, arabic: l.ar),
                      ),
                    ),
                    _MenuRow(
                      icon: Icons.description_outlined,
                      label: l.termsOfUse,
                      onTap: () => launchLegalUrl(
                        miftahLegalUrl(MiftahLegalPage.terms, arabic: l.ar),
                      ),
                    ),
                    _MenuRow(
                      icon: Icons.info_outline,
                      label: l.about,
                      onTap: () {
                        showAboutDialog(
                          context: context,
                          applicationName: 'Miftah Manager',
                          applicationVersion: version,
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
                    l.versionFooter(version),
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 11.5,
                            color: m.textMuted,
                          )
                        : GoogleFonts.plusJakartaSans(
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

  Widget _sectionLabel(String text, bool ar, LegacyMiftahColors m) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 4, top: 18, bottom: 9),
      child: Text(
        ar ? text : text.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 12,
                color: AppColors.accentDark,
              )
            : GoogleFonts.plusJakartaSans(
                fontSize: 10,
                letterSpacing: 2.6,
                color: AppColors.accentDark,
              ),
      ),
    );
  }
}

/// Account header — design screen 15. Centred gradient avatar over ink, name,
/// then role and portfolio size. One of the five ink headers the redesign
/// keeps.
class _ChromeHeader extends StatelessWidget {
  const _ChromeHeader({required this.authState, required this.l});

  final AuthState authState;
  final _L l;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: MiftahColors.ink,
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 26),
      child: Column(
        children: [
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: MiftahCircleButton(
              icon: Icons.arrow_back_rounded,
              tooltip: l.back,
              onDark: true,
              onTap: () => Navigator.of(context).maybePop(),
            ),
          ),
          const SizedBox(height: 12),
          MiftahAvatarButton(
            name: authState.name,
            size: 80,
            gradient: true,
            onTap: () => context.push('/profile'),
          ),
          const SizedBox(height: 14),
          Text(
            authState.name ?? l.managerFallback,
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: l.ar
                ? MiftahType.ar(
                    size: 22,
                    weight: FontWeight.w700,
                    color: Colors.white,
                  )
                : MiftahType.amount(size: 24, color: Colors.white),
          ),
          const SizedBox(height: 5),
          Text(
            l.roleLine(authState.role, authState.email),
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: l.ar
                ? MiftahType.ar(size: 12.5, color: const Color(0xFF8C86A0))
                : MiftahType.body(size: 12.5, color: const Color(0xFF8C86A0)),
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
              gradient: selected ? LegacyMiftahGradients.gold : null,
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
                  : GoogleFonts.plusJakartaSans(
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
                : GoogleFonts.plusJakartaSans(
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
              style: GoogleFonts.plusJakartaSans(
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
                    : GoogleFonts.plusJakartaSans(
                        fontWeight: FontWeight.w600,
                        fontSize: 18,
                      ),
              ),
              content: Text(
                l.signOutConfirm,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic()
                    : GoogleFonts.plusJakartaSans(),
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
                        : GoogleFonts.plusJakartaSans(
                            fontWeight: FontWeight.w600,
                          ),
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
                        : GoogleFonts.plusJakartaSans(
                            fontWeight: FontWeight.w600,
                          ),
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
              : GoogleFonts.plusJakartaSans(
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

  /// Secondary line under the label. A row with a [sub] and no [onTap] is the
  /// "present but not yet available" state — it stays visible and inert.
  final String? sub;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _MenuRow({
    required this.icon,
    required this.label,
    this.sub,
    this.trailing,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final disabled = onTap == null && trailing == null;
    final labelColor = disabled ? m.textMuted : m.textPrimary;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        child: Row(
          children: [
            Icon(
              icon,
              size: 21,
              color: disabled ? m.textMuted : AppColors.accentDark,
            ),
            const SizedBox(width: 13),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    label,
                    style: context.isAr
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 14.5,
                            color: labelColor,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 14,
                            color: labelColor,
                          ),
                  ),
                  if (sub != null)
                    Text(
                      sub!,
                      style: context.isAr
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 12,
                              color: m.textMuted,
                            )
                          : GoogleFonts.plusJakartaSans(
                              fontSize: 11.5,
                              color: m.textMuted,
                            ),
                    ),
                ],
              ),
            ),
            if (trailing != null)
              trailing!
            else if (!disabled)
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
  String get community => ar ? 'المجتمع' : 'Community';
  String get facilities =>
      ar ? 'المرافق ومواقف السيارات' : 'Amenities & Parking';
  String get bookingRequests => ar ? 'طلبات الحجز' : 'Booking Requests';
  String get finance => ar ? 'المالية' : 'Finance';
  String get accountsTransactions =>
      ar ? 'الحسابات والمعاملات' : 'Accounts & Transactions';
  String get bankAccounts => ar ? 'الحسابات البنكية' : 'Bank Accounts';
  String get vendors => ar ? 'المورّدون' : 'Vendors';
  String get reports => ar ? 'التقارير' : 'Reports';
  String get comingSoon => ar ? 'قريباً' : 'Coming soon';
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
  String versionFooter(String v) =>
      ar ? 'مفتاح للإدارة · $v' : 'MIFTAH ADMIN · V$v';
  String get privacyPolicy => ar ? 'سياسة الخصوصية' : 'Privacy Policy';
  String get termsOfUse => ar ? 'شروط الاستخدام' : 'Terms of Use';

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

  String get back => ar ? 'رجوع' : 'Back';
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class SettingsHubScreen extends ConsumerWidget {
  const SettingsHubScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    // GET /v1/rent-settings/{id} and /v1/gateway-config are
    // SUPER_ADMIN/TENANT_ADMIN only — hide those tiles for other roles
    // instead of offering a guaranteed 403.
    final role = ref.watch(authProvider).role;
    final isAdmin = role == 'SUPER_ADMIN' || role == 'TENANT_ADMIN';
    // MOBILE_FINANCE: /v1/finance/account-mappings was removed by accounting
    // v2, so the Account Mappings screen 404s. The router redirects
    // /settings/mappings, but go_router applies redirect to push too — leaving
    // the tile would PUSH the dashboard on top of Settings, which is worse
    // than the dead link. Hidden AND redirected (ruling P5-R8).
    final financeEnabled = ref.watch(mobileFinanceEnabledProvider);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(l: l),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _MenuCard(
                  items: [
                    if (isAdmin)
                      _MenuRow(
                        icon: Icons.payments_outlined,
                        label: l.rentSettings,
                        subtitle: l.rentSettingsDesc,
                        onTap: () => context.push('/settings/rent'),
                      ),
                    if (isAdmin)
                      _MenuRow(
                        icon: Icons.credit_card_outlined,
                        label: l.gateway,
                        subtitle: l.gatewayDesc,
                        onTap: () => context.push('/settings/gateway'),
                      ),
                    if (isAdmin && financeEnabled)
                      _MenuRow(
                        icon: Icons.account_tree_outlined,
                        label: l.mappings,
                        subtitle: l.mappingsDesc,
                        onTap: () => context.push('/settings/mappings'),
                      ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ChromeHeader extends StatelessWidget {
  final _L l;
  const _ChromeHeader({required this.l});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(8, 4, 20, 18),
      child: SafeArea(
        bottom: false,
        child: Row(
          children: [
            IconButton(
              onPressed: () => context.pop(),
              icon: Icon(
                context.isAr ? Icons.chevron_right : Icons.chevron_left,
                color: AppColors.accent,
                size: 26,
              ),
            ),
            Text(
              l.title,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 18,
                      fontWeight: FontWeight.w600,
                      color: Colors.white,
                    )
                  : GoogleFonts.plusJakartaSans(
                      fontSize: 16,
                      letterSpacing: 2.4,
                      color: Colors.white,
                    ),
            ),
          ],
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
  final String subtitle;
  final VoidCallback onTap;

  const _MenuRow({
    required this.icon,
    required this.label,
    required this.subtitle,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
        child: Row(
          children: [
            Container(
              width: 38,
              height: 38,
              decoration: BoxDecoration(
                color: AppColors.accentDark.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(10),
              ),
              alignment: Alignment.center,
              child: Icon(icon, size: 19, color: AppColors.accentDark),
            ),
            const SizedBox(width: 13),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 14.5,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.plusJakartaSans(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: ar
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
            Icon(
              context.isAr ? Icons.chevron_left : Icons.chevron_right,
              size: 18,
              color: m.textMuted,
            ),
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

  String get title => ar ? 'الإعدادات' : 'Settings';
  String get rentSettings =>
      ar ? 'إعدادات تحصيل الإيجار' : 'Rent Collection Settings';
  String get rentSettingsDesc =>
      ar ? 'فترة السماح، معدلات الغرامة' : 'Grace periods, penalty rates';
  String get gateway => ar ? 'بوابة الدفع' : 'Payment Gateway';
  String get gatewayDesc =>
      ar ? 'إعداد الدفع عبر الإنترنت' : 'Online payment configuration';
  String get mappings => ar ? 'ربط الحسابات' : 'Account Mappings';
  String get mappingsDesc =>
      ar ? 'قواعد ربط شجرة الحسابات' : 'GL account mapping rules';
}

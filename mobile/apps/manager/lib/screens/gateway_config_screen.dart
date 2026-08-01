import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _settingsServiceProvider = Provider<SettingsService>((ref) {
  final client = ref.watch(apiClientProvider);
  return SettingsService(client.dio);
});

final _gatewayConfigProvider =
    FutureProvider.autoDispose<Map<String, dynamic>?>((ref) async {
      final service = ref.watch(_settingsServiceProvider);
      return service.getGatewayConfig();
    });

final _availableGatewaysProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_settingsServiceProvider);
  return service.getAvailableGateways();
});

class GatewayConfigScreen extends ConsumerStatefulWidget {
  const GatewayConfigScreen({super.key});

  @override
  ConsumerState<GatewayConfigScreen> createState() =>
      _GatewayConfigScreenState();
}

class _GatewayConfigScreenState extends ConsumerState<GatewayConfigScreen> {
  Future<void> _refresh() async {
    ref.invalidate(_gatewayConfigProvider);
    ref.invalidate(_availableGatewaysProvider);
  }

  String _maskKey(String key) {
    if (key.length <= 4) return '****';
    return '${'*' * (key.length - 4)}${key.substring(key.length - 4)}';
  }

  @override
  Widget build(BuildContext context) {
    final configAsync = ref.watch(_gatewayConfigProvider);
    final gatewaysAsync = ref.watch(_availableGatewaysProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(l: l),
          Expanded(
            child: configAsync.when(
              loading: () => Center(
                child: CircularProgressIndicator(color: AppColors.accent),
              ),
              error: (e, _) =>
                  ErrorState(message: l.failedToLoadConfig, onRetry: _refresh),
              data: (config) {
                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.accent,
                  child: SingleChildScrollView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (config != null) ...[
                          _StatusCard(config: config, l: l),
                          const SizedBox(height: 12),
                          _ConfigCard(config: config, l: l, maskKey: _maskKey),
                        ] else
                          EmptyState(
                            icon: Icons.credit_card_off_outlined,
                            title: l.noGateway,
                          ),
                        const SizedBox(height: 24),
                        _sectionLabel(l.availableGateways, l.ar, m),
                        const SizedBox(height: 10),
                        gatewaysAsync.when(
                          loading: () => Center(
                            child: Padding(
                              padding: const EdgeInsets.all(16),
                              child: CircularProgressIndicator(
                                color: AppColors.accent,
                              ),
                            ),
                          ),
                          error: (e, _) => Text(
                            l.failedToLoadGateways,
                            style: GoogleFonts.josefinSans(
                              color: m.textMuted,
                              fontSize: 13,
                            ),
                          ),
                          data: (gateways) {
                            if (gateways.isEmpty) {
                              return Text(
                                l.noGatewaysAvailable,
                                style: GoogleFonts.josefinSans(
                                  color: m.textMuted,
                                  fontSize: 13,
                                ),
                              );
                            }
                            return Column(
                              children: gateways.map<Widget>((g) {
                                final name = g is Map
                                    ? (g['name'] ?? g.toString()).toString()
                                    : g.toString();
                                return _GatewayRow(name: name, l: l);
                              }).toList(),
                            );
                          },
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _sectionLabel(String text, bool ar, MiftahColors m) {
    return Text(
      ar ? text : text.toUpperCase(),
      style: ar
          ? GoogleFonts.notoNaskhArabic(fontSize: 13, color: m.textMuted)
          : GoogleFonts.josefinSans(
              fontSize: 11,
              letterSpacing: 2.2,
              color: m.textMuted,
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
                  : GoogleFonts.cinzel(
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

class _StatusCard extends StatelessWidget {
  final Map<String, dynamic> config;
  final _L l;
  const _StatusCard({required this.config, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final isActive =
        config['active'] == true ||
        config['status']?.toString().toUpperCase() == 'ACTIVE';
    final color = isActive ? m.success : m.danger;
    final bg = isActive ? m.successBg : m.dangerBg;

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      padding: const EdgeInsets.all(16),
      child: Row(
        children: [
          Container(
            width: 10,
            height: 10,
            decoration: BoxDecoration(shape: BoxShape.circle, color: color),
          ),
          const SizedBox(width: 12),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: bg,
              borderRadius: BorderRadius.circular(999),
            ),
            child: Text(
              l.ar
                  ? (isActive ? l.active : l.inactive)
                  : (isActive ? l.active : l.inactive).toUpperCase(),
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w600,
                      color: color,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 10.5,
                      letterSpacing: 1.4,
                      fontWeight: FontWeight.w600,
                      color: color,
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ConfigCard extends StatelessWidget {
  final Map<String, dynamic> config;
  final _L l;
  final String Function(String) maskKey;

  const _ConfigCard({
    required this.config,
    required this.l,
    required this.maskKey,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final gatewayName = (config['gatewayName'] ?? config['name'] ?? '-')
        .toString();
    final providerType = (config['providerType'] ?? config['provider'] ?? '-')
        .toString();
    final keyId = (config['keyId'] ?? config['key_id'] ?? '').toString();

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.configuration,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
          const SizedBox(height: 4),
          Divider(color: m.divider, height: 20),
          _ConfigRow(
            icon: Icons.account_balance_wallet_outlined,
            label: l.gatewayName,
            value: gatewayName,
            ar: l.ar,
          ),
          _ConfigRow(
            icon: Icons.integration_instructions_outlined,
            label: l.providerType,
            value: providerType.replaceAll('_', ' '),
            ar: l.ar,
          ),
          if (keyId.isNotEmpty)
            _ConfigRow(
              icon: Icons.key_outlined,
              label: l.keyId,
              value: maskKey(keyId),
              ar: l.ar,
            ),
        ],
      ),
    );
  }
}

class _ConfigRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final bool ar;

  const _ConfigRow({
    required this.icon,
    required this.label,
    required this.value,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Row(
        children: [
          Icon(icon, size: 20, color: AppColors.accentDark),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              label,
              style: ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: m.textSecondary,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 13,
                      color: m.textSecondary,
                    ),
            ),
          ),
          Flexible(
            child: Text(
              value,
              style: ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 14.5,
                      fontWeight: FontWeight.w600,
                      color: m.textPrimary,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      color: m.textPrimary,
                    ),
              textAlign: TextAlign.end,
            ),
          ),
        ],
      ),
    );
  }
}

class _GatewayRow extends StatelessWidget {
  final String name;
  final _L l;
  const _GatewayRow({required this.name, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.border),
      ),
      child: ListTile(
        leading: Container(
          padding: const EdgeInsets.all(8),
          decoration: BoxDecoration(
            color: AppColors.accentDark.withValues(alpha: 0.1),
            borderRadius: BorderRadius.circular(8),
          ),
          child: const Icon(
            Icons.payment,
            color: AppColors.accentDark,
            size: 20,
          ),
        ),
        title: Text(
          name,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(fontSize: 14, color: m.textPrimary)
              : GoogleFonts.josefinSans(
                  fontSize: 14,
                  fontWeight: FontWeight.w500,
                  color: m.textPrimary,
                ),
        ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'بوابة الدفع' : 'Payment Gateway';
  String get failedToLoadConfig => ar
      ? 'تعذر تحميل إعداد بوابة الدفع'
      : 'Failed to load gateway configuration';
  String get noGateway =>
      ar ? 'لم يتم إعداد بوابة دفع' : 'No payment gateway configured';
  String get availableGateways =>
      ar ? 'بوابات الدفع المتاحة' : 'Available Gateways';
  String get failedToLoadGateways => ar
      ? 'تعذر تحميل بوابات الدفع المتاحة'
      : 'Failed to load available gateways';
  String get noGatewaysAvailable =>
      ar ? 'لا توجد بوابات دفع متاحة' : 'No gateways available';
  String get active => ar ? 'نشط' : 'Active';
  String get inactive => ar ? 'غير نشط' : 'Inactive';
  String get configuration => ar ? 'الإعدادات' : 'Configuration';
  String get gatewayName => ar ? 'اسم البوابة' : 'Gateway Name';
  String get providerType => ar ? 'نوع المزوّد' : 'Provider Type';
  String get keyId => ar ? 'معرّف المفتاح' : 'Key ID';
}

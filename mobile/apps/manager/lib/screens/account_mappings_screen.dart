import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _settingsServiceProvider = Provider<SettingsService>((ref) {
  final client = ref.watch(apiClientProvider);
  return SettingsService(client.dio);
});

final _accountMappingsProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_settingsServiceProvider);
  return service.getAccountMappings();
});

final _transactionNaturesProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_settingsServiceProvider);
  return service.getTransactionNatures();
});

class AccountMappingsScreen extends ConsumerStatefulWidget {
  const AccountMappingsScreen({super.key});

  @override
  ConsumerState<AccountMappingsScreen> createState() =>
      _AccountMappingsScreenState();
}

class _AccountMappingsScreenState extends ConsumerState<AccountMappingsScreen> {
  Future<void> _refresh() async {
    ref.invalidate(_accountMappingsProvider);
    ref.invalidate(_transactionNaturesProvider);
  }

  Map<String, List<Map<String, dynamic>>> _groupByNature(
    List<dynamic> mappings,
  ) {
    final grouped = <String, List<Map<String, dynamic>>>{};
    for (final m in mappings) {
      if (m is! Map<String, dynamic>) continue;
      final nature = (m['transactionNature'] ?? 'UNKNOWN').toString();
      grouped.putIfAbsent(nature, () => []);
      grouped[nature]!.add(m);
    }
    return grouped;
  }

  @override
  Widget build(BuildContext context) {
    final mappingsAsync = ref.watch(_accountMappingsProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(l: l),
          Expanded(
            child: mappingsAsync.when(
              loading: () => Center(
                child: CircularProgressIndicator(color: AppColors.accent),
              ),
              error: (e, _) =>
                  ErrorState(message: l.failedToLoad, onRetry: _refresh),
              data: (mappings) {
                if (mappings.isEmpty) {
                  return EmptyState(
                    icon: Icons.account_tree_outlined,
                    title: l.noMappings,
                  );
                }

                final grouped = _groupByNature(mappings);
                final natures = grouped.keys.toList()..sort();

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.accent,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.all(16),
                    itemCount: natures.length,
                    itemBuilder: (context, index) {
                      final nature = natures[index];
                      final items = grouped[nature]!;
                      return _NatureCard(nature: nature, items: items, l: l);
                    },
                  ),
                );
              },
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

class _NatureCard extends StatelessWidget {
  final String nature;
  final List<Map<String, dynamic>> items;
  final _L l;

  const _NatureCard({
    required this.nature,
    required this.items,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Theme(
        data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
        child: ExpansionTile(
          iconColor: AppColors.accentDark,
          collapsedIconColor: m.textMuted,
          leading: Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: AppColors.accentDark.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Icon(
              Icons.category_outlined,
              color: AppColors.accentDark,
              size: 20,
            ),
          ),
          title: Text(
            l.natureLabel(nature),
            style: l.ar
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
          ),
          subtitle: Text(
            l.mappingCount(items.length),
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textMuted)
                : GoogleFonts.josefinSans(fontSize: 11.5, color: m.textMuted),
          ),
          children: items.map<Widget>((mapping) {
            final accountName = (mapping['accountName'] ?? '-').toString();
            final accountCode = (mapping['accountCode'] ?? '').toString();

            return Container(
              decoration: BoxDecoration(
                border: Border(top: BorderSide(color: m.divider)),
              ),
              child: ListTile(
                dense: true,
                leading: Icon(
                  Icons.account_balance_outlined,
                  size: 18,
                  color: m.textSecondary,
                ),
                title: Text(
                  accountName,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 13.5,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 13,
                          fontWeight: FontWeight.w500,
                          color: m.textPrimary,
                        ),
                ),
                subtitle: accountCode.isNotEmpty
                    ? Text(
                        l.codeLine(accountCode),
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 12,
                                color: m.textMuted,
                              )
                            : GoogleFonts.josefinSans(
                                fontSize: 11.5,
                                color: m.textMuted,
                              ),
                      )
                    : null,
              ),
            );
          }).toList(),
        ),
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'ربط الحسابات' : 'Account Mappings';
  String get failedToLoad =>
      ar ? 'تعذر تحميل ربط الحسابات' : 'Failed to load account mappings';
  String get noMappings =>
      ar ? 'لا توجد قواعد ربط حسابات' : 'No account mappings configured';

  String mappingCount(int n) {
    if (ar) return '$n ${n == 1 ? "ربط" : "روابط"}';
    return '$n mapping${n == 1 ? '' : 's'}';
  }

  String codeLine(String code) => ar ? 'الرمز: $code' : 'Code: $code';

  String natureLabel(String nature) {
    if (!ar) {
      return nature
          .replaceAll('_', ' ')
          .split(' ')
          .map(
            (w) => w.isEmpty
                ? w
                : '${w[0].toUpperCase()}${w.substring(1).toLowerCase()}',
          )
          .join(' ');
    }
    const arMap = {
      'RENT_PAYMENT_CLEARED': 'صرف دفعة إيجار',
      'SECURITY_DEPOSIT_RECEIVED': 'استلام مبلغ تأمين',
      'SECURITY_DEPOSIT_REFUNDED': 'استرداد مبلغ تأمين',
      'CHEQUE_BOUNCED': 'ارتداد شيك',
      'SALARY_PAYMENT': 'دفع راتب',
      'VENDOR_PAYMENT': 'دفعة لمورّد',
      'VENDOR_INVOICE_RECEIVED': 'استلام فاتورة مورّد',
      'MAINTENANCE_EXPENSE': 'مصروف صيانة',
      'UTILITY_PAYMENT': 'دفعة مرافق',
      'PENALTY_INCOME': 'دخل غرامات',
      'UNKNOWN': 'غير معروف',
    };
    return arMap[nature] ?? nature.replaceAll('_', ' ');
  }
}

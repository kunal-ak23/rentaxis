import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _bankAccountServiceProvider = Provider<BankAccountService>((ref) {
  final client = ref.watch(apiClientProvider);
  return BankAccountService(client.dio);
});

final _bankAccountsProvider = FutureProvider.autoDispose<List<dynamic>>((
  ref,
) async {
  final service = ref.watch(_bankAccountServiceProvider);
  return service.getBankAccounts();
});

class BankAccountsScreen extends ConsumerStatefulWidget {
  const BankAccountsScreen({super.key});

  @override
  ConsumerState<BankAccountsScreen> createState() => _BankAccountsScreenState();
}

/// Localized name of the nested `property` object the backend serializes
/// (nameEn/nameAr) — there is no flat `propertyName` field in the response.
String _propertyName(Map<String, dynamic> account, bool ar) {
  final property = account['property'];
  if (property is! Map) return '';
  final nameEn = (property['nameEn'] ?? '').toString();
  final nameAr = (property['nameAr'] ?? '').toString();
  if (ar) return nameAr.isNotEmpty ? nameAr : nameEn;
  return nameEn.isNotEmpty ? nameEn : nameAr;
}

class _BankAccountsScreenState extends ConsumerState<BankAccountsScreen> {
  Future<void> _refresh() async {
    ref.invalidate(_bankAccountsProvider);
  }

  void _showAccountDetails(
    BuildContext context,
    Map<String, dynamic> account,
    _L l,
  ) {
    final m = context.miftah;
    final bankName = account['bankName'] ?? l.unknown;
    final accountNumber = (account['accountNumber'] ?? '').toString();
    final iban = (account['iban'] ?? '').toString();
    final branch = (account['branchName'] ?? '').toString();
    final propertyName = _propertyName(account, l.ar);

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: m.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 40,
                height: 4,
                decoration: BoxDecoration(
                  color: m.borderStrong,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 20),
            Text(
              bankName,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 19,
                      fontWeight: FontWeight.w600,
                      color: m.textPrimary,
                    )
                  : GoogleFonts.plusJakartaSans(fontSize: 18, color: m.textPrimary),
            ),
            const SizedBox(height: 20),
            _DetailRow(label: l.bankName, value: bankName, l: l),
            if (accountNumber.isNotEmpty)
              _DetailRow(label: l.accountNumber, value: accountNumber, l: l),
            if (iban.isNotEmpty) _DetailRow(label: l.iban, value: iban, l: l),
            if (branch.isNotEmpty)
              _DetailRow(label: l.branch, value: branch, l: l),
            if (propertyName.isNotEmpty)
              _DetailRow(label: l.linkedProperty, value: propertyName, l: l),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final accountsAsync = ref.watch(_bankAccountsProvider);
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _ChromeHeader(l: l),
          Expanded(
            child: accountsAsync.when(
              loading: () => Center(
                child: CircularProgressIndicator(color: AppColors.accent),
              ),
              error: (e, _) =>
                  ErrorState(message: l.failedToLoad, onRetry: _refresh),
              data: (accounts) {
                if (accounts.isEmpty) {
                  return EmptyState(
                    icon: Icons.account_balance_outlined,
                    title: l.noBankAccounts,
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.accent,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.all(16),
                    itemCount: accounts.length,
                    itemBuilder: (context, index) {
                      final account = accounts[index];
                      return _BankAccountCard(
                        account: account,
                        l: l,
                        onTap: () => _showAccountDetails(context, account, l),
                      );
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

class _BankAccountCard extends StatelessWidget {
  final Map<String, dynamic> account;
  final _L l;
  final VoidCallback onTap;

  const _BankAccountCard({
    required this.account,
    required this.l,
    required this.onTap,
  });

  String _maskAccountNumber(String number) {
    if (number.length <= 4) return number;
    return '****${number.substring(number.length - 4)}';
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final bankName = (account['bankName'] ?? l.unknown).toString();
    final accountNumber = (account['accountNumber'] ?? '').toString();
    final iban = (account['iban'] ?? '').toString();
    final propertyName = _propertyName(account, l.ar);
    // `default` fallback tolerates a backend deployed before the
    // @JsonProperty("isDefault") rename.
    final isDefault =
        account['isDefault'] == true || account['default'] == true;

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: AppColors.accentDark.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(
                  Icons.account_balance,
                  color: AppColors.accentDark,
                  size: 22,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            bankName,
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 14.5,
                                    fontWeight: FontWeight.w600,
                                    color: m.textPrimary,
                                  )
                                : GoogleFonts.plusJakartaSans(
                                    fontSize: 14,
                                    fontWeight: FontWeight.w600,
                                    color: m.textPrimary,
                                  ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        if (isDefault) ...[
                          const SizedBox(width: 8),
                          _Pill(label: l.defaultLabel),
                        ],
                      ],
                    ),
                    if (accountNumber.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        _maskAccountNumber(accountNumber),
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 12,
                          color: m.textSecondary,
                        ),
                      ),
                    ],
                    if (iban.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        l.ibanLine(iban),
                        style: GoogleFonts.plusJakartaSans(
                          fontSize: 11,
                          color: m.textMuted,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                    if (propertyName.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        propertyName,
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 11.5,
                                color: m.textMuted,
                              )
                            : GoogleFonts.plusJakartaSans(
                                fontSize: 11,
                                color: m.textMuted,
                              ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
              Icon(
                context.isAr ? Icons.chevron_left : Icons.chevron_right,
                color: m.textMuted,
                size: 20,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  final String label;
  const _Pill({required this.label});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final ar = context.isAr;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: m.successBg,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        ar ? label : label.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(fontSize: 10, color: m.success)
            : GoogleFonts.plusJakartaSans(
                fontSize: 9.5,
                letterSpacing: 1.2,
                fontWeight: FontWeight.w600,
                color: m.success,
              ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  final String label;
  final String value;
  final _L l;

  const _DetailRow({required this.label, required this.value, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textMuted)
                : GoogleFonts.plusJakartaSans(
                    fontSize: 10.5,
                    letterSpacing: 1.6,
                    color: m.textMuted,
                  ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.plusJakartaSans(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
        ],
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'الحسابات البنكية' : 'Bank Accounts';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get bankName => ar ? 'اسم البنك' : 'Bank Name';
  String get accountNumber => ar ? 'رقم الحساب' : 'Account Number';
  String get iban => ar ? 'رقم الآيبان' : 'IBAN';
  String get branch => ar ? 'الفرع' : 'Branch';
  String get linkedProperty => ar ? 'العقار المرتبط' : 'Linked Property';
  String get defaultLabel => ar ? 'افتراضي' : 'Default';
  String get failedToLoad =>
      ar ? 'تعذر تحميل الحسابات البنكية' : 'Failed to load bank accounts';
  String get noBankAccounts => ar ? 'لا توجد حسابات بنكية' : 'No bank accounts';
  String ibanLine(String iban) => ar ? 'آيبان: $iban' : 'IBAN: $iban';
}

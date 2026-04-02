import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _bankAccountServiceProvider = Provider<BankAccountService>((ref) {
  final client = ref.watch(apiClientProvider);
  return BankAccountService(client.dio);
});

final _bankAccountsProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_bankAccountServiceProvider);
  return service.getBankAccounts();
});

class BankAccountsScreen extends ConsumerStatefulWidget {
  const BankAccountsScreen({super.key});

  @override
  ConsumerState<BankAccountsScreen> createState() =>
      _BankAccountsScreenState();
}

class _BankAccountsScreenState extends ConsumerState<BankAccountsScreen> {
  Future<void> _refresh() async {
    ref.invalidate(_bankAccountsProvider);
  }

  void _showAccountDetails(BuildContext context, Map<String, dynamic> account) {
    final bankName = account['bankName'] ?? 'Unknown';
    final accountNumber = (account['accountNumber'] ?? '').toString();
    final iban = (account['iban'] ?? '').toString();
    final swiftCode = (account['swiftCode'] ?? '').toString();
    final branch = (account['branch'] ?? '').toString();
    final propertyName = (account['propertyName'] ?? '').toString();

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
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
                  color: AppColors.border,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 20),
            Text(bankName, style: Theme.of(ctx).textTheme.headlineSmall),
            const SizedBox(height: 20),
            _DetailRow(label: 'Bank Name', value: bankName),
            if (accountNumber.isNotEmpty)
              _DetailRow(label: 'Account Number', value: accountNumber),
            if (iban.isNotEmpty)
              _DetailRow(label: 'IBAN', value: iban),
            if (swiftCode.isNotEmpty)
              _DetailRow(label: 'SWIFT Code', value: swiftCode),
            if (branch.isNotEmpty)
              _DetailRow(label: 'Branch', value: branch),
            if (propertyName.isNotEmpty)
              _DetailRow(label: 'Linked Property', value: propertyName),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final accountsAsync = ref.watch(_bankAccountsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Bank Accounts')),
      body: accountsAsync.when(
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
        error: (e, _) => ErrorState(
          message: 'Failed to load bank accounts',
          onRetry: _refresh,
        ),
        data: (accounts) {
          if (accounts.isEmpty) {
            return const EmptyState(
              icon: Icons.account_balance_outlined,
              title: 'No bank accounts',
            );
          }

          return RefreshIndicator(
            onRefresh: _refresh,
            color: AppColors.primary,
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              itemCount: accounts.length,
              itemBuilder: (context, index) {
                final account = accounts[index];
                return _BankAccountCard(
                  account: account,
                  onTap: () => _showAccountDetails(context, account),
                );
              },
            ),
          );
        },
      ),
    );
  }
}

class _BankAccountCard extends StatelessWidget {
  final Map<String, dynamic> account;
  final VoidCallback onTap;

  const _BankAccountCard({required this.account, required this.onTap});

  String _maskAccountNumber(String number) {
    if (number.length <= 4) return number;
    return '****${number.substring(number.length - 4)}';
  }

  @override
  Widget build(BuildContext context) {
    final bankName = (account['bankName'] ?? 'Unknown').toString();
    final accountNumber = (account['accountNumber'] ?? '').toString();
    final iban = (account['iban'] ?? '').toString();
    final propertyName = (account['propertyName'] ?? '').toString();

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: AppColors.primary.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(
                  Icons.account_balance,
                  color: AppColors.primary,
                  size: 22,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      bankName,
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 14,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (accountNumber.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        _maskAccountNumber(accountNumber),
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textSecondary,
                        ),
                      ),
                    ],
                    if (iban.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        'IBAN: $iban',
                        style: const TextStyle(
                          fontSize: 11,
                          color: AppColors.textMuted,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                    if (propertyName.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        propertyName,
                        style: const TextStyle(
                          fontSize: 11,
                          color: AppColors.textMuted,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                  ],
                ),
              ),
              const Icon(Icons.chevron_right,
                  color: AppColors.textMuted, size: 20),
            ],
          ),
        ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  final String label;
  final String value;

  const _DetailRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: const TextStyle(
              fontSize: 12,
              color: AppColors.textMuted,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            value,
            style: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

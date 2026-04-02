import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _settingsServiceProvider = Provider<SettingsService>((ref) {
  final client = ref.watch(apiClientProvider);
  return SettingsService(client.dio);
});

final _accountMappingsProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_settingsServiceProvider);
  return service.getAccountMappings();
});

final _transactionNaturesProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_settingsServiceProvider);
  return service.getTransactionNatures();
});

class AccountMappingsScreen extends ConsumerStatefulWidget {
  const AccountMappingsScreen({super.key});

  @override
  ConsumerState<AccountMappingsScreen> createState() =>
      _AccountMappingsScreenState();
}

class _AccountMappingsScreenState
    extends ConsumerState<AccountMappingsScreen> {
  Future<void> _refresh() async {
    ref.invalidate(_accountMappingsProvider);
    ref.invalidate(_transactionNaturesProvider);
  }

  String _formatNature(String nature) {
    return nature
        .replaceAll('_', ' ')
        .split(' ')
        .map((w) => w.isEmpty
            ? w
            : '${w[0].toUpperCase()}${w.substring(1).toLowerCase()}')
        .join(' ');
  }

  Map<String, List<Map<String, dynamic>>> _groupByNature(
    List<dynamic> mappings,
  ) {
    final grouped = <String, List<Map<String, dynamic>>>{};
    for (final m in mappings) {
      if (m is! Map<String, dynamic>) continue;
      final nature =
          (m['transactionNature'] ?? 'UNKNOWN').toString();
      grouped.putIfAbsent(nature, () => []);
      grouped[nature]!.add(m);
    }
    return grouped;
  }

  @override
  Widget build(BuildContext context) {
    final mappingsAsync = ref.watch(_accountMappingsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Account Mappings')),
      body: mappingsAsync.when(
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
        error: (e, _) => ErrorState(
          message: 'Failed to load account mappings',
          onRetry: _refresh,
        ),
        data: (mappings) {
          if (mappings.isEmpty) {
            return const EmptyState(
              icon: Icons.account_tree_outlined,
              title: 'No account mappings configured',
            );
          }

          final grouped = _groupByNature(mappings);
          final natures = grouped.keys.toList()..sort();

          return RefreshIndicator(
            onRefresh: _refresh,
            color: AppColors.primary,
            child: ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              itemCount: natures.length,
              itemBuilder: (context, index) {
                final nature = natures[index];
                final items = grouped[nature]!;

                return Card(
                  margin: const EdgeInsets.only(bottom: 8),
                  clipBehavior: Clip.antiAlias,
                  child: ExpansionTile(
                    leading: Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: AppColors.primary.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Icon(
                        Icons.category_outlined,
                        color: AppColors.primary,
                        size: 20,
                      ),
                    ),
                    title: Text(
                      _formatNature(nature),
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 14,
                      ),
                    ),
                    subtitle: Text(
                      '${items.length} mapping${items.length == 1 ? '' : 's'}',
                      style: const TextStyle(
                        fontSize: 12,
                        color: AppColors.textMuted,
                      ),
                    ),
                    children: items.map<Widget>((mapping) {
                      final accountName =
                          (mapping['accountName'] ?? '-').toString();
                      final accountCode =
                          (mapping['accountCode'] ?? '').toString();

                      return ListTile(
                        dense: true,
                        leading: const Icon(
                          Icons.account_balance_outlined,
                          size: 18,
                          color: AppColors.textSecondary,
                        ),
                        title: Text(
                          accountName,
                          style: const TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                        subtitle: accountCode.isNotEmpty
                            ? Text(
                                'Code: $accountCode',
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: AppColors.textMuted,
                                ),
                              )
                            : null,
                      );
                    }).toList(),
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }
}

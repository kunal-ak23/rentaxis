import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
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

final _availableGatewaysProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
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

    return Scaffold(
      appBar: AppBar(title: const Text('Payment Gateway')),
      body: configAsync.when(
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
        error: (e, _) => ErrorState(
          message: 'Failed to load gateway configuration',
          onRetry: _refresh,
        ),
        data: (config) {
          return RefreshIndicator(
            onRefresh: _refresh,
            color: AppColors.primary,
            child: SingleChildScrollView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (config != null) ...[
                    // Status card
                    _buildStatusCard(config),
                    const SizedBox(height: 16),
                    // Configuration details
                    _buildConfigCard(config),
                  ] else
                    const EmptyState(
                      icon: Icons.credit_card_off_outlined,
                      title: 'No payment gateway configured',
                    ),
                  const SizedBox(height: 24),
                  // Available gateways
                  const Text(
                    'Available Gateways',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 12),
                  gatewaysAsync.when(
                    loading: () => const Center(
                      child: Padding(
                        padding: EdgeInsets.all(16),
                        child: CircularProgressIndicator(
                          color: AppColors.primary,
                        ),
                      ),
                    ),
                    error: (e, _) => const Text(
                      'Failed to load available gateways',
                      style: TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 13,
                      ),
                    ),
                    data: (gateways) {
                      if (gateways.isEmpty) {
                        return const Text(
                          'No gateways available',
                          style: TextStyle(
                            color: AppColors.textMuted,
                            fontSize: 13,
                          ),
                        );
                      }
                      return Column(
                        children: gateways.map<Widget>((g) {
                          final name = g is Map
                              ? (g['name'] ?? g.toString()).toString()
                              : g.toString();
                          return Card(
                            margin: const EdgeInsets.only(bottom: 8),
                            child: ListTile(
                              leading: Container(
                                padding: const EdgeInsets.all(8),
                                decoration: BoxDecoration(
                                  color: AppColors.primary
                                      .withValues(alpha: 0.1),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: const Icon(
                                  Icons.payment,
                                  color: AppColors.primary,
                                  size: 20,
                                ),
                              ),
                              title: Text(
                                name,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w500,
                                  fontSize: 14,
                                ),
                              ),
                            ),
                          );
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
    );
  }

  Widget _buildStatusCard(Map<String, dynamic> config) {
    final isActive = config['active'] == true ||
        config['status']?.toString().toUpperCase() == 'ACTIVE';

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              width: 12,
              height: 12,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: isActive ? Colors.green : Colors.red,
              ),
            ),
            const SizedBox(width: 12),
            Text(
              isActive ? 'Active' : 'Inactive',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: isActive ? Colors.green : Colors.red,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildConfigCard(Map<String, dynamic> config) {
    final gatewayName =
        (config['gatewayName'] ?? config['name'] ?? '-').toString();
    final providerType =
        (config['providerType'] ?? config['provider'] ?? '-').toString();
    final keyId = (config['keyId'] ?? config['key_id'] ?? '').toString();

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Configuration',
              style: TextStyle(
                fontWeight: FontWeight.w600,
                fontSize: 15,
              ),
            ),
            const SizedBox(height: 16),
            _ConfigRow(
              icon: Icons.account_balance_wallet_outlined,
              label: 'Gateway Name',
              value: gatewayName,
            ),
            _ConfigRow(
              icon: Icons.integration_instructions_outlined,
              label: 'Provider Type',
              value: providerType.replaceAll('_', ' '),
            ),
            if (keyId.isNotEmpty)
              _ConfigRow(
                icon: Icons.key_outlined,
                label: 'Key ID',
                value: _maskKey(keyId),
              ),
          ],
        ),
      ),
    );
  }
}

class _ConfigRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;

  const _ConfigRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Row(
        children: [
          Icon(icon, size: 20, color: AppColors.primary),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              label,
              style: const TextStyle(
                fontSize: 13,
                color: AppColors.textSecondary,
              ),
            ),
          ),
          Flexible(
            child: Text(
              value,
              style: const TextStyle(
                fontSize: 14,
                fontWeight: FontWeight.w600,
              ),
              textAlign: TextAlign.end,
            ),
          ),
        ],
      ),
    );
  }
}

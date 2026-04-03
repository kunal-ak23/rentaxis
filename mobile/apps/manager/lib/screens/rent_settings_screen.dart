import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _settingsServiceProvider = Provider<SettingsService>((ref) {
  final client = ref.watch(apiClientProvider);
  return SettingsService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _propertiesProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_propertyServiceProvider);
  return service.getProperties();
});

class RentSettingsScreen extends ConsumerStatefulWidget {
  const RentSettingsScreen({super.key});

  @override
  ConsumerState<RentSettingsScreen> createState() => _RentSettingsScreenState();
}

class _RentSettingsScreenState extends ConsumerState<RentSettingsScreen> {
  String? _selectedPropertyId;
  String? _selectedPropertyName;
  bool _loadingSettings = false;
  Map<String, dynamic>? _settings;
  String? _error;

  Future<void> _loadSettings(String propertyId) async {
    setState(() {
      _loadingSettings = true;
      _error = null;
    });
    try {
      final service = ref.read(_settingsServiceProvider);
      final data = await service.getRentSettings(propertyId);
      setState(() {
        _settings = data;
        _loadingSettings = false;
      });
    } catch (e) {
      setState(() {
        _error = 'Failed to load rent settings';
        _loadingSettings = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final propertiesAsync = ref.watch(_propertiesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Rent Collection Settings')),
      body: propertiesAsync.when(
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
        error: (e, _) => ErrorState(
          message: 'Failed to load properties',
          onRetry: () => ref.invalidate(_propertiesProvider),
        ),
        data: (properties) {
          return SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Property dropdown
                DropdownButtonFormField<String>(
                  value: _selectedPropertyId,
                  decoration: const InputDecoration(
                    labelText: 'Select Property',
                    prefixIcon: Icon(Icons.apartment),
                    border: OutlineInputBorder(),
                  ),
                  items: properties.map<DropdownMenuItem<String>>((p) {
                    final id = p['id']?.toString() ?? '';
                    final name = p['name']?.toString() ?? 'Unnamed';
                    return DropdownMenuItem(value: id, child: Text(name));
                  }).toList(),
                  onChanged: (value) {
                    if (value == null) return;
                    final prop = properties.firstWhere(
                      (p) => p['id']?.toString() == value,
                    );
                    setState(() {
                      _selectedPropertyId = value;
                      _selectedPropertyName =
                          prop['name']?.toString() ?? 'Unnamed';
                    });
                    _loadSettings(value);
                  },
                ),
                const SizedBox(height: 24),

                // Content
                if (_selectedPropertyId == null)
                  const Center(
                    child: Padding(
                      padding: EdgeInsets.only(top: 40),
                      child: Column(
                        children: [
                          Icon(
                            Icons.touch_app_outlined,
                            size: 48,
                            color: AppColors.textMuted,
                          ),
                          SizedBox(height: 12),
                          Text(
                            'Select a property to view settings',
                            style: TextStyle(
                              fontSize: 14,
                              color: AppColors.textMuted,
                            ),
                          ),
                        ],
                      ),
                    ),
                  )
                else if (_loadingSettings)
                  const Center(
                    child: Padding(
                      padding: EdgeInsets.only(top: 40),
                      child: CircularProgressIndicator(
                        color: AppColors.primary,
                      ),
                    ),
                  )
                else if (_error != null)
                  ErrorState(
                    message: _error!,
                    onRetry: () => _loadSettings(_selectedPropertyId!),
                  )
                else if (_settings == null)
                  const EmptyState(
                    icon: Icons.settings_outlined,
                    title: 'No rent settings configured for this property',
                  )
                else
                  _buildSettingsCards(),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildSettingsCards() {
    final settings = _settings!;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _selectedPropertyName ?? 'Property Settings',
              style: const TextStyle(
                fontWeight: FontWeight.w600,
                fontSize: 16,
              ),
            ),
            const SizedBox(height: 16),
            _SettingRow(
              icon: Icons.timer_outlined,
              label: 'Grace Period',
              value: '${settings['gracePeriodDays'] ?? '-'} days',
            ),
            _SettingRow(
              icon: Icons.percent,
              label: 'Late Payment Penalty Rate',
              value: '${settings['penaltyRate'] ?? '-'}%',
            ),
            if (settings['penaltyType'] != null)
              _SettingRow(
                icon: Icons.category_outlined,
                label: 'Penalty Type',
                value: (settings['penaltyType'] as String)
                    .replaceAll('_', ' '),
              ),
            if (settings['autoApplyPenalty'] != null)
              _SettingRow(
                icon: Icons.autorenew,
                label: 'Auto-Apply Penalty',
                value: settings['autoApplyPenalty'] == true ? 'Yes' : 'No',
              ),
            if (settings['reminderDaysBefore'] != null)
              _SettingRow(
                icon: Icons.notifications_outlined,
                label: 'Reminder Before Due',
                value: '${settings['reminderDaysBefore']} days',
              ),
          ],
        ),
      ),
    );
  }
}

class _SettingRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;

  const _SettingRow({
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
          Text(
            value,
            style: const TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

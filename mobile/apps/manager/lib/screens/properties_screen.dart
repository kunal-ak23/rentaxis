import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _unitServiceProvider = Provider<UnitService>((ref) {
  final client = ref.watch(apiClientProvider);
  return UnitService(client.dio);
});

final _propertiesProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_propertyServiceProvider);
  return service.getProperties();
});

final _allUnitsProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_unitServiceProvider);
  return service.getUnits();
});

class PropertiesScreen extends ConsumerStatefulWidget {
  const PropertiesScreen({super.key});

  @override
  ConsumerState<PropertiesScreen> createState() => _PropertiesScreenState();
}

class _PropertiesScreenState extends ConsumerState<PropertiesScreen> {
  String _searchQuery = '';

  Future<void> _refresh() async {
    ref.invalidate(_propertiesProvider);
    ref.invalidate(_allUnitsProvider);
  }

  @override
  Widget build(BuildContext context) {
    final propertiesAsync = ref.watch(_propertiesProvider);
    final unitsAsync = ref.watch(_allUnitsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Properties'),
      ),
      body: Column(
        children: [
          // Search bar
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
            child: TextField(
              onChanged: (v) => setState(() => _searchQuery = v.toLowerCase()),
              decoration: InputDecoration(
                hintText: 'Search properties...',
                prefixIcon:
                    const Icon(Icons.search, color: AppColors.textMuted),
                suffixIcon: _searchQuery.isNotEmpty
                    ? IconButton(
                        icon: const Icon(Icons.clear, size: 18),
                        onPressed: () => setState(() => _searchQuery = ''),
                      )
                    : null,
              ),
            ),
          ),
          Expanded(
            child: propertiesAsync.when(
              loading: () => const Padding(
                padding: EdgeInsets.symmetric(horizontal: 20),
                child: ListShimmer(itemCount: 3),
              ),
              error: (e, _) => ErrorState(
                message: 'Failed to load properties',
                onRetry: _refresh,
              ),
              data: (properties) {
                final allUnits = unitsAsync.valueOrNull ?? [];
                final filtered = properties.where((p) {
                  final prop = p['property'] ?? p;
                  final name =
                      (prop['nameEn'] ?? prop['name'] ?? '').toString().toLowerCase();
                  final address =
                      (prop['address'] ?? '').toString().toLowerCase();
                  return name.contains(_searchQuery) ||
                      address.contains(_searchQuery);
                }).toList();

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.apartment_outlined,
                    title: _searchQuery.isEmpty
                        ? 'No properties yet'
                        : 'No matching properties',
                    subtitle: _searchQuery.isEmpty
                        ? 'Add your first property to get started'
                        : null,
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.primary,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(20, 8, 20, 150),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final item = filtered[index];
                      final prop = item['property'] ?? item;
                      final propertyId = prop['id'] ?? item['id'] ?? '';
                      final units = allUnits
                          .where((u) {
                            final unitPropId = u['propertyId'] ?? u['property']?['id'];
                            return unitPropId == propertyId;
                          })
                          .toList();
                      final occupied = units
                          .where((u) => u['status'] == 'OCCUPIED')
                          .length;
                      final total = units.length;
                      final vacancies = item['vacancies'] ?? 0;
                      final unitCount = total > 0 ? total : (item['propertyCount'] ?? 0);
                      final occupiedCount = total > 0 ? occupied : (unitCount - (vacancies as int));
                      final occupancy =
                          unitCount > 0 ? occupiedCount / unitCount : 0.0;

                      return AnimatedListItem(
                        index: index,
                        child: _PropertyCard(
                          name: prop['nameEn'] ?? prop['name'] ?? '',
                          address: prop['address'] ?? '',
                          emirate: prop['emirate'] ?? '',
                          totalUnits: unitCount,
                          occupiedUnits: occupiedCount,
                          occupancy: occupancy,
                          onTap: () =>
                              context.push('/properties/$propertyId'),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        onPressed: () => _showCreatePropertySheet(context),
        child: const Icon(Icons.add, color: Colors.white),
      ),
    );
  }

  void _showCreatePropertySheet(BuildContext context) {
    final nameCtrl = TextEditingController();
    final addressCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();
    String selectedEmirate = 'DUBAI';

    final emirates = [
      'DUBAI',
      'ABU_DHABI',
      'SHARJAH',
      'AJMAN',
      'RAS_AL_KHAIMAH',
      'FUJAIRAH',
      'UMM_AL_QUWAIN'
    ];

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(
              24, 24, 24, MediaQuery.of(ctx).viewInsets.bottom + 24),
          child: Form(
            key: formKey,
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
                Text('New Property',
                    style: Theme.of(ctx).textTheme.headlineSmall),
                const SizedBox(height: 20),
                TextFormField(
                  controller: nameCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Property Name',
                    prefixIcon: Icon(Icons.apartment_outlined),
                  ),
                  validator: (v) => v == null || v.trim().isEmpty
                      ? 'Name is required'
                      : null,
                ),
                const SizedBox(height: 16),
                TextFormField(
                  controller: addressCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Address',
                    prefixIcon: Icon(Icons.location_on_outlined),
                  ),
                  validator: (v) => v == null || v.trim().isEmpty
                      ? 'Address is required'
                      : null,
                ),
                const SizedBox(height: 16),
                DropdownButtonFormField<String>(
                  value: selectedEmirate,
                  decoration: const InputDecoration(
                    labelText: 'Emirate',
                    prefixIcon: Icon(Icons.flag_outlined),
                  ),
                  items: emirates
                      .map((e) => DropdownMenuItem(
                          value: e,
                          child: Text(e.replaceAll('_', ' '))))
                      .toList(),
                  onChanged: (v) =>
                      setSheetState(() => selectedEmirate = v ?? 'DUBAI'),
                ),
                const SizedBox(height: 24),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    onPressed: () async {
                      if (!formKey.currentState!.validate()) return;
                      final service =
                          ref.read(_propertyServiceProvider);
                      try {
                        await service.createProperty({
                          'name': nameCtrl.text.trim(),
                          'address': addressCtrl.text.trim(),
                          'emirate': selectedEmirate,
                        });
                        if (ctx.mounted) Navigator.pop(ctx);
                        _refresh();
                      } catch (e) {
                        if (ctx.mounted) {
                          ScaffoldMessenger.of(ctx).showSnackBar(
                            const SnackBar(
                                content:
                                    Text('Failed to create property')),
                          );
                        }
                      }
                    },
                    child: const Text('Create Property'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PropertyCard extends StatelessWidget {
  final String name;
  final String address;
  final String emirate;
  final int totalUnits;
  final int occupiedUnits;
  final double occupancy;
  final VoidCallback onTap;

  const _PropertyCard({
    required this.name,
    required this.address,
    required this.emirate,
    required this.totalUnits,
    required this.occupiedUnits,
    required this.occupancy,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: Theme.of(context).cardColor,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(16),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: AppColors.primary.withValues(alpha: 0.1),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: const Icon(Icons.apartment_rounded,
                          color: AppColors.primary, size: 24),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(name,
                              style: GoogleFonts.josefinSans(
                                fontWeight: FontWeight.w600,
                                fontSize: 15,
                              )),
                          const SizedBox(height: 2),
                          Text(
                            '$address, ${emirate.replaceAll('_', ' ')}',
                            style: GoogleFonts.josefinSans(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    ),
                    const Icon(Icons.chevron_right,
                        color: AppColors.textMuted),
                  ],
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    _StatChip(
                      label: 'Units',
                      value: '$totalUnits',
                      color: AppColors.info,
                    ),
                    const SizedBox(width: 8),
                    _StatChip(
                      label: 'Occupied',
                      value: '$occupiedUnits',
                      color: AppColors.success,
                    ),
                    const SizedBox(width: 8),
                    _StatChip(
                      label: 'Vacant',
                      value: '${totalUnits - occupiedUnits}',
                      color: AppColors.warning,
                    ),
                    const Spacer(),
                    Text(
                      '${(occupancy * 100).toStringAsFixed(0)}%',
                      style: GoogleFonts.cinzel(
                        fontWeight: FontWeight.w700,
                        fontSize: 16,
                        color: AppColors.primary,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: occupancy.clamp(0.0, 1.0),
                    backgroundColor: AppColors.border,
                    color: AppColors.success,
                    minHeight: 4,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _StatChip extends StatelessWidget {
  final String label;
  final String value;
  final Color color;

  const _StatChip({
    required this.label,
    required this.value,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(value,
              style: TextStyle(
                fontWeight: FontWeight.w700,
                fontSize: 12,
                color: color,
              )),
          const SizedBox(width: 4),
          Text(label,
              style: TextStyle(
                fontSize: 11,
                color: color.withValues(alpha: 0.8),
              )),
        ],
      ),
    );
  }
}

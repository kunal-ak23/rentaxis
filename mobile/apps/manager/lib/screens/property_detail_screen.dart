import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _unitServiceProvider = Provider<UnitService>((ref) {
  final client = ref.watch(apiClientProvider);
  return UnitService(client.dio);
});

final _contactServiceProvider = Provider<PropertyContactService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyContactService(client.dio);
});

final _buildingServiceProvider = Provider<BuildingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return BuildingService(client.dio);
});

class PropertyDetailScreen extends ConsumerStatefulWidget {
  final String propertyId;
  const PropertyDetailScreen({super.key, required this.propertyId});

  @override
  ConsumerState<PropertyDetailScreen> createState() =>
      _PropertyDetailScreenState();
}

class _PropertyDetailScreenState extends ConsumerState<PropertyDetailScreen> {
  Map<String, dynamic>? _property;
  List<dynamic> _units = [];
  List<dynamic> _contacts = [];
  List<dynamic> _buildings = [];
  bool _isLoading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final propService = ref.read(_propertyServiceProvider);
      final unitService = ref.read(_unitServiceProvider);
      final contactService = ref.read(_contactServiceProvider);
      final buildingService = ref.read(_buildingServiceProvider);
      final results = await Future.wait([
        propService.getPropertyById(widget.propertyId),
        unitService.getUnitsByProperty(widget.propertyId),
        contactService.getContacts(widget.propertyId),
        buildingService.getBuildingsByProperty(widget.propertyId),
      ]);
      if (!mounted) return;
      setState(() {
        _property = results[0] as Map<String, dynamic>;
        _units = results[1] as List<dynamic>;
        _contacts = results[2] as List<dynamic>;
        _buildings = results[3] as List<dynamic>;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = 'Failed to load property details';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Property')),
        body: const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
      );
    }

    if (_error != null || _property == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Property')),
        body: ErrorState(message: _error ?? 'Not found', onRetry: _loadData),
      );
    }

    final property = _property!;
    final totalUnits = _units.length;
    final occupied =
        _units.where((u) => u['status'] == 'OCCUPIED').length;
    final vacant = totalUnits - occupied;
    final occupancy = totalUnits > 0 ? occupied / totalUnits : 0.0;

    return Scaffold(
      appBar: AppBar(
        title: Text(property['name'] ?? 'Property'),
      ),
      body: RefreshIndicator(
        onRefresh: _loadData,
        color: AppColors.primary,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: EdgeInsets.fromLTRB(16, 16, 16, AppInsets.bottomNav(context)),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Property header card
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [AppColors.navyDark, Color(0xFF1A3352)],
                  ),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.apartment_rounded,
                            color: AppColors.accent, size: 28),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Text(
                            property['name'] ?? '',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 20,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    _InfoRow(
                      icon: Icons.location_on_outlined,
                      text: property['address'] ?? '-',
                    ),
                    const SizedBox(height: 6),
                    _InfoRow(
                      icon: Icons.flag_outlined,
                      text: (property['emirate'] ?? '-')
                          .toString()
                          .replaceAll('_', ' '),
                    ),
                    if (property['type'] != null) ...[
                      const SizedBox(height: 6),
                      _InfoRow(
                        icon: Icons.category_outlined,
                        text: property['type'].toString(),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(height: 20),

              // Stats row
              Row(
                children: [
                  _StatCard(
                    label: 'Total',
                    value: '$totalUnits',
                    color: AppColors.info,
                  ),
                  const SizedBox(width: 10),
                  _StatCard(
                    label: 'Occupied',
                    value: '$occupied',
                    color: AppColors.success,
                  ),
                  const SizedBox(width: 10),
                  _StatCard(
                    label: 'Vacant',
                    value: '$vacant',
                    color: AppColors.warning,
                  ),
                  const SizedBox(width: 10),
                  _StatCard(
                    label: 'Occupancy',
                    value: '${(occupancy * 100).toStringAsFixed(0)}%',
                    color: AppColors.primary,
                  ),
                ],
              ),
              const SizedBox(height: 24),

              // Units header
              Row(
                children: [
                  Text('Units',
                      style: Theme.of(context).textTheme.headlineSmall),
                  const Spacer(),
                  Text('$totalUnits total',
                      style: const TextStyle(
                        fontSize: 13,
                        color: AppColors.textSecondary,
                      )),
                ],
              ),
              const SizedBox(height: 12),

              if (_units.isEmpty)
                const EmptyState(
                  icon: Icons.door_front_door_outlined,
                  title: 'No units yet',
                  subtitle: 'Add units to this property',
                )
              else
                ..._units.map((unit) => _UnitCard(
                      unit: unit,
                      onTap: () {
                        // Unit detail - future enhancement
                      },
                    )),

              // Contacts section
              const SizedBox(height: 20),
              Row(
                children: [
                  const Icon(Icons.contacts_outlined, size: 20, color: AppColors.primary),
                  const SizedBox(width: 8),
                  Text('Contacts', style: Theme.of(context).textTheme.headlineSmall),
                  const Spacer(),
                  TextButton.icon(
                    onPressed: () => _showAddContactSheet(context),
                    icon: const Icon(Icons.add, size: 18),
                    label: const Text('Add'),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              if (_contacts.isEmpty)
                const Text('No contacts', style: TextStyle(fontSize: 13, color: AppColors.textSecondary))
              else
                ..._contacts.map((contact) => Card(
                  margin: const EdgeInsets.only(bottom: 8),
                  child: ListTile(
                    leading: CircleAvatar(
                      backgroundColor: AppColors.accent.withValues(alpha: 0.1),
                      child: const Icon(Icons.person_outline, color: AppColors.accent, size: 20),
                    ),
                    title: Text(contact['name'] ?? 'Contact', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (contact['role'] != null) Text(contact['role'], style: const TextStyle(fontSize: 12, color: AppColors.textSecondary)),
                        if (contact['phone'] != null) Text(contact['phone'], style: const TextStyle(fontSize: 12, color: AppColors.textMuted)),
                        if (contact['email'] != null) Text(contact['email'], style: const TextStyle(fontSize: 12, color: AppColors.textMuted)),
                      ],
                    ),
                    trailing: IconButton(
                      icon: const Icon(Icons.delete_outline, size: 18, color: AppColors.danger),
                      onPressed: () async {
                        final confirmed = await showDialog<bool>(
                          context: context,
                          builder: (ctx) => AlertDialog(
                            title: const Text('Delete Contact'),
                            content: const Text('Remove this contact?'),
                            actions: [
                              TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
                              ElevatedButton(
                                onPressed: () => Navigator.pop(ctx, true),
                                style: ElevatedButton.styleFrom(backgroundColor: AppColors.danger),
                                child: const Text('Delete'),
                              ),
                            ],
                          ),
                        );
                        if (confirmed == true) {
                          try {
                            await ref.read(_contactServiceProvider).deleteContact(widget.propertyId, contact['id']);
                            _loadData();
                          } catch (_) {}
                        }
                      },
                    ),
                  ),
                )),

              // Buildings section
              const SizedBox(height: 20),
              Row(
                children: [
                  const Icon(Icons.domain_outlined, size: 20, color: AppColors.primary),
                  const SizedBox(width: 8),
                  Text('Buildings', style: Theme.of(context).textTheme.headlineSmall),
                ],
              ),
              const SizedBox(height: 8),
              if (_buildings.isEmpty)
                const Text('No buildings', style: TextStyle(fontSize: 13, color: AppColors.textSecondary))
              else
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: _buildings.map((b) => Chip(
                    avatar: const Icon(Icons.domain, size: 16, color: AppColors.primary),
                    label: Text(b['name'] ?? 'Building', style: const TextStyle(fontSize: 12)),
                  )).toList(),
                ),
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: AppColors.primary,
        onPressed: () => _showCreateUnitSheet(context),
        child: const Icon(Icons.add, color: Colors.white),
      ),
    );
  }

  void _showCreateUnitSheet(BuildContext context) {
    final unitNumberCtrl = TextEditingController();
    final sizeCtrl = TextEditingController();
    final rentCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();
    String unitType = 'APARTMENT';

    final unitTypes = [
      'APARTMENT',
      'VILLA',
      'STUDIO',
      'OFFICE',
      'SHOP',
      'WAREHOUSE',
      'TOWNHOUSE',
      'PENTHOUSE'
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
            child: SingleChildScrollView(
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
                  Text('New Unit',
                      style: Theme.of(ctx).textTheme.headlineSmall),
                  const SizedBox(height: 20),
                  TextFormField(
                    controller: unitNumberCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Unit Number',
                      prefixIcon: Icon(Icons.tag),
                    ),
                    validator: (v) => v == null || v.trim().isEmpty
                        ? 'Required'
                        : null,
                  ),
                  const SizedBox(height: 16),
                  DropdownButtonFormField<String>(
                    value: unitType,
                    decoration: const InputDecoration(
                      labelText: 'Type',
                      prefixIcon: Icon(Icons.category_outlined),
                    ),
                    items: unitTypes
                        .map((t) => DropdownMenuItem(
                            value: t, child: Text(t)))
                        .toList(),
                    onChanged: (v) =>
                        setSheetState(() => unitType = v ?? 'APARTMENT'),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: sizeCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Size (sq ft)',
                      prefixIcon: Icon(Icons.square_foot_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: rentCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Annual Rent (AED)',
                      prefixIcon: Icon(Icons.attach_money),
                    ),
                  ),
                  const SizedBox(height: 24),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      onPressed: () async {
                        if (!formKey.currentState!.validate()) return;
                        final service = ref.read(_unitServiceProvider);
                        try {
                          await service.createUnit({
                            'unitNumber': unitNumberCtrl.text.trim(),
                            'propertyId': widget.propertyId,
                            'type': unitType,
                            if (sizeCtrl.text.isNotEmpty)
                              'size':
                                  double.tryParse(sizeCtrl.text.trim()),
                            if (rentCtrl.text.isNotEmpty)
                              'annualRent':
                                  double.tryParse(rentCtrl.text.trim()),
                          });
                          if (ctx.mounted) Navigator.pop(ctx);
                          _loadData();
                        } catch (e) {
                          if (ctx.mounted) {
                            ScaffoldMessenger.of(ctx).showSnackBar(
                              const SnackBar(
                                  content:
                                      Text('Failed to create unit')),
                            );
                          }
                        }
                      },
                      child: const Text('Create Unit'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _showAddContactSheet(BuildContext context) {
    final nameCtrl = TextEditingController();
    final roleCtrl = TextEditingController();
    final phoneCtrl = TextEditingController();
    final emailCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => Padding(
        padding: EdgeInsets.fromLTRB(24, 24, 24, MediaQuery.of(ctx).viewInsets.bottom + 24),
        child: Form(
          key: formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(child: Container(width: 40, height: 4, decoration: BoxDecoration(color: AppColors.border, borderRadius: BorderRadius.circular(2)))),
                const SizedBox(height: 20),
                Text('New Contact', style: Theme.of(ctx).textTheme.headlineSmall),
                const SizedBox(height: 20),
                TextFormField(controller: nameCtrl, decoration: const InputDecoration(labelText: 'Name', prefixIcon: Icon(Icons.person_outline)), validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null),
                const SizedBox(height: 16),
                TextFormField(controller: roleCtrl, decoration: const InputDecoration(labelText: 'Role', prefixIcon: Icon(Icons.work_outline))),
                const SizedBox(height: 16),
                TextFormField(controller: phoneCtrl, keyboardType: TextInputType.phone, decoration: const InputDecoration(labelText: 'Phone', prefixIcon: Icon(Icons.phone_outlined))),
                const SizedBox(height: 16),
                TextFormField(controller: emailCtrl, keyboardType: TextInputType.emailAddress, decoration: const InputDecoration(labelText: 'Email', prefixIcon: Icon(Icons.email_outlined))),
                const SizedBox(height: 24),
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    onPressed: () async {
                      if (!formKey.currentState!.validate()) return;
                      try {
                        await ref.read(_contactServiceProvider).createContact(widget.propertyId, {
                          'name': nameCtrl.text.trim(),
                          if (roleCtrl.text.isNotEmpty) 'role': roleCtrl.text.trim(),
                          if (phoneCtrl.text.isNotEmpty) 'phone': phoneCtrl.text.trim(),
                          if (emailCtrl.text.isNotEmpty) 'email': emailCtrl.text.trim(),
                        });
                        if (ctx.mounted) Navigator.pop(ctx);
                        _loadData();
                      } catch (e) {
                        if (ctx.mounted) ScaffoldMessenger.of(ctx).showSnackBar(const SnackBar(content: Text('Failed to add contact')));
                      }
                    },
                    child: const Text('Add Contact'),
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

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String text;

  const _InfoRow({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 16, color: Colors.white60),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(
              color: Colors.white70,
              fontSize: 13,
            ),
          ),
        ),
      ],
    );
  }
}

class _StatCard extends StatelessWidget {
  final String label;
  final String value;
  final Color color;

  const _StatCard({
    required this.label,
    required this.value,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 14),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: color.withValues(alpha: 0.2)),
        ),
        child: Column(
          children: [
            Text(
              value,
              style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.w700,
                color: color,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              label,
              style: TextStyle(
                fontSize: 11,
                color: color.withValues(alpha: 0.8),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _UnitCard extends StatelessWidget {
  final Map<String, dynamic> unit;
  final VoidCallback onTap;

  const _UnitCard({required this.unit, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final status = unit['status'] ?? 'VACANT';
    final isOccupied = status == 'OCCUPIED';

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
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: (isOccupied ? AppColors.info : AppColors.success)
                      .withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  Icons.door_front_door_outlined,
                  color: isOccupied ? AppColors.info : AppColors.success,
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
                        Text(
                          'Unit ${unit['unitNumber'] ?? '-'}',
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(width: 8),
                        StatusBadge(
                          label: status,
                          color: isOccupied
                              ? AppColors.info
                              : AppColors.success,
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        if (unit['type'] != null)
                          Text(
                            unit['type'].toString(),
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                            ),
                          ),
                        if (unit['size'] != null) ...[
                          const Text(' | ',
                              style: TextStyle(
                                  color: AppColors.textMuted, fontSize: 12)),
                          Text(
                            '${unit['size']} sq ft',
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                            ),
                          ),
                        ],
                      ],
                    ),
                    if (unit['renterName'] != null && isOccupied) ...[
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          const Icon(Icons.person_outline,
                              size: 14, color: AppColors.textMuted),
                          const SizedBox(width: 4),
                          Text(
                            unit['renterName'],
                            style: const TextStyle(
                              fontSize: 12,
                              color: AppColors.textSecondary,
                            ),
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
              if (unit['annualRent'] != null)
                Text(
                  Formatters.currencyCompact(
                      (unit['annualRent'] as num).toDouble()),
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                    color: AppColors.primary,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

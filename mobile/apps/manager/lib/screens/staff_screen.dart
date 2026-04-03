import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _staffServiceProvider = Provider<StaffService>((ref) {
  final client = ref.watch(apiClientProvider);
  return StaffService(client.dio);
});

final _propertyServiceProvider = Provider<PropertyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PropertyService(client.dio);
});

final _staffProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_staffServiceProvider);
  return service.getStaff();
});

final _propertiesForFilterProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_propertyServiceProvider);
  return service.getProperties();
});

class StaffScreen extends ConsumerStatefulWidget {
  const StaffScreen({super.key});

  @override
  ConsumerState<StaffScreen> createState() => _StaffScreenState();
}

class _StaffScreenState extends ConsumerState<StaffScreen> {
  String _searchQuery = '';
  String? _selectedPropertyId;

  Future<void> _refresh() async {
    ref.invalidate(_staffProvider);
    ref.invalidate(_propertiesForFilterProvider);
  }

  Future<void> _deleteStaff(Map<String, dynamic> staff) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Staff'),
        content: Text(
            'Are you sure you want to delete ${staff['name'] ?? 'this staff member'}?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.danger,
            ),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await ref.read(_staffServiceProvider).deleteStaff(staff['id']);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Staff member deleted')),
        );
        _refresh();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to delete staff member')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final staffAsync = ref.watch(_staffProvider);
    final propertiesAsync = ref.watch(_propertiesForFilterProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Staff')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              onChanged: (v) => setState(() => _searchQuery = v.toLowerCase()),
              decoration: InputDecoration(
                hintText: 'Search staff...',
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
          propertiesAsync.when(
            loading: () => const SizedBox.shrink(),
            error: (_, __) => const SizedBox.shrink(),
            data: (properties) {
              if (properties.isEmpty) return const SizedBox.shrink();
              return SizedBox(
                height: 40,
                child: ListView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  children: [
                    FilterChip(
                      label: const Text('All'),
                      selected: _selectedPropertyId == null,
                      onSelected: (_) =>
                          setState(() => _selectedPropertyId = null),
                      selectedColor:
                          AppColors.primary.withValues(alpha: 0.15),
                    ),
                    const SizedBox(width: 8),
                    ...properties.map((p) => Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: FilterChip(
                            label: Text(p['name'] ?? 'Property'),
                            selected: _selectedPropertyId == p['id'],
                            onSelected: (_) => setState(
                                () => _selectedPropertyId = p['id']),
                            selectedColor:
                                AppColors.primary.withValues(alpha: 0.15),
                          ),
                        )),
                  ],
                ),
              );
            },
          ),
          const SizedBox(height: 8),
          Expanded(
            child: staffAsync.when(
              loading: () => const Center(
                child: CircularProgressIndicator(color: AppColors.primary),
              ),
              error: (e, _) => ErrorState(
                message: 'Failed to load staff',
                onRetry: _refresh,
              ),
              data: (staff) {
                final filtered = staff.where((s) {
                  final name =
                      (s['name'] ?? '').toString().toLowerCase();
                  final email =
                      (s['email'] ?? '').toString().toLowerCase();
                  final matchesSearch = name.contains(_searchQuery) ||
                      email.contains(_searchQuery);

                  if (_selectedPropertyId != null) {
                    final propertyIds =
                        (s['propertyIds'] as List<dynamic>?) ?? [];
                    if (!propertyIds.contains(_selectedPropertyId)) {
                      return false;
                    }
                  }

                  return matchesSearch;
                }).toList();

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.badge_outlined,
                    title: _searchQuery.isEmpty && _selectedPropertyId == null
                        ? 'No staff yet'
                        : 'No matching staff',
                    subtitle: _searchQuery.isEmpty && _selectedPropertyId == null
                        ? 'Add your first staff member'
                        : null,
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.primary,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(16, 0, 16, 80),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final member = filtered[index];
                      return AnimatedListItem(
                        index: index,
                        child: _StaffCard(
                          staff: member,
                          onTap: () =>
                              context.push('/staff/${member['id']}'),
                          onLongPress: () => _deleteStaff(member),
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
        onPressed: () => _showCreateStaffSheet(context),
        child: const Icon(Icons.person_add_outlined, color: Colors.white),
      ),
    );
  }

  void _showCreateStaffSheet(BuildContext context) {
    final nameCtrl = TextEditingController();
    final emailCtrl = TextEditingController();
    final phoneCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();
    String role = 'PROPERTY_MANAGER';

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
                  Text('New Staff Member',
                      style: Theme.of(ctx).textTheme.headlineSmall),
                  const SizedBox(height: 20),
                  TextFormField(
                    controller: nameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Full Name',
                      prefixIcon: Icon(Icons.person_outline),
                    ),
                    validator: (v) => v == null || v.trim().isEmpty
                        ? 'Name is required'
                        : null,
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: emailCtrl,
                    keyboardType: TextInputType.emailAddress,
                    decoration: const InputDecoration(
                      labelText: 'Email',
                      prefixIcon: Icon(Icons.email_outlined),
                    ),
                    validator: (v) {
                      if (v == null || v.trim().isEmpty) {
                        return 'Email is required';
                      }
                      if (!RegExp(r'^[^@]+@[^@]+\.[^@]+$')
                          .hasMatch(v.trim())) {
                        return 'Enter a valid email';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: phoneCtrl,
                    keyboardType: TextInputType.phone,
                    decoration: const InputDecoration(
                      labelText: 'Phone Number',
                      prefixIcon: Icon(Icons.phone_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  DropdownButtonFormField<String>(
                    value: role,
                    decoration: const InputDecoration(
                      labelText: 'Role',
                      prefixIcon: Icon(Icons.badge_outlined),
                    ),
                    items: const [
                      DropdownMenuItem(
                          value: 'PROPERTY_MANAGER',
                          child: Text('Property Manager')),
                      DropdownMenuItem(
                          value: 'TENANT_USER',
                          child: Text('Tenant User')),
                    ],
                    onChanged: (v) =>
                        setSheetState(() => role = v ?? 'PROPERTY_MANAGER'),
                  ),
                  const SizedBox(height: 24),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      onPressed: () async {
                        if (!formKey.currentState!.validate()) return;
                        final service = ref.read(_staffServiceProvider);
                        try {
                          await service.createStaff({
                            'name': nameCtrl.text.trim(),
                            'email': emailCtrl.text.trim(),
                            if (phoneCtrl.text.isNotEmpty)
                              'phone': phoneCtrl.text.trim(),
                            'role': role,
                          });
                          if (ctx.mounted) Navigator.pop(ctx);
                          _refresh();
                        } catch (e) {
                          if (ctx.mounted) {
                            ScaffoldMessenger.of(ctx).showSnackBar(
                              const SnackBar(
                                  content:
                                      Text('Failed to create staff member')),
                            );
                          }
                        }
                      },
                      child: const Text('Create Staff'),
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
}

class _StaffCard extends StatelessWidget {
  final Map<String, dynamic> staff;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  const _StaffCard({
    required this.staff,
    required this.onTap,
    required this.onLongPress,
  });

  Color _roleColor(String role) {
    switch (role) {
      case 'PROPERTY_MANAGER':
        return AppColors.primary;
      case 'TENANT_USER':
        return AppColors.info;
      default:
        return AppColors.textSecondary;
    }
  }

  String _roleLabel(String role) {
    switch (role) {
      case 'PROPERTY_MANAGER':
        return 'Property Manager';
      case 'TENANT_USER':
        return 'Tenant User';
      default:
        return role;
    }
  }

  @override
  Widget build(BuildContext context) {
    final name = staff['name'] ?? 'Unknown';
    final email = staff['email'] ?? '';
    final phone = staff['phone'] ?? staff['phoneNumber'] ?? '';
    final role = staff['role'] ?? '';
    final propertyIds = (staff['propertyIds'] as List<dynamic>?) ?? [];

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: InkWell(
        onTap: onTap,
        onLongPress: onLongPress,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              CircleAvatar(
                backgroundColor: AppColors.primary.withValues(alpha: 0.1),
                child: Text(
                  name.isNotEmpty ? name[0].toUpperCase() : '?',
                  style: const TextStyle(
                    color: AppColors.primary,
                    fontWeight: FontWeight.w700,
                  ),
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
                            name,
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 14,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        if (role.isNotEmpty) ...[
                          const SizedBox(width: 8),
                          StatusBadge(
                            label: _roleLabel(role),
                            color: _roleColor(role),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 4),
                    if (email.isNotEmpty)
                      Text(
                        email,
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textSecondary,
                        ),
                      ),
                    if (phone.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        phone,
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textMuted,
                        ),
                      ),
                    ],
                    if (propertyIds.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: AppColors.accent.withValues(alpha: 0.1),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          '${propertyIds.length} ${propertyIds.length == 1 ? 'property' : 'properties'}',
                          style: const TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.w600,
                            color: AppColors.accent,
                          ),
                        ),
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

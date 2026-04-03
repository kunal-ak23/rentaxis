import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _vendorServiceProvider = Provider<VendorService>((ref) {
  final client = ref.watch(apiClientProvider);
  return VendorService(client.dio);
});

final _vendorsProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_vendorServiceProvider);
  return service.getVendors();
});

class VendorsScreen extends ConsumerStatefulWidget {
  const VendorsScreen({super.key});

  @override
  ConsumerState<VendorsScreen> createState() => _VendorsScreenState();
}

class _VendorsScreenState extends ConsumerState<VendorsScreen> {
  String _searchQuery = '';

  Future<void> _refresh() async {
    ref.invalidate(_vendorsProvider);
  }

  @override
  Widget build(BuildContext context) {
    final vendorsAsync = ref.watch(_vendorsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Vendors')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: TextField(
              onChanged: (v) => setState(() => _searchQuery = v.toLowerCase()),
              decoration: InputDecoration(
                hintText: 'Search vendors...',
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
            child: vendorsAsync.when(
              loading: () => const Center(
                child: CircularProgressIndicator(color: AppColors.primary),
              ),
              error: (e, _) => ErrorState(
                message: 'Failed to load vendors',
                onRetry: _refresh,
              ),
              data: (vendors) {
                final filtered = vendors.where((v) {
                  final name =
                      (v['name'] ?? '').toString().toLowerCase();
                  final email =
                      (v['email'] ?? '').toString().toLowerCase();
                  final phone =
                      (v['phone'] ?? '').toString().toLowerCase();
                  return name.contains(_searchQuery) ||
                      email.contains(_searchQuery) ||
                      phone.contains(_searchQuery);
                }).toList();

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.store_outlined,
                    title: _searchQuery.isEmpty
                        ? 'No vendors yet'
                        : 'No matching vendors',
                    subtitle: _searchQuery.isEmpty
                        ? 'Add your first vendor'
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
                      final vendor = filtered[index];
                      return _VendorCard(
                        vendor: vendor,
                        onTap: () =>
                            context.push('/vendors/${vendor['id']}'),
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
        onPressed: () => _showCreateVendorSheet(context),
        child: const Icon(Icons.add, color: Colors.white),
      ),
    );
  }

  void _showCreateVendorSheet(BuildContext context) {
    final nameCtrl = TextEditingController();
    final emailCtrl = TextEditingController();
    final phoneCtrl = TextEditingController();
    final addressCtrl = TextEditingController();
    final trnCtrl = TextEditingController();
    final formKey = GlobalKey<FormState>();

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
                  Text('New Vendor',
                      style: Theme.of(ctx).textTheme.headlineSmall),
                  const SizedBox(height: 20),
                  TextFormField(
                    controller: nameCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Name',
                      prefixIcon: Icon(Icons.store_outlined),
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
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: phoneCtrl,
                    keyboardType: TextInputType.phone,
                    decoration: const InputDecoration(
                      labelText: 'Phone',
                      prefixIcon: Icon(Icons.phone_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: addressCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Address',
                      prefixIcon: Icon(Icons.location_on_outlined),
                    ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    controller: trnCtrl,
                    decoration: const InputDecoration(
                      labelText: 'TRN (Tax Registration Number)',
                      prefixIcon: Icon(Icons.receipt_long_outlined),
                    ),
                  ),
                  const SizedBox(height: 24),
                  SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      onPressed: () async {
                        if (!formKey.currentState!.validate()) return;
                        final service = ref.read(_vendorServiceProvider);
                        try {
                          await service.createVendor({
                            'name': nameCtrl.text.trim(),
                            if (emailCtrl.text.isNotEmpty)
                              'email': emailCtrl.text.trim(),
                            if (phoneCtrl.text.isNotEmpty)
                              'phone': phoneCtrl.text.trim(),
                            if (addressCtrl.text.isNotEmpty)
                              'address': addressCtrl.text.trim(),
                            if (trnCtrl.text.isNotEmpty)
                              'trn': trnCtrl.text.trim(),
                          });
                          if (ctx.mounted) Navigator.pop(ctx);
                          _refresh();
                        } catch (e) {
                          if (ctx.mounted) {
                            ScaffoldMessenger.of(ctx).showSnackBar(
                              const SnackBar(
                                  content:
                                      Text('Failed to create vendor')),
                            );
                          }
                        }
                      },
                      child: const Text('Create Vendor'),
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

class _VendorCard extends StatelessWidget {
  final Map<String, dynamic> vendor;
  final VoidCallback onTap;

  const _VendorCard({required this.vendor, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final name = vendor['name'] ?? 'Unknown';
    final email = vendor['email'] ?? '';
    final phone = vendor['phone'] ?? '';
    final trn = vendor['trn'] ?? '';

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              CircleAvatar(
                backgroundColor: AppColors.primary.withValues(alpha: 0.1),
                child: name.isNotEmpty
                    ? Text(
                        name[0].toUpperCase(),
                        style: const TextStyle(
                          color: AppColors.primary,
                          fontWeight: FontWeight.w700,
                        ),
                      )
                    : const Icon(Icons.store_outlined,
                        color: AppColors.primary, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name,
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 14,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 4),
                    if (email.toString().isNotEmpty)
                      Text(
                        email.toString(),
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textSecondary,
                        ),
                      ),
                    if (phone.toString().isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        phone.toString(),
                        style: const TextStyle(
                          fontSize: 12,
                          color: AppColors.textMuted,
                        ),
                      ),
                    ],
                    if (trn.toString().isNotEmpty) ...[
                      const SizedBox(height: 2),
                      Text(
                        'TRN: ${trn.toString()}',
                        style: const TextStyle(
                          fontSize: 11,
                          color: AppColors.textMuted,
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

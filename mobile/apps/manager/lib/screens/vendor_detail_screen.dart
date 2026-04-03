import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _vendorServiceProvider = Provider<VendorService>((ref) {
  final client = ref.watch(apiClientProvider);
  return VendorService(client.dio);
});

class VendorDetailScreen extends ConsumerStatefulWidget {
  final String vendorId;
  const VendorDetailScreen({super.key, required this.vendorId});

  @override
  ConsumerState<VendorDetailScreen> createState() =>
      _VendorDetailScreenState();
}

class _VendorDetailScreenState extends ConsumerState<VendorDetailScreen> {
  Map<String, dynamic>? _vendor;
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
      final service = ref.read(_vendorServiceProvider);
      final vendor = await service.getVendorById(widget.vendorId);
      if (!mounted) return;
      setState(() {
        _vendor = vendor;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = 'Failed to load vendor details';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Vendor Details')),
        body: const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
      );
    }

    if (_error != null || _vendor == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Vendor Details')),
        body: ErrorState(message: _error ?? 'Not found', onRetry: _loadData),
      );
    }

    final vendor = _vendor!;
    final name = vendor['name'] ?? 'Unknown';
    final email = (vendor['email'] ?? '').toString();
    final phone = (vendor['phone'] ?? '').toString();
    final address = (vendor['address'] ?? '').toString();
    final trn = (vendor['trn'] ?? '').toString();

    return Scaffold(
      appBar: AppBar(
        title: const Text('Vendor Details'),
      ),
      body: RefreshIndicator(
        onRefresh: _loadData,
        color: AppColors.primary,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 80),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Vendor header card
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
                  children: [
                    Container(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: Colors.white.withValues(alpha: 0.1),
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(Icons.store_rounded,
                          color: AppColors.accent, size: 36),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      name,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 20,
                        fontWeight: FontWeight.w700,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 20),

              // Detail info section
              Text('Details',
                  style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: 12),

              if (email.isNotEmpty)
                _DetailRow(
                  icon: Icons.email_outlined,
                  label: 'Email',
                  value: email,
                ),
              if (phone.isNotEmpty)
                _DetailRow(
                  icon: Icons.phone_outlined,
                  label: 'Phone',
                  value: phone,
                ),
              if (address.isNotEmpty)
                _DetailRow(
                  icon: Icons.location_on_outlined,
                  label: 'Address',
                  value: address,
                ),
              if (trn.isNotEmpty)
                _DetailRow(
                  icon: Icons.receipt_long_outlined,
                  label: 'TRN',
                  value: trn,
                ),

              if (email.isEmpty &&
                  phone.isEmpty &&
                  address.isEmpty &&
                  trn.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 24),
                  child: Center(
                    child: Text(
                      'No additional details available',
                      style: TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 14,
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;

  const _DetailRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: AppColors.primary),
          const SizedBox(width: 12),
          Expanded(
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
                const SizedBox(height: 2),
                Text(
                  value,
                  style: const TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

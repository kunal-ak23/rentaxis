import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _settlementServiceProvider = Provider<SettlementService>((ref) {
  final client = ref.watch(apiClientProvider);
  return SettlementService(client.dio);
});

class LeaseSettlementScreen extends ConsumerStatefulWidget {
  final String leaseId;
  const LeaseSettlementScreen({super.key, required this.leaseId});

  @override
  ConsumerState<LeaseSettlementScreen> createState() =>
      _LeaseSettlementScreenState();
}

class _LeaseSettlementScreenState extends ConsumerState<LeaseSettlementScreen> {
  Map<String, dynamic>? _preview;
  Map<String, dynamic>? _settlement;
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
      final service = ref.read(_settlementServiceProvider);
      try {
        final settlement = await service.getSettlement(widget.leaseId);
        if (!mounted) return;
        setState(() {
          _settlement = settlement;
          _isLoading = false;
        });
      } catch (_) {
        // No settlement yet, load preview
        try {
          final preview = await service.getSettlementPreview(widget.leaseId);
          if (!mounted) return;
          setState(() {
            _preview = preview;
            _isLoading = false;
          });
        } catch (e) {
          if (!mounted) return;
          setState(() {
            _error = 'Failed to load settlement data';
            _isLoading = false;
          });
        }
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = 'Failed to load settlement data';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Settlement'),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return const Center(
        child: CircularProgressIndicator(color: AppColors.primary),
      );
    }

    if (_error != null) {
      return ErrorState(message: _error!, onRetry: _loadData);
    }

    if (_settlement == null && _preview == null) {
      return ErrorState(
        message: 'No settlement data available',
        onRetry: _loadData,
      );
    }

    return RefreshIndicator(
      onRefresh: _loadData,
      color: AppColors.primary,
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (_settlement != null)
              _buildSettledView(_settlement!)
            else
              _buildPreviewView(_preview!),
          ],
        ),
      ),
    );
  }

  Widget _buildSettledView(Map<String, dynamic> settlement) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.check_circle, color: AppColors.success, size: 24),
            const SizedBox(width: 8),
            Text(
              'Settlement Complete',
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    color: AppColors.success,
                    fontWeight: FontWeight.w700,
                  ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        _buildSummaryCard(settlement),
      ],
    );
  }

  Widget _buildPreviewView(Map<String, dynamic> preview) {
    final lineItems = preview['lineItems'] as List<dynamic>? ?? [];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Icon(Icons.info, color: AppColors.primary, size: 24),
            const SizedBox(width: 8),
            Text(
              'Settlement Preview',
              style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w700,
                  ),
            ),
          ],
        ),
        const SizedBox(height: 20),
        _buildSummaryCard(preview),
        if (lineItems.isNotEmpty) ...[
          const SizedBox(height: 20),
          Text(
            'Line Items',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 12),
          ...lineItems.map((item) => _buildLineItem(item)),
        ],
        const SizedBox(height: 20),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: AppColors.primary.withValues(alpha: 0.08),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: AppColors.primary.withValues(alpha: 0.2),
            ),
          ),
          child: Row(
            children: [
              const Icon(Icons.info_outline,
                  size: 18, color: AppColors.primary),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  'This is a preview. Settlements are finalized via the web dashboard.',
                  style: TextStyle(
                    fontSize: 13,
                    color: AppColors.primary.withValues(alpha: 0.9),
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildSummaryCard(Map<String, dynamic> data) {
    return Container(
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
          _buildSummaryRow(
            'Total Rent Paid',
            Formatters.currency(
              (data['totalRentPaid'] ?? 0).toDouble(),
            ),
          ),
          const SizedBox(height: 12),
          _buildSummaryRow(
            'Outstanding Balance',
            Formatters.currency(
              (data['outstandingBalance'] ?? 0).toDouble(),
            ),
          ),
          const SizedBox(height: 12),
          _buildSummaryRow(
            'Penalties Total',
            Formatters.currency(
              (data['penaltiesTotal'] ?? data['totalPenalties'] ?? 0)
                  .toDouble(),
            ),
          ),
          const SizedBox(height: 12),
          _buildSummaryRow(
            'Security Deposit',
            Formatters.currency(
              (data['securityDeposit'] ?? 0).toDouble(),
            ),
          ),
          const Divider(color: Colors.white24, height: 24),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                'Final Settlement Amount',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
              Text(
                Formatters.currency(
                  (data['finalSettlementAmount'] ??
                          data['settlementAmount'] ??
                          0)
                      .toDouble(),
                ),
                style: const TextStyle(
                  color: AppColors.accent,
                  fontWeight: FontWeight.w700,
                  fontSize: 18,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildSummaryRow(String label, String value) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(
          label,
          style: const TextStyle(color: Colors.white70, fontSize: 13),
        ),
        Text(
          value,
          style: const TextStyle(
            color: Colors.white,
            fontWeight: FontWeight.w600,
            fontSize: 14,
          ),
        ),
      ],
    );
  }

  Widget _buildLineItem(Map<String, dynamic> item) {
    final amount = (item['amount'] ?? 0).toDouble();
    final isPositive = amount >= 0;

    return Container(
      margin: const EdgeInsets.only(bottom: 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              item['description'] ?? '',
              style: const TextStyle(fontSize: 14),
            ),
          ),
          Text(
            Formatters.currency(amount),
            style: TextStyle(
              fontWeight: FontWeight.w600,
              fontSize: 14,
              color: isPositive ? AppColors.success : AppColors.danger,
            ),
          ),
        ],
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _penaltyServiceProvider = Provider<PenaltyService>((ref) {
  final client = ref.watch(apiClientProvider);
  return PenaltyService(client.dio);
});

class LeasePenaltiesScreen extends ConsumerStatefulWidget {
  final String leaseId;
  const LeasePenaltiesScreen({super.key, required this.leaseId});

  @override
  ConsumerState<LeasePenaltiesScreen> createState() =>
      _LeasePenaltiesScreenState();
}

class _LeasePenaltiesScreenState extends ConsumerState<LeasePenaltiesScreen> {
  List<dynamic> _penalties = [];
  bool _isLoading = true;
  bool _isActioning = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _loadPenalties();
  }

  Future<void> _loadPenalties() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final service = ref.read(_penaltyServiceProvider);
      final penalties = await service.getPenalties(widget.leaseId);
      if (!mounted) return;
      setState(() {
        _penalties = penalties;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = 'Failed to load penalties';
        _isLoading = false;
      });
    }
  }

  Future<void> _recalculatePenalties() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Recalculate Penalties'),
        content: const Text(
          'This will recalculate all penalties for this lease based on current payment statuses. Continue?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Recalculate'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _isActioning = true);
    try {
      await ref.read(_penaltyServiceProvider).recalculatePenalties(
            widget.leaseId,
          );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Penalties recalculated')),
        );
        _loadPenalties();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to recalculate penalties')),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Future<void> _waivePenalty(String penaltyId) async {
    final reasonCtrl = TextEditingController();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Waive Penalty'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('Are you sure you want to waive this penalty?'),
            const SizedBox(height: 12),
            TextField(
              controller: reasonCtrl,
              maxLines: 2,
              decoration: const InputDecoration(
                hintText: 'Reason (optional)',
              ),
            ),
          ],
        ),
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
            child: const Text('Waive'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _isActioning = true);
    try {
      await ref.read(_penaltyServiceProvider).waivePenalty(
            penaltyId,
            reason: reasonCtrl.text.isNotEmpty ? reasonCtrl.text : null,
          );
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Penalty waived')),
        );
        _loadPenalties();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to waive penalty')),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Penalties'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _recalculatePenalties,
            tooltip: 'Recalculate',
          ),
        ],
      ),
      body: LoadingOverlay(
        isLoading: _isActioning,
        child: _buildBody(),
      ),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return const Center(
        child: CircularProgressIndicator(color: AppColors.primary),
      );
    }

    if (_error != null) {
      return ErrorState(message: _error!, onRetry: _loadPenalties);
    }

    if (_penalties.isEmpty) {
      return EmptyState(
        icon: Icons.gavel,
        title: 'No penalties',
        subtitle: 'No penalties for this lease',
      );
    }

    return RefreshIndicator(
      onRefresh: _loadPenalties,
      color: AppColors.primary,
      child: ListView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        itemCount: _penalties.length,
        itemBuilder: (context, index) {
          final penalty = _penalties[index];
          return _buildPenaltyCard(penalty);
        },
      ),
    );
  }

  Widget _buildPenaltyCard(Map<String, dynamic> penalty) {
    final status = (penalty['status'] ?? 'ACTIVE').toString().toUpperCase();
    final isActive = status == 'ACTIVE';
    final borderColor = isActive ? AppColors.danger : AppColors.textMuted;

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 4,
            height: 80,
            decoration: BoxDecoration(
              color: borderColor,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        penalty['penaltyType'] ?? penalty['type'] ?? 'Penalty',
                        style: const TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 14,
                        ),
                      ),
                    ),
                    Text(
                      Formatters.currency(
                        (penalty['amount'] ?? 0).toDouble(),
                      ),
                      style: TextStyle(
                        fontWeight: FontWeight.w700,
                        fontSize: 14,
                        color: isActive ? AppColors.danger : AppColors.textMuted,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Row(
                  children: [
                    StatusBadge(
                      label: status,
                      color: isActive ? AppColors.danger : AppColors.textMuted,
                    ),
                  ],
                ),
                if (penalty['paymentNumber'] != null ||
                    penalty['dueDate'] != null) ...[
                  const SizedBox(height: 6),
                  Text(
                    [
                      if (penalty['paymentNumber'] != null)
                        'Payment #${penalty['paymentNumber']}',
                      if (penalty['dueDate'] != null)
                        'Due: ${Formatters.date(penalty['dueDate'])}',
                    ].join(' - '),
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppColors.textSecondary,
                    ),
                  ),
                ],
                const SizedBox(height: 4),
                Text(
                  Formatters.date(penalty['createdAt']),
                  style: const TextStyle(
                    fontSize: 11,
                    color: AppColors.textMuted,
                  ),
                ),
                if (isActive) ...[
                  const SizedBox(height: 8),
                  TextButton.icon(
                    onPressed: () => _waivePenalty(penalty['id']),
                    icon: const Icon(
                      Icons.cancel_outlined,
                      size: 16,
                      color: AppColors.danger,
                    ),
                    label: const Text(
                      'Waive',
                      style: TextStyle(color: AppColors.danger),
                    ),
                    style: TextButton.styleFrom(
                      padding: EdgeInsets.zero,
                      minimumSize: const Size(0, 32),
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

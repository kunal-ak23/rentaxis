import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _meetingServiceProvider = Provider<MeetingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return MeetingService(client.dio);
});

class MeetingDetailScreen extends ConsumerStatefulWidget {
  final String meetingId;
  const MeetingDetailScreen({super.key, required this.meetingId});

  @override
  ConsumerState<MeetingDetailScreen> createState() =>
      _MeetingDetailScreenState();
}

class _MeetingDetailScreenState extends ConsumerState<MeetingDetailScreen> {
  Map<String, dynamic>? _meeting;
  bool _isLoading = true;
  bool _isActioning = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final svc = ref.read(_meetingServiceProvider);
      final data = await svc.getMeeting(widget.meetingId);
      if (!mounted) return;
      setState(() {
        _meeting = data;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = 'Failed to load meeting';
        _isLoading = false;
      });
    }
  }

  Future<void> _action(String label, Future<Map<String, dynamic>> Function() fn) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(label),
        content: Text('Are you sure you want to $label this meeting?'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          ElevatedButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Confirm')),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _isActioning = true);
    try {
      await fn();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$label successful')),
        );
        _load();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Failed to $label meeting')),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  String _formatSlot(String? slotStart) {
    if (slotStart == null) return '-';
    try {
      final dt = DateTime.parse(slotStart).toLocal();
      final months = [
        'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
        'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'
      ];
      final hour = dt.hour % 12 == 0 ? 12 : dt.hour % 12;
      final minute = dt.minute.toString().padLeft(2, '0');
      final ampm = dt.hour < 12 ? 'AM' : 'PM';
      return '${months[dt.month - 1]} ${dt.day}, ${dt.year} · $hour:$minute $ampm';
    } catch (_) {
      return slotStart;
    }
  }

  Color _statusColor(String status) {
    switch (status) {
      case 'APPROVED':
        return AppColors.success;
      case 'CANCELLED':
        return AppColors.danger;
      case 'COMPLETED':
        return AppColors.primary;
      case 'NO_SHOW':
        return AppColors.warning;
      default:
        return AppColors.textMuted;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Meeting')),
        body: const Center(
            child: CircularProgressIndicator(color: AppColors.primary)),
      );
    }
    if (_error != null || _meeting == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Meeting')),
        body: ErrorState(message: _error ?? 'Not found', onRetry: _load),
      );
    }

    final m = _meeting!;
    final status = m['status'] ?? 'REQUESTED';
    final purpose = m['purpose'] ?? '';
    final svc = ref.read(_meetingServiceProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Meeting Details'),
        actions: [
          if (status == 'REQUESTED')
            PopupMenuButton<String>(
              onSelected: (v) {
                if (v == 'approve') {
                  _action('Approve', () => svc.approveMeeting(widget.meetingId));
                } else if (v == 'cancel') {
                  _action('Cancel', () => svc.cancelMeeting(widget.meetingId));
                }
              },
              itemBuilder: (_) => [
                const PopupMenuItem(
                  value: 'approve',
                  child: Row(children: [
                    Icon(Icons.check_circle_outline, color: AppColors.success, size: 18),
                    SizedBox(width: 8),
                    Text('Approve'),
                  ]),
                ),
                const PopupMenuItem(
                  value: 'cancel',
                  child: Row(children: [
                    Icon(Icons.cancel_outlined, color: AppColors.danger, size: 18),
                    SizedBox(width: 8),
                    Text('Cancel'),
                  ]),
                ),
              ],
            ),
          if (status == 'APPROVED')
            PopupMenuButton<String>(
              onSelected: (v) {
                if (v == 'complete') {
                  _action('Complete', () => svc.completeMeeting(widget.meetingId));
                } else if (v == 'no-show') {
                  _action('No-Show', () => svc.noShowMeeting(widget.meetingId));
                } else if (v == 'cancel') {
                  _action('Cancel', () => svc.cancelMeeting(widget.meetingId));
                }
              },
              itemBuilder: (_) => [
                const PopupMenuItem(
                  value: 'complete',
                  child: Row(children: [
                    Icon(Icons.done_all_rounded, color: AppColors.success, size: 18),
                    SizedBox(width: 8),
                    Text('Mark Complete'),
                  ]),
                ),
                const PopupMenuItem(
                  value: 'no-show',
                  child: Row(children: [
                    Icon(Icons.person_off_outlined, color: AppColors.warning, size: 18),
                    SizedBox(width: 8),
                    Text('No-Show'),
                  ]),
                ),
                const PopupMenuItem(
                  value: 'cancel',
                  child: Row(children: [
                    Icon(Icons.cancel_outlined, color: AppColors.danger, size: 18),
                    SizedBox(width: 8),
                    Text('Cancel'),
                  ]),
                ),
              ],
            ),
        ],
      ),
      body: LoadingOverlay(
        isLoading: _isActioning,
        child: RefreshIndicator(
          onRefresh: _load,
          color: AppColors.primary,
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Header card
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                        colors: [AppColors.navyDark, Color(0xFF1A3352)]),
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              m['title'] ?? _purposeLabel(purpose),
                              style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 18,
                                  fontWeight: FontWeight.w700),
                            ),
                          ),
                          Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 10, vertical: 4),
                            decoration: BoxDecoration(
                              color: _statusColor(status).withValues(alpha: 0.25),
                              borderRadius: BorderRadius.circular(10),
                            ),
                            child: Text(
                              status,
                              style: TextStyle(
                                  color: _statusColor(status),
                                  fontWeight: FontWeight.w700,
                                  fontSize: 12),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      _InfoRow(
                          icon: Icons.calendar_today_outlined,
                          text: _formatSlot(m['slotStart']?.toString())),
                      const SizedBox(height: 6),
                      _InfoRow(
                          icon: Icons.category_outlined,
                          text: '${_purposeLabel(purpose)} · ${m['type'] == 'PROPERTY_VISIT' ? 'On-site' : 'Office'}'),
                    ],
                  ),
                ),
                const SizedBox(height: 20),

                // People
                _SectionCard(
                  title: 'People',
                  child: Column(
                    children: [
                      _DetailRow(
                          label: 'Requester', value: m['requesterName'] ?? '-'),
                      _DetailRow(
                          label: 'Host', value: m['hostName'] ?? '-'),
                    ],
                  ),
                ),

                // Context
                if (m['propertyName'] != null || m['leaseLabel'] != null) ...[
                  const SizedBox(height: 12),
                  _SectionCard(
                    title: 'Context',
                    child: Column(
                      children: [
                        if (m['propertyName'] != null)
                          _DetailRow(
                              label: 'Property', value: m['propertyName']),
                        if (m['unitNumber'] != null)
                          _DetailRow(label: 'Unit', value: m['unitNumber']),
                        if (m['leaseLabel'] != null)
                          _DetailRow(label: 'Lease', value: m['leaseLabel']),
                      ],
                    ),
                  ),
                ],

                // Purpose-specific details
                if (m['details'] != null) ...[
                  const SizedBox(height: 12),
                  _buildDetails(purpose, m['details'] as Map<String, dynamic>),
                ],

                // Notes
                if ((m['notes'] ?? '').isNotEmpty) ...[
                  const SizedBox(height: 12),
                  _SectionCard(
                    title: 'Notes',
                    child: Text(m['notes'],
                        style: const TextStyle(
                            color: AppColors.textSecondary, fontSize: 14)),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildDetails(String purpose, Map<String, dynamic> details) {
    if (purpose == 'LEASE_RENEWAL') {
      final start = details['proposedStartDate'];
      final end = details['proposedEndDate'];
      int? months;
      if (start != null && end != null) {
        try {
          final s = DateTime.parse(start);
          final e = DateTime.parse(end);
          months = (e.year - s.year) * 12 + (e.month - s.month);
        } catch (_) {}
      }
      return _SectionCard(
        title: 'Renewal Details',
        child: Column(
          children: [
            if (start != null)
              _DetailRow(label: 'Proposed Start', value: start),
            if (months != null)
              _DetailRow(label: 'Duration', value: '$months months'),
            if ((details['notes'] ?? '').isNotEmpty)
              _DetailRow(label: 'Notes', value: details['notes']),
          ],
        ),
      );
    }
    if (purpose == 'CHEQUE_REPLACEMENT') {
      final ids = details['paymentScheduleIds'] as List<dynamic>?;
      return _SectionCard(
        title: 'Cheque Replacement Details',
        child: Column(
          children: [
            if (ids != null && ids.isNotEmpty)
              _DetailRow(label: 'Cheques', value: '${ids.length} cheque(s)'),
            if ((details['notes'] ?? '').isNotEmpty)
              _DetailRow(label: 'Notes', value: details['notes']),
          ],
        ),
      );
    }
    return const SizedBox.shrink();
  }

  String _purposeLabel(String p) {
    switch (p) {
      case 'PROPERTY_VIEWING':
        return 'Property Viewing';
      case 'LEASE_RENEWAL':
        return 'Lease Renewal';
      case 'CHEQUE_REPLACEMENT':
        return 'Cheque Replacement';
      default:
        return 'Meeting';
    }
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
        Icon(icon, size: 14, color: Colors.white60),
        const SizedBox(width: 6),
        Expanded(
          child: Text(text,
              style: const TextStyle(color: Colors.white70, fontSize: 13)),
        ),
      ],
    );
  }
}

class _SectionCard extends StatelessWidget {
  final String title;
  final Widget child;
  const _SectionCard({required this.title, required this.child});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style: const TextStyle(
                  fontWeight: FontWeight.w700,
                  fontSize: 13,
                  color: AppColors.textMuted)),
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  final String label;
  final String value;
  const _DetailRow({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 110,
            child: Text(label,
                style: const TextStyle(
                    color: AppColors.textMuted, fontSize: 13)),
          ),
          Expanded(
            child: Text(value,
                style: const TextStyle(
                    fontWeight: FontWeight.w600, fontSize: 13)),
          ),
        ],
      ),
    );
  }
}

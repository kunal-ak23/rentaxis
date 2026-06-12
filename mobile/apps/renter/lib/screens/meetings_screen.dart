import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _meetingServiceProvider = Provider<MeetingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return MeetingService(client.dio);
});

class MeetingsScreen extends ConsumerStatefulWidget {
  const MeetingsScreen({super.key});

  @override
  ConsumerState<MeetingsScreen> createState() => _MeetingsScreenState();
}

class _MeetingsScreenState extends ConsumerState<MeetingsScreen> {
  List<dynamic> _meetings = [];
  bool _isLoading = true;
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
      final data = await svc.listMyMeetings(perspective: 'requester');
      if (!mounted) return;
      setState(() {
        _meetings = data;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = 'Failed to load meetings';
        _isLoading = false;
      });
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
      return '${months[dt.month - 1]} ${dt.day} · $hour:$minute $ampm';
    } catch (_) {
      return slotStart;
    }
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Meetings'),
        titleSpacing: 20,
        actions: [
          IconButton(
            icon: const Icon(Icons.add),
            onPressed: () =>
                context.push('/meetings/create').then((_) => _load()),
          ),
        ],
      ),
      body: _isLoading
          ? const Center(
              child: CircularProgressIndicator(color: AppColors.primary))
          : _error != null
              ? ErrorState(message: _error!, onRetry: _load)
              : _meetings.isEmpty
                  ? EmptyState(
                      title: 'No meetings yet',
                      subtitle: 'Tap + to request a meeting',
                      icon: Icons.event_outlined,
                      actionLabel: 'Request Meeting',
                      onAction: () => context
                          .push('/meetings/create')
                          .then((_) => _load()),
                    )
                  : RefreshIndicator(
                      onRefresh: _load,
                      color: AppColors.primary,
                      child: ListView.separated(
                        padding:
                            EdgeInsets.fromLTRB(16, 12, 16, AppInsets.bottomNav(context)),
                        itemCount: _meetings.length,
                        separatorBuilder: (_, __) =>
                            const SizedBox(height: 10),
                        itemBuilder: (_, i) {
                          final m = _meetings[i] as Map<String, dynamic>;
                          final status = m['status'] ?? 'REQUESTED';
                          final purpose = m['purpose'] ?? '';
                          return InkWell(
                            onTap: () =>
                                context.push('/meetings/${m['id']}'),
                            borderRadius: BorderRadius.circular(16),
                            child: Container(
                              padding: const EdgeInsets.all(16),
                              decoration: BoxDecoration(
                                color: AppColors.surface,
                                borderRadius: BorderRadius.circular(16),
                                boxShadow: AppShadows.soft,
                              ),
                              child: Row(
                                crossAxisAlignment:
                                    CrossAxisAlignment.start,
                                children: [
                                  Container(
                                    width: 44,
                                    height: 44,
                                    decoration: BoxDecoration(
                                      color: AppColors.primary
                                          .withValues(alpha: 0.1),
                                      borderRadius:
                                          BorderRadius.circular(12),
                                    ),
                                    child: const Icon(
                                        Icons.event_outlined,
                                        color: AppColors.primary,
                                        size: 22),
                                  ),
                                  const SizedBox(width: 12),
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Row(
                                          children: [
                                            Expanded(
                                              child: Text(
                                                m['title'] ??
                                                    _purposeLabel(purpose),
                                                style: const TextStyle(
                                                    fontWeight:
                                                        FontWeight.w700,
                                                    fontSize: 14),
                                                maxLines: 1,
                                                overflow:
                                                    TextOverflow.ellipsis,
                                              ),
                                            ),
                                            const SizedBox(width: 8),
                                            Container(
                                              padding:
                                                  const EdgeInsets.symmetric(
                                                      horizontal: 7,
                                                      vertical: 2),
                                              decoration: BoxDecoration(
                                                color: _statusColor(status)
                                                    .withValues(alpha: 0.12),
                                                borderRadius:
                                                    BorderRadius.circular(6),
                                              ),
                                              child: Text(
                                                status,
                                                style: TextStyle(
                                                    fontSize: 10,
                                                    fontWeight:
                                                        FontWeight.w700,
                                                    color: _statusColor(
                                                        status)),
                                              ),
                                            ),
                                          ],
                                        ),
                                        const SizedBox(height: 4),
                                        Text(
                                          _formatSlot(m['slotStart']
                                              ?.toString()),
                                          style: const TextStyle(
                                              color: AppColors.textMuted,
                                              fontSize: 12),
                                        ),
                                        if (m['hostName'] != null) ...[
                                          const SizedBox(height: 2),
                                          Text(
                                            'With ${m['hostName']}',
                                            style: const TextStyle(
                                                color: AppColors.textMuted,
                                                fontSize: 12),
                                          ),
                                        ],
                                      ],
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          );
                        },
                      ),
                    ),
    );
  }
}

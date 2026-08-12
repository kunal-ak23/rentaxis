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
  bool _isCancelling = false;
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
      final data = await ref
          .read(_meetingServiceProvider)
          .getMeeting(widget.meetingId);
      if (!mounted) return;
      setState(() {
        _meeting = data;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = _L(context.isAr).failedToLoad;
        _isLoading = false;
      });
    }
  }

  Future<void> _cancel() async {
    final l = _L(context.isAr);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(l.cancelMeetingTitle),
        content: Text(l.cancelMeetingConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.no),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(backgroundColor: AppColors.danger),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(
              l.yesCancel,
              style: const TextStyle(color: Colors.white),
            ),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _isCancelling = true);
    try {
      await ref.read(_meetingServiceProvider).cancelMeeting(widget.meetingId);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.meetingCancelled)));
        _load();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.failedToCancel)));
      }
    } finally {
      if (mounted) setState(() => _isCancelling = false);
    }
  }

  Color _statusColor(String status) {
    final m = context.miftah;
    switch (status) {
      case 'APPROVED':
        return m.success;
      case 'CANCELLED':
        return m.danger;
      case 'COMPLETED':
        return AppColors.primary;
      case 'NO_SHOW':
        return m.warning;
      default:
        return m.textMuted;
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = _L(context.isAr);
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: Text(l.meeting)),
        body: const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
      );
    }
    if (_error != null || _meeting == null) {
      return Scaffold(
        appBar: AppBar(title: Text(l.meeting)),
        body: ErrorState(message: _error ?? l.notFound, onRetry: _load),
      );
    }

    final m = context.miftah;
    final meeting = _meeting!;
    final status = meeting['status'] ?? 'REQUESTED';
    final purpose = meeting['purpose'] ?? '';
    final canCancel = status == 'REQUESTED' || status == 'APPROVED';

    return Scaffold(
      appBar: AppBar(title: Text(l.meetingDetails)),
      body: LoadingOverlay(
        isLoading: _isCancelling,
        child: RefreshIndicator(
          onRefresh: _load,
          color: AppColors.primary,
          child: SingleChildScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: EdgeInsets.fromLTRB(
              16,
              16,
              16,
              24,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
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
                          Expanded(
                            child: Text(
                              meeting['title'] ?? l.purposeLabel(purpose),
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 18,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 10,
                              vertical: 4,
                            ),
                            decoration: BoxDecoration(
                              color: _statusColor(
                                status,
                              ).withValues(alpha: 0.25),
                              borderRadius: BorderRadius.circular(10),
                            ),
                            child: Text(
                              l.statusLabel(status),
                              style: TextStyle(
                                color: _statusColor(status),
                                fontWeight: FontWeight.w700,
                                fontSize: 12,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          const Icon(
                            Icons.calendar_today_outlined,
                            size: 14,
                            color: Colors.white60,
                          ),
                          const SizedBox(width: 6),
                          Text(
                            l.formatSlot(meeting['slotStart']?.toString()),
                            style: const TextStyle(
                              color: Colors.white70,
                              fontSize: 13,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      Row(
                        children: [
                          const Icon(
                            Icons.person_outline_rounded,
                            size: 14,
                            color: Colors.white60,
                          ),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              l.withHost(meeting['hostName'] ?? '-'),
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: Colors.white70,
                                fontSize: 13,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 20),

                _InfoCard(
                  title: l.details,
                  rows: [
                    (
                      l.type,
                      meeting['type'] == 'PROPERTY_VISIT'
                          ? l.onSiteVisit
                          : l.officeVisit,
                    ),
                    (l.purpose, l.purposeLabel(purpose)),
                    if (meeting['propertyName'] != null)
                      (l.property, meeting['propertyName'] as String),
                    if (meeting['unitNumber'] != null)
                      (l.unit, meeting['unitNumber'] as String),
                    if (meeting['leaseLabel'] != null)
                      (l.lease, meeting['leaseLabel'] as String),
                  ],
                ),

                if ((meeting['notes'] ?? '').isNotEmpty) ...[
                  const SizedBox(height: 12),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: m.surface,
                      borderRadius: BorderRadius.circular(16),
                      boxShadow: AppShadows.soft,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          l.notes,
                          style: TextStyle(
                            fontWeight: FontWeight.w700,
                            fontSize: 13,
                            color: m.textMuted,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          meeting['notes'],
                          style: TextStyle(
                            color: m.textSecondary,
                            fontSize: 14,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],

                if (canCancel) ...[
                  const SizedBox(height: 24),
                  SizedBox(
                    width: double.infinity,
                    child: OutlinedButton(
                      onPressed: _cancel,
                      style: OutlinedButton.styleFrom(
                        foregroundColor: m.danger,
                        side: BorderSide(color: m.danger),
                        padding: const EdgeInsets.symmetric(vertical: 14),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      child: Text(
                        l.cancelMeeting,
                        style: const TextStyle(fontWeight: FontWeight.w700),
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _InfoCard extends StatelessWidget {
  final String title;
  final List<(String, String)> rows;
  const _InfoCard({required this.title, required this.rows});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(
              fontWeight: FontWeight.w700,
              fontSize: 13,
              color: m.textMuted,
            ),
          ),
          const SizedBox(height: 12),
          ...rows.map(
            (r) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 90,
                    child: Text(
                      r.$1,
                      style: TextStyle(color: m.textMuted, fontSize: 13),
                    ),
                  ),
                  Expanded(
                    child: Text(
                      r.$2,
                      style: const TextStyle(
                        fontWeight: FontWeight.w600,
                        fontSize: 13,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get meeting => ar ? 'الاجتماع' : 'Meeting';
  String get meetingDetails => ar ? 'تفاصيل الاجتماع' : 'Meeting Details';
  String get failedToLoad =>
      ar ? 'تعذر تحميل الاجتماع' : 'Failed to load meeting';
  String get notFound => ar ? 'غير موجود' : 'Not found';
  String get cancelMeetingTitle => ar ? 'إلغاء الاجتماع' : 'Cancel Meeting';
  String get cancelMeetingConfirm => ar
      ? 'هل أنت متأكد أنك تريد إلغاء طلب هذا الاجتماع؟'
      : 'Are you sure you want to cancel this meeting request?';
  String get no => ar ? 'لا' : 'No';
  String get yesCancel => ar ? 'نعم، إلغاء' : 'Yes, Cancel';
  String get meetingCancelled => ar ? 'تم إلغاء الاجتماع' : 'Meeting cancelled';
  String get failedToCancel =>
      ar ? 'تعذر إلغاء الاجتماع' : 'Failed to cancel meeting';
  String get cancelMeeting => ar ? 'إلغاء الاجتماع' : 'Cancel Meeting';
  String get details => ar ? 'التفاصيل' : 'Details';
  String get type => ar ? 'النوع' : 'Type';
  String get onSiteVisit => ar ? 'زيارة ميدانية' : 'On-site Visit';
  String get officeVisit => ar ? 'زيارة المكتب' : 'Office Visit';
  String get purpose => ar ? 'الغرض' : 'Purpose';
  String get property => ar ? 'العقار' : 'Property';
  String get unit => ar ? 'الوحدة' : 'Unit';
  String get lease => ar ? 'عقد الإيجار' : 'Lease';
  String get notes => ar ? 'ملاحظات' : 'Notes';

  String purposeLabel(String p) {
    switch (p) {
      case 'PROPERTY_VIEWING':
        return ar ? 'معاينة العقار' : 'Property Viewing';
      case 'LEASE_RENEWAL':
        return ar ? 'تجديد عقد الإيجار' : 'Lease Renewal';
      case 'CHEQUE_REPLACEMENT':
        return ar ? 'استبدال الشيك' : 'Cheque Replacement';
      default:
        return ar ? 'اجتماع' : 'Meeting';
    }
  }

  String statusLabel(String status) {
    switch (status) {
      case 'REQUESTED':
        return ar ? 'مطلوب' : 'REQUESTED';
      case 'APPROVED':
        return ar ? 'معتمد' : 'APPROVED';
      case 'CANCELLED':
        return ar ? 'ملغي' : 'CANCELLED';
      case 'COMPLETED':
        return ar ? 'مكتمل' : 'COMPLETED';
      case 'NO_SHOW':
        return ar ? 'لم يحضر' : 'NO_SHOW';
      default:
        return status;
    }
  }

  String withHost(String host) => ar ? 'مع $host' : 'With $host';

  static const _monthsEn = [
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ];
  static const _monthsAr = [
    'يناير',
    'فبراير',
    'مارس',
    'أبريل',
    'مايو',
    'يونيو',
    'يوليو',
    'أغسطس',
    'سبتمبر',
    'أكتوبر',
    'نوفمبر',
    'ديسمبر',
  ];

  String formatSlot(String? slotStart) {
    if (slotStart == null) return '-';
    try {
      final dt = DateTime.parse(slotStart).toLocal();
      final months = ar ? _monthsAr : _monthsEn;
      final hour = dt.hour % 12 == 0 ? 12 : dt.hour % 12;
      final minute = dt.minute.toString().padLeft(2, '0');
      final ampm = ar
          ? (dt.hour < 12 ? 'ص' : 'م')
          : (dt.hour < 12 ? 'AM' : 'PM');
      return '${months[dt.month - 1]} ${dt.day}, ${dt.year} · $hour:$minute $ampm';
    } catch (_) {
      return slotStart;
    }
  }
}

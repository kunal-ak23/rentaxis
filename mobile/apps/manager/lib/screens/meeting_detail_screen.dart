import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _meetingServiceProvider = Provider<MeetingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return MeetingService(client.dio);
});

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
/// Purpose/status terms mirror the renter app's meeting_detail_screen so
/// vocabulary stays identical across apps.
class _L {
  _L(this.ar);
  final bool ar;

  String get meeting => ar ? 'الاجتماع' : 'Meeting';
  String get meetingDetails => ar ? 'تفاصيل الاجتماع' : 'Meeting Details';
  String get failedToLoad =>
      ar ? 'تعذر تحميل الاجتماع' : 'Failed to load meeting';
  String get notFound => ar ? 'غير موجود' : 'Not found';
  String get approve => ar ? 'اعتماد' : 'Approve';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get complete => ar ? 'إكمال' : 'Complete';
  String get markComplete => ar ? 'وضع علامة مكتمل' : 'Mark Complete';
  String get noShow => ar ? 'لم يحضر' : 'No-Show';
  String confirmTitle(String action) => action;
  String confirmBody(String action) => ar
      ? 'هل أنت متأكد أنك تريد $action هذا الاجتماع؟'
      : 'Are you sure you want to $action this meeting?';
  String get confirm => ar ? 'تأكيد' : 'Confirm';
  String successMsg(String action) =>
      ar ? 'تم $action بنجاح' : '$action successful';
  String failedMsg(String action) =>
      ar ? 'فشل $action الاجتماع' : 'Failed to $action meeting';
  String get people => ar ? 'الأشخاص' : 'People';
  String get requester => ar ? 'مقدّم الطلب' : 'Requester';
  String get host => ar ? 'المضيف' : 'Host';
  String get context_ => ar ? 'السياق' : 'Context';
  String get property => ar ? 'العقار' : 'Property';
  String get unit => ar ? 'الوحدة' : 'Unit';
  String get lease => ar ? 'عقد الإيجار' : 'Lease';
  String get notes => ar ? 'ملاحظات' : 'Notes';
  String get renewalDetails => ar ? 'تفاصيل التجديد' : 'Renewal Details';
  String get chequeDetails =>
      ar ? 'تفاصيل استبدال الشيك' : 'Cheque Replacement Details';
  String get proposedStart => ar ? 'البداية المقترحة' : 'Proposed Start';
  String get duration => ar ? 'المدة' : 'Duration';
  String months(int n) => ar ? '$n أشهر' : '$n months';
  String cheques(int n) => ar ? '$n شيك' : '$n cheque(s)';
  String get onSite => ar ? 'ميداني' : 'On-site';
  String get office => ar ? 'مكتب' : 'Office';

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
        _error = _L(context.isAr).failedToLoad;
        _isLoading = false;
      });
    }
  }

  Future<void> _action(
    String label,
    Future<Map<String, dynamic>> Function() fn,
  ) async {
    final l = _L(context.isAr);
    final m = context.miftah;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: m.surface,
        title: Text(l.confirmTitle(label)),
        content: Text(l.confirmBody(label)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.accent,
              foregroundColor: AppColors.primary,
            ),
            child: Text(l.confirm),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _isActioning = true);
    try {
      await fn();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.successMsg(label))));
        _load();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.failedMsg(label)),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Color _statusColor(LegacyMiftahColors m, String status) {
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
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _buildHeader(m, l),
            Expanded(
              child: _isLoading
                  ? Padding(
                      padding: const EdgeInsets.all(20),
                      child: ListShimmer(itemCount: 3),
                    )
                  : (_error != null || _meeting == null)
                  ? ErrorState(message: _error ?? l.notFound, onRetry: _load)
                  : _buildBody(m, l),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(LegacyMiftahColors m, _L l) {
    final status = (_meeting?['status'] ?? 'REQUESTED').toString();
    return Container(
      decoration: BoxDecoration(
        color: AppColors.navyDark,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(8, 4, 20, 18),
      child: Row(
        children: [
          IconButton(
            onPressed: () => Navigator.of(context).maybePop(),
            icon: Icon(
              l.ar ? Icons.chevron_right : Icons.chevron_left,
              color: AppColors.accent,
              size: 26,
            ),
          ),
          Expanded(
            child: Text(
              l.meetingDetails,
              style: l.ar
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: Colors.white,
                    )
                  : GoogleFonts.cinzel(
                      fontSize: 15,
                      letterSpacing: 2.0,
                      color: Colors.white,
                    ),
            ),
          ),
          if (_meeting != null && status == 'REQUESTED')
            PopupMenuButton<String>(
              icon: const Icon(Icons.more_vert, color: AppColors.accent),
              color: m.surface,
              onSelected: (v) {
                if (v == 'approve') {
                  _action(
                    l.approve,
                    () => ref
                        .read(_meetingServiceProvider)
                        .approveMeeting(widget.meetingId),
                  );
                } else if (v == 'cancel') {
                  _action(
                    l.cancel,
                    () => ref
                        .read(_meetingServiceProvider)
                        .cancelMeeting(widget.meetingId),
                  );
                }
              },
              itemBuilder: (_) => [
                PopupMenuItem(
                  value: 'approve',
                  child: Row(
                    children: [
                      Icon(
                        Icons.check_circle_outline,
                        color: m.success,
                        size: 18,
                      ),
                      const SizedBox(width: 8),
                      Text(l.approve),
                    ],
                  ),
                ),
                PopupMenuItem(
                  value: 'cancel',
                  child: Row(
                    children: [
                      Icon(Icons.cancel_outlined, color: m.danger, size: 18),
                      const SizedBox(width: 8),
                      Text(l.cancel),
                    ],
                  ),
                ),
              ],
            ),
          if (_meeting != null && status == 'APPROVED')
            PopupMenuButton<String>(
              icon: const Icon(Icons.more_vert, color: AppColors.accent),
              color: m.surface,
              onSelected: (v) {
                if (v == 'complete') {
                  _action(
                    l.complete,
                    () => ref
                        .read(_meetingServiceProvider)
                        .completeMeeting(widget.meetingId),
                  );
                } else if (v == 'no-show') {
                  _action(
                    l.noShow,
                    () => ref
                        .read(_meetingServiceProvider)
                        .noShowMeeting(widget.meetingId),
                  );
                } else if (v == 'cancel') {
                  _action(
                    l.cancel,
                    () => ref
                        .read(_meetingServiceProvider)
                        .cancelMeeting(widget.meetingId),
                  );
                }
              },
              itemBuilder: (_) => [
                PopupMenuItem(
                  value: 'complete',
                  child: Row(
                    children: [
                      Icon(Icons.done_all_rounded, color: m.success, size: 18),
                      const SizedBox(width: 8),
                      Text(l.markComplete),
                    ],
                  ),
                ),
                PopupMenuItem(
                  value: 'no-show',
                  child: Row(
                    children: [
                      Icon(
                        Icons.person_off_outlined,
                        color: m.warning,
                        size: 18,
                      ),
                      const SizedBox(width: 8),
                      Text(l.noShow),
                    ],
                  ),
                ),
                PopupMenuItem(
                  value: 'cancel',
                  child: Row(
                    children: [
                      Icon(Icons.cancel_outlined, color: m.danger, size: 18),
                      const SizedBox(width: 8),
                      Text(l.cancel),
                    ],
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }

  Widget _buildBody(LegacyMiftahColors m, _L l) {
    final meetingData = _meeting!;
    final status = (meetingData['status'] ?? 'REQUESTED').toString();
    final purpose = (meetingData['purpose'] ?? '').toString();
    final statusColor = _statusColor(m, status);

    return LoadingOverlay(
      isLoading: _isActioning,
      child: RefreshIndicator(
        onRefresh: _load,
        color: AppColors.accent,
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: EdgeInsets.fromLTRB(
            20,
            16,
            20,
            AppInsets.bottomNav(context),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              AnimatedListItem(
                index: 0,
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        meetingData['title'] ?? l.purposeLabel(purpose),
                        style: l.ar
                            ? GoogleFonts.notoNaskhArabic(
                                fontSize: 18,
                                fontWeight: FontWeight.w600,
                                color: m.textPrimary,
                              )
                            : GoogleFonts.cinzel(
                                fontSize: 19,
                                color: m.textPrimary,
                              ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    _StatusPill(
                      label: l.statusLabel(status),
                      color: statusColor,
                      ar: l.ar,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              AnimatedListItem(
                index: 1,
                child: _InfoRow(
                  icon: Icons.calendar_today_outlined,
                  text: l.formatSlot(meetingData['slotStart']?.toString()),
                  m: m,
                ),
              ),
              const SizedBox(height: 6),
              AnimatedListItem(
                index: 2,
                child: _InfoRow(
                  icon: Icons.category_outlined,
                  text:
                      '${l.purposeLabel(purpose)} · ${meetingData['type'] == 'PROPERTY_VISIT' ? l.onSite : l.office}',
                  m: m,
                ),
              ),
              const SizedBox(height: 20),

              _SectionCard(
                title: l.people,
                m: m,
                l: l,
                child: Column(
                  children: [
                    _DetailRow(
                      label: l.requester,
                      value: meetingData['requesterName'] ?? '-',
                      m: m,
                    ),
                    _DetailRow(
                      label: l.host,
                      value: meetingData['hostName'] ?? '-',
                      m: m,
                    ),
                  ],
                ),
              ),

              if (meetingData['propertyName'] != null ||
                  meetingData['leaseLabel'] != null) ...[
                const SizedBox(height: 12),
                _SectionCard(
                  title: l.context_,
                  m: m,
                  l: l,
                  child: Column(
                    children: [
                      if (meetingData['propertyName'] != null)
                        _DetailRow(
                          label: l.property,
                          value: meetingData['propertyName'],
                          m: m,
                        ),
                      if (meetingData['unitNumber'] != null)
                        _DetailRow(
                          label: l.unit,
                          value: meetingData['unitNumber'],
                          m: m,
                        ),
                      if (meetingData['leaseLabel'] != null)
                        _DetailRow(
                          label: l.lease,
                          value: meetingData['leaseLabel'],
                          m: m,
                        ),
                    ],
                  ),
                ),
              ],

              if (meetingData['details'] != null) ...[
                const SizedBox(height: 12),
                _buildDetails(
                  purpose,
                  meetingData['details'] as Map<String, dynamic>,
                  m,
                  l,
                ),
              ],

              if ((meetingData['notes'] ?? '').isNotEmpty) ...[
                const SizedBox(height: 12),
                _SectionCard(
                  title: l.notes,
                  m: m,
                  l: l,
                  child: Text(
                    meetingData['notes'],
                    style: GoogleFonts.josefinSans(
                      fontSize: 14,
                      color: m.textSecondary,
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDetails(
    String purpose,
    Map<String, dynamic> details,
    LegacyMiftahColors m,
    _L l,
  ) {
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
        title: l.renewalDetails,
        m: m,
        l: l,
        child: Column(
          children: [
            if (start != null)
              _DetailRow(label: l.proposedStart, value: start, m: m),
            if (months != null)
              _DetailRow(label: l.duration, value: l.months(months), m: m),
            if ((details['notes'] ?? '').isNotEmpty)
              _DetailRow(label: l.notes, value: details['notes'], m: m),
          ],
        ),
      );
    }
    if (purpose == 'CHEQUE_REPLACEMENT') {
      final ids = details['paymentScheduleIds'] as List<dynamic>?;
      return _SectionCard(
        title: l.chequeDetails,
        m: m,
        l: l,
        child: Column(
          children: [
            if (ids != null && ids.isNotEmpty)
              _DetailRow(label: l.notes, value: l.cheques(ids.length), m: m),
            if ((details['notes'] ?? '').isNotEmpty)
              _DetailRow(label: l.notes, value: details['notes'], m: m),
          ],
        ),
      );
    }
    return const SizedBox.shrink();
  }
}

class _StatusPill extends StatelessWidget {
  final String label;
  final Color color;
  final bool ar;

  const _StatusPill({
    required this.label,
    required this.color,
    required this.ar,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: GoogleFonts.josefinSans(
          fontSize: 10.5,
          fontWeight: FontWeight.w600,
          letterSpacing: ar ? 0 : 1.2,
          color: color,
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  final IconData icon;
  final String text;
  final LegacyMiftahColors m;
  const _InfoRow({required this.icon, required this.text, required this.m});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(icon, size: 14, color: m.textMuted),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            text,
            style: GoogleFonts.josefinSans(
              fontSize: 13,
              color: m.textSecondary,
            ),
          ),
        ),
      ],
    );
  }
}

class _SectionCard extends StatelessWidget {
  final String title;
  final Widget child;
  final LegacyMiftahColors m;
  final _L l;
  const _SectionCard({
    required this.title,
    required this.child,
    required this.m,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textMuted)
                : GoogleFonts.josefinSans(
                    fontSize: 10.5,
                    letterSpacing: 2.0,
                    fontWeight: FontWeight.w600,
                    color: m.textMuted,
                  ),
          ),
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
  final LegacyMiftahColors m;
  const _DetailRow({required this.label, required this.value, required this.m});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 110,
            child: Text(
              label,
              style: GoogleFonts.josefinSans(fontSize: 13, color: m.textMuted),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: GoogleFonts.josefinSans(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: m.textPrimary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

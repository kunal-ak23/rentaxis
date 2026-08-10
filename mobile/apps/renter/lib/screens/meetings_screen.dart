import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
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
  static const _pageSize = 25;

  List<dynamic> _meetings = [];
  bool _isLoading = true;
  bool _loadingMore = false;
  String? _error;

  // Server-side pagination state: meetings accumulate page by page (sorted
  // slotStart ASC by the backend) so renters with more meetings than one
  // page holds aren't silently truncated. The epoch guards against a stale
  // in-flight fetch (e.g. mid-scroll refresh) landing on fresh state.
  int _page = 0;
  int _totalPages = 1;
  int _fetchEpoch = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final epoch = ++_fetchEpoch;
    setState(() {
      _isLoading = true;
      _error = null;
      _meetings = [];
      _page = 0;
      _totalPages = 1;
    });
    await _fetchPage(0, epoch);
  }

  Future<void> _fetchPage(int page, int epoch) async {
    try {
      final svc = ref.read(_meetingServiceProvider);
      final result = await svc.listMyMeetingsPage(
        perspective: 'requester',
        page: page,
        size: _pageSize,
      );
      if (!mounted || epoch != _fetchEpoch) return;
      setState(() {
        _meetings = [..._meetings, ...(result['content'] as List? ?? const [])];
        _page = page;
        _totalPages = (result['totalPages'] as num?)?.toInt() ?? 1;
        _isLoading = false;
        _loadingMore = false;
      });
    } catch (e) {
      if (!mounted || epoch != _fetchEpoch) return;
      setState(() {
        _isLoading = false;
        _loadingMore = false;
        // Failing to extend the list shouldn't blank rows already on screen.
        _error = _meetings.isEmpty ? _L(context.isAr).failedToLoad : null;
      });
    }
  }

  bool get _hasMore => _page + 1 < _totalPages;

  Future<void> _loadMore() async {
    if (_isLoading || _loadingMore || !_hasMore) return;
    setState(() => _loadingMore = true);
    await _fetchPage(_page + 1, _fetchEpoch);
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
    final m = context.miftah;
    final l = _L(context.isAr);
    final accentColor = m.isDark ? AppColors.accent : AppColors.primary;
    return Scaffold(
      appBar: AppBar(
        title: Text(l.title),
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
          ? Center(child: CircularProgressIndicator(color: accentColor))
          : _error != null
          ? ErrorState(message: _error!, onRetry: _load)
          : _meetings.isEmpty
          ? EmptyState(
              title: l.noMeetingsTitle,
              subtitle: l.noMeetingsSubtitle,
              icon: Icons.event_outlined,
              actionLabel: l.requestMeeting,
              onAction: () =>
                  context.push('/meetings/create').then((_) => _load()),
            )
          : NotificationListener<ScrollNotification>(
              onNotification: (scrollInfo) {
                if (scrollInfo.metrics.pixels >=
                    scrollInfo.metrics.maxScrollExtent - 200) {
                  _loadMore();
                }
                return false;
              },
              child: RefreshIndicator(
                onRefresh: _load,
                color: accentColor,
                child: ListView.separated(
                  padding: EdgeInsets.fromLTRB(
                    16,
                    12,
                    16,
                    AppInsets.bottomNav(context),
                  ),
                  itemCount: _meetings.length + (_loadingMore ? 1 : 0),
                  separatorBuilder: (_, _) => const SizedBox(height: 10),
                  itemBuilder: (_, i) {
                    if (i >= _meetings.length) {
                      return Center(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: CircularProgressIndicator(color: accentColor),
                        ),
                      );
                    }
                    final meeting = _meetings[i] as Map<String, dynamic>;
                    final status = meeting['status'] ?? 'REQUESTED';
                    final purpose = meeting['purpose'] ?? '';
                    return AnimatedListItem(
                      index: i,
                      child: InkWell(
                        onTap: () => context.push('/meetings/${meeting['id']}'),
                        borderRadius: BorderRadius.circular(16),
                        child: Container(
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: m.surface,
                            borderRadius: BorderRadius.circular(16),
                            border: Border.all(color: m.border),
                            boxShadow: m.isDark ? null : AppShadows.soft,
                          ),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Container(
                                width: 44,
                                height: 44,
                                decoration: BoxDecoration(
                                  color: accentColor.withValues(alpha: 0.1),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: Icon(
                                  Icons.event_outlined,
                                  color: accentColor,
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
                                        Expanded(
                                          child: Text(
                                            meeting['title'] ??
                                                l.purposeLabel(purpose),
                                            style: const TextStyle(
                                              fontWeight: FontWeight.w700,
                                              fontSize: 14,
                                            ),
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                          ),
                                        ),
                                        const SizedBox(width: 8),
                                        Container(
                                          padding: const EdgeInsets.symmetric(
                                            horizontal: 7,
                                            vertical: 2,
                                          ),
                                          decoration: BoxDecoration(
                                            color: _statusColor(
                                              status,
                                            ).withValues(alpha: 0.12),
                                            borderRadius: BorderRadius.circular(
                                              6,
                                            ),
                                          ),
                                          child: Text(
                                            l.statusLabel(status),
                                            style: TextStyle(
                                              fontSize: 10,
                                              fontWeight: FontWeight.w700,
                                              color: _statusColor(status),
                                            ),
                                          ),
                                        ),
                                      ],
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      l.formatSlot(
                                        meeting['slotStart']?.toString(),
                                      ),
                                      style: TextStyle(
                                        color: m.textMuted,
                                        fontSize: 12,
                                      ),
                                    ),
                                    if (meeting['hostName'] != null) ...[
                                      const SizedBox(height: 2),
                                      Text(
                                        l.withHost(meeting['hostName']),
                                        style: TextStyle(
                                          color: m.textMuted,
                                          fontSize: 12,
                                        ),
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
    );
  }
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المواعيد' : 'Meetings';
  String get noMeetingsTitle => ar ? 'لا توجد اجتماعات بعد' : 'No meetings yet';
  String get noMeetingsSubtitle =>
      ar ? 'اضغط على + لطلب اجتماع' : 'Tap + to request a meeting';
  String get requestMeeting => ar ? 'طلب اجتماع' : 'Request Meeting';
  String get failedToLoad =>
      ar ? 'تعذر تحميل الاجتماعات' : 'Failed to load meetings';

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
      return '${months[dt.month - 1]} ${dt.day} · $hour:$minute $ampm';
    } catch (_) {
      return slotStart;
    }
  }
}

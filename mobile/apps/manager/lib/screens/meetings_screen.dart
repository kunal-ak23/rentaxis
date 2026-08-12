import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _meetingServiceProvider = Provider<MeetingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return MeetingService(client.dio);
});

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
/// Purpose/status terms mirror the renter app's meetings_screen so
/// vocabulary stays identical across apps.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المواعيد' : 'Meetings';
  String get allMeetings => ar ? 'كل المواعيد' : 'All Meetings';
  String get mySchedule => ar ? 'جدولي' : 'My Schedule';
  String get noMeetingsFound => ar ? 'لا توجد مواعيد' : 'No meetings found';
  String get failedToLoad =>
      ar ? 'تعذر تحميل المواعيد' : 'Failed to load meetings';
  String get onSite => ar ? 'ميداني' : 'On-site';
  String get office => ar ? 'مكتب' : 'Office';
  String withHost(String host) => ar ? 'مع $host' : 'With $host';

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
  int _segment = 0; // 0=All, 1=My (host)

  // Server-side pagination state: meetings accumulate page by page (sorted
  // slotStart ASC by the backend) so tenants with more meetings than one
  // page holds aren't silently truncated. The epoch guards against a stale
  // in-flight fetch (e.g. after a segment switch) landing on fresh state.
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
      final result = _segment == 0
          ? await svc.listMeetingsPage(page: page, size: _pageSize)
          : await svc.listMyMeetingsPage(
              perspective: 'host',
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

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        children: [
          _buildChromeHeader(l),
          Expanded(
            child: _isLoading
                ? Padding(
                    padding: const EdgeInsets.all(20),
                    child: ListShimmer(itemCount: 3),
                  )
                : _error != null
                ? ErrorState(message: _error!, onRetry: _load)
                : _meetings.isEmpty
                ? EmptyState(
                    title: l.noMeetingsFound,
                    icon: Icons.event_outlined,
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
                      color: AppColors.accent,
                      child: ListView.separated(
                        padding: EdgeInsets.fromLTRB(
                          20,
                          14,
                          20,
                          AppInsets.bottomNav(context),
                        ),
                        itemCount: _meetings.length + (_loadingMore ? 1 : 0),
                        separatorBuilder: (_, _) => const SizedBox(height: 10),
                        itemBuilder: (_, i) {
                          if (i >= _meetings.length) {
                            return const Center(
                              child: Padding(
                                padding: EdgeInsets.all(16),
                                child: CircularProgressIndicator(
                                  color: AppColors.accent,
                                ),
                              ),
                            );
                          }
                          return AnimatedListItem(
                            index: i,
                            child: _MeetingCard(meeting: _meetings[i], l: l),
                          );
                        },
                      ),
                    ),
                  ),
          ),
        ],
      ),
    );
  }

  /// Dark chrome header matching the tickets/more screens: tracked overline,
  /// Cinzel title, "+" action, and the All/My Schedule segment toggle.
  Widget _buildChromeHeader(_L l) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.navyDark,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(20, 22, 20, 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                l.title,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 20,
                        fontWeight: FontWeight.w600,
                        color: AppColors.gold400,
                      )
                    : GoogleFonts.plusJakartaSans(
                        fontSize: 23,
                        color: AppColors.gold400,
                      ),
              ),
              GestureDetector(
                onTap: () =>
                    context.push('/meetings/create').then((_) => _load()),
                child: const Icon(
                  Icons.add_circle_outline,
                  color: AppColors.accent,
                  size: 24,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              _SegmentChip(
                label: l.allMeetings,
                selected: _segment == 0,
                ar: l.ar,
                onTap: () {
                  setState(() => _segment = 0);
                  _load();
                },
              ),
              const SizedBox(width: 8),
              _SegmentChip(
                label: l.mySchedule,
                selected: _segment == 1,
                ar: l.ar,
                onTap: () {
                  setState(() => _segment = 1);
                  _load();
                },
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _SegmentChip extends StatelessWidget {
  final String label;
  final bool selected;
  final bool ar;
  final VoidCallback onTap;

  const _SegmentChip({
    required this.label,
    required this.selected,
    required this.ar,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          color: selected ? AppColors.accent : Colors.transparent,
          borderRadius: BorderRadius.circular(999),
          border: selected
              ? null
              : Border.all(color: Colors.white.withValues(alpha: 0.22)),
        ),
        child: Text(
          label,
          style: GoogleFonts.plusJakartaSans(
            fontSize: 11.5,
            fontWeight: FontWeight.w500,
            letterSpacing: ar ? 0 : 1.2,
            color: selected
                ? AppColors.primary
                : Colors.white.withValues(alpha: 0.7),
          ),
        ),
      ),
    );
  }
}

class _MeetingCard extends StatelessWidget {
  final Map<String, dynamic> meeting;
  final _L l;
  const _MeetingCard({required this.meeting, required this.l});

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
    final status = (meeting['status'] ?? 'REQUESTED').toString();
    final purpose = (meeting['purpose'] ?? '').toString();
    final type = (meeting['type'] ?? '').toString();
    final statusColor = _statusColor(m, status);

    return Container(
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: () => context.push('/meetings/${meeting['id']}'),
          borderRadius: BorderRadius.circular(14),
          child: Stack(
            children: [
              PositionedDirectional(
                start: 0,
                top: 0,
                bottom: 0,
                child: Container(width: 2, color: statusColor),
              ),
              Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            meeting['title'] ?? l.purposeLabel(purpose),
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w500,
                              color: m.textPrimary,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
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
                    const SizedBox(height: 7),
                    Row(
                      children: [
                        Icon(
                          Icons.calendar_today_outlined,
                          size: 13,
                          color: m.textMuted,
                        ),
                        const SizedBox(width: 5),
                        Text(
                          l.formatSlot(meeting['slotStart']?.toString()),
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 12,
                            color: m.textMuted,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Row(
                      children: [
                        Icon(
                          Icons.person_outline_rounded,
                          size: 13,
                          color: m.textMuted,
                        ),
                        const SizedBox(width: 5),
                        Text(
                          meeting['requesterName'] ?? '-',
                          style: GoogleFonts.plusJakartaSans(
                            fontSize: 12,
                            color: m.textMuted,
                          ),
                        ),
                        const SizedBox(width: 10),
                        _StatusPill(
                          label: type == 'PROPERTY_VISIT' ? l.onSite : l.office,
                          color: AppColors.accentDark,
                          ar: l.ar,
                        ),
                      ],
                    ),
                    if (meeting['propertyName'] != null) ...[
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          Icon(
                            Icons.apartment_outlined,
                            size: 13,
                            color: m.textMuted,
                          ),
                          const SizedBox(width: 5),
                          Text(
                            meeting['propertyName'],
                            style: GoogleFonts.plusJakartaSans(
                              fontSize: 12,
                              color: m.textMuted,
                            ),
                          ),
                        ],
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
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: GoogleFonts.plusJakartaSans(
          fontSize: 9.5,
          fontWeight: FontWeight.w600,
          letterSpacing: ar ? 0 : 0.8,
          color: color,
        ),
      ),
    );
  }
}

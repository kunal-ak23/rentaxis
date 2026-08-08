import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

String _shortId(dynamic id) {
  final s = (id?.toString() ?? '').replaceAll('-', '');
  if (s.isEmpty) return '----';
  return s.substring(0, s.length > 6 ? 6 : s.length).toUpperCase();
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
/// Category/status/priority terms mirror the renter app's tickets/ticket
/// detail screens so vocabulary stays identical across apps.
class _L {
  _L(this.ar);
  final bool ar;

  String ticketRef(String id) => ar ? 'الطلب #$id' : 'TICKET #$id';
  String get untitled => ar ? 'بدون عنوان' : 'Untitled';
  String get description => ar ? 'الوصف' : 'DESCRIPTION';
  String get noDescription => ar ? 'لا يوجد وصف' : 'No description';
  String get attachments => ar ? 'المرفقات' : 'ATTACHMENTS';
  String get managementActions => ar ? 'إجراءات الإدارة' : 'MANAGEMENT ACTIONS';
  String get assignToMe => ar ? 'إسناد لي' : 'Assign to Me';
  String get setEta => ar ? 'تحديد الوقت المتوقع' : 'Set ETA';
  String get startWork => ar ? 'بدء العمل' : 'Start Work';
  String get markResolved => ar ? 'وضع علامة محلولة' : 'Resolved';
  String get otpPrompt => ar
      ? 'أدخل رمز المستأجر لإغلاق الطلب:'
      : 'Enter renter OTP to close the ticket:';
  String get enterOtp => ar ? 'أدخل الرمز' : 'Enter OTP';
  String get close => ar ? 'إغلاق' : 'Close';
  String get reopenTicket => ar ? 'إعادة فتح الطلب' : 'Reopen Ticket';
  String replies(int n) => ar ? 'الردود ($n)' : 'Replies ($n)';
  String get noRepliesYet => ar ? 'لا توجد ردود بعد' : 'No replies yet';
  String get activityHistory => ar ? 'سجل النشاط' : 'ACTIVITY HISTORY';
  String get typeReply => ar ? 'اكتب ردًا...' : 'Type a reply...';
  String get you => ar ? 'أنت' : 'You';
  String get unknown => ar ? 'غير معروف' : 'Unknown';
  String get notFound => ar ? 'غير موجود' : 'Not found';
  String get loadFailed => ar ? 'فشل تحميل الطلب' : 'Failed to load ticket';
  String statusUpdated(String status) => ar
      ? 'تم تحديث الحالة إلى ${this.status(status)}'
      : 'Status updated to $status';
  String get failedToUpdateStatus =>
      ar ? 'فشل تحديث الحالة' : 'Failed to update status';
  String get assignedToYou =>
      ar ? 'تم إسناد الطلب إليك' : 'Ticket assigned to you';
  String get failedToAssign =>
      ar ? 'فشل إسناد الطلب' : 'Failed to assign ticket';
  String get otpRequired => ar
      ? 'رمز التحقق مطلوب لإغلاق الطلب'
      : 'OTP is required to close the ticket';
  String get ticketClosed => ar ? 'تم إغلاق الطلب' : 'Ticket closed';
  String get failedToCloseCheckOtp => ar
      ? 'فشل إغلاق الطلب. تحقق من الرمز.'
      : 'Failed to close ticket. Check OTP.';
  String get setEtaTitle => ar ? 'تحديد الوقت المتوقع' : 'Set ETA';
  String get estimatedHours => ar ? 'الساعات المتوقعة' : 'Estimated hours';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get set => ar ? 'تحديد' : 'Set';
  String etaSet(int hours) =>
      ar ? 'تم تحديد الوقت المتوقع بـ $hours ساعة' : 'ETA set to $hours hours';
  String get failedToSetEta =>
      ar ? 'فشل تحديد الوقت المتوقع' : 'Failed to set ETA';
  String get failedToSendReply =>
      ar ? 'فشل إرسال الرد' : 'Failed to send reply';
  String get photoAttached => ar ? 'تم إرفاق الصورة' : 'Photo attached';
  String get failedToUpload => ar ? 'فشل الرفع' : 'Failed to upload';
  String get unit => ar ? 'وحدة' : 'Unit';

  String category(String value) {
    switch (value) {
      case 'PLUMBING':
        return ar ? 'سباكة' : 'Plumbing';
      case 'ELECTRICAL':
        return ar ? 'كهرباء' : 'Electrical';
      case 'HVAC':
        return ar ? 'تكييف' : 'HVAC';
      case 'APPLIANCE':
        return ar ? 'أجهزة' : 'Appliance';
      case 'STRUCTURAL':
        return ar ? 'إنشائي' : 'Structural';
      case 'PEST_CONTROL':
        return ar ? 'مكافحة حشرات' : 'Pest Control';
      case 'CLEANING':
        return ar ? 'تنظيف' : 'Cleaning';
      case 'SECURITY':
        return ar ? 'أمن' : 'Security';
      case 'MAINTENANCE':
        return ar ? 'صيانة' : 'Maintenance';
      case 'GENERAL':
      case 'OTHER':
      case '':
        return ar ? 'عام' : 'General';
      default:
        return value.replaceAll('_', ' ');
    }
  }

  String status(String value) {
    switch (value) {
      case 'OPEN':
        return ar ? 'مفتوحة' : 'OPEN';
      case 'ASSIGNED':
        return ar ? 'مسندة' : 'ASSIGNED';
      case 'IN_PROGRESS':
        return ar ? 'قيد التنفيذ' : 'IN PROGRESS';
      case 'RESOLVED':
        return ar ? 'تم الحل' : 'RESOLVED';
      case 'CLOSED':
        return ar ? 'مغلقة' : 'CLOSED';
      case 'REOPENED':
        return ar ? 'أُعيد فتحها' : 'REOPENED';
      default:
        return value.replaceAll('_', ' ');
    }
  }

  String priority(String value) {
    switch (value) {
      case 'LOW':
        return ar ? 'منخفضة' : 'LOW';
      case 'MEDIUM':
        return ar ? 'متوسطة' : 'MEDIUM';
      case 'HIGH':
        return ar ? 'مرتفعة' : 'HIGH';
      case 'URGENT':
        return ar ? 'عاجلة' : 'URGENT';
      default:
        return value.replaceAll('_', ' ');
    }
  }
}

/// 4-step status rail labels (EN/AR) — matches renter's ticket detail rail.
List<String> _railLabels(bool ar) => ar
    ? ['أُثيرت', 'أُسندت', 'قيد التنفيذ', 'مغلقة']
    : ['RAISED', 'ASSIGNED', 'IN PROGRESS', 'CLOSED'];

class TicketDetailScreen extends ConsumerStatefulWidget {
  final String ticketId;
  const TicketDetailScreen({super.key, required this.ticketId});

  @override
  ConsumerState<TicketDetailScreen> createState() => _TicketDetailScreenState();
}

class _TicketDetailScreenState extends ConsumerState<TicketDetailScreen> {
  Map<String, dynamic>? _ticket;
  List<dynamic> _replies = [];
  List<dynamic> _attachments = [];
  List<dynamic> _history = [];
  bool _isLoading = true;
  bool _isActioning = false;
  String? _error;

  final _replyCtrl = TextEditingController();
  final _otpCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  @override
  void dispose() {
    _replyCtrl.dispose();
    _otpCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      final service = ref.read(_ticketServiceProvider);
      final results = await Future.wait([
        service.getTicketById(widget.ticketId),
        service.getReplies(widget.ticketId),
        service.getAttachments(widget.ticketId),
        service.getHistory(widget.ticketId),
      ]);
      if (!mounted) return;
      setState(() {
        _ticket = results[0] as Map<String, dynamic>;
        _replies = results[1] as List<dynamic>;
        _attachments = results[2] as List<dynamic>;
        _history = results[3] as List<dynamic>;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = _L(context.isAr).loadFailed;
        _isLoading = false;
      });
    }
  }

  Future<void> _updateStatus(String newStatus) async {
    final l = _L(context.isAr);
    setState(() => _isActioning = true);
    try {
      await ref
          .read(_ticketServiceProvider)
          .updateStatus(widget.ticketId, newStatus);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.statusUpdated(newStatus))));
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.failedToUpdateStatus),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Future<void> _assignToMe() async {
    final l = _L(context.isAr);
    final authState = ref.read(authProvider);
    final userId = authState.userId;
    if (userId == null) return;

    setState(() => _isActioning = true);
    try {
      await ref
          .read(_ticketServiceProvider)
          .assignTicket(widget.ticketId, userId);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.assignedToYou)));
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.failedToAssign),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Future<void> _closeTicket() async {
    final l = _L(context.isAr);
    final otp = _otpCtrl.text.trim();
    if (otp.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(l.otpRequired),
          backgroundColor: AppColors.danger,
        ),
      );
      return;
    }

    setState(() => _isActioning = true);
    try {
      await ref.read(_ticketServiceProvider).closeTicket(widget.ticketId, otp);
      _otpCtrl.clear();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.ticketClosed)));
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.failedToCloseCheckOtp),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Future<void> _setEta() async {
    final l = _L(context.isAr);
    final m = context.miftah;
    final hoursCtrl = TextEditingController();
    final result = await showDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: m.surface,
        title: Text(l.setEtaTitle),
        content: TextField(
          controller: hoursCtrl,
          keyboardType: TextInputType.number,
          decoration: InputDecoration(
            labelText: l.estimatedHours,
            hintText: l.ar ? 'مثال: 24' : 'e.g. 24',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(l.cancel),
          ),
          ElevatedButton(
            onPressed: () {
              final hours = int.tryParse(hoursCtrl.text);
              if (hours != null && hours > 0) {
                Navigator.pop(ctx, hours);
              }
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.accent,
              foregroundColor: AppColors.primary,
            ),
            child: Text(l.set),
          ),
        ],
      ),
    );

    if (result == null) return;

    try {
      await ref
          .read(_ticketServiceProvider)
          .setEstimate(widget.ticketId, result);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.etaSet(result))));
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.failedToSetEta),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
  }

  Future<void> _sendReply() async {
    final l = _L(context.isAr);
    final message = _replyCtrl.text.trim();
    if (message.isEmpty) return;

    try {
      await ref.read(_ticketServiceProvider).addReply(widget.ticketId, message);
      _replyCtrl.clear();
      _loadData();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.failedToSendReply),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
  }

  Future<void> _uploadReplyAttachment() async {
    final l = _L(context.isAr);
    final picker = ImagePicker();
    final image = await picker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 80,
    );
    if (image == null) return;

    try {
      await ref
          .read(_ticketServiceProvider)
          .uploadAttachment(widget.ticketId, image.path);
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(l.photoAttached)));
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(l.failedToUpload),
            backgroundColor: AppColors.danger,
          ),
        );
      }
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
            _buildHeader(l),
            Expanded(
              child: _isLoading
                  ? Padding(
                      padding: const EdgeInsets.all(20),
                      child: ListShimmer(itemCount: 3),
                    )
                  : (_error != null || _ticket == null)
                  ? ErrorState(
                      message: _error ?? l.notFound,
                      onRetry: _loadData,
                    )
                  : _buildBody(m, l),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBody(MiftahColors m, _L l) {
    final ticket = _ticket!;
    final status = (ticket['status'] ?? 'OPEN').toString();
    final priority = (ticket['priority'] ?? 'MEDIUM').toString();
    final category = (ticket['category'] ?? '').toString();

    return LoadingOverlay(
      isLoading: _isActioning,
      child: Column(
        children: [
          Expanded(
            child: RefreshIndicator(
              onRefresh: _loadData,
              color: AppColors.accent,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: EdgeInsets.fromLTRB(
                  20,
                  16,
                  20,
                  AppInsets.bottomNav(context),
                ),
                children: [
                  AnimatedListItem(
                    index: 0,
                    child: Row(
                      children: [
                        _StatusPill(
                          label: l.priority(priority),
                          color: StatusHelper.getPriorityColor(priority),
                          ar: l.ar,
                        ),
                        const SizedBox(width: 8),
                        if (category.isNotEmpty)
                          _StatusPill(
                            label: l.category(category),
                            color: AppColors.accentDark,
                            ar: l.ar,
                          ),
                        const Spacer(),
                        Text(
                          Formatters.timeAgo(ticket['createdAt'], ar: l.ar),
                          style: GoogleFonts.josefinSans(
                            fontSize: 11.5,
                            color: m.textMuted,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 14),

                  if (ticket['propertyName'] != null) ...[
                    AnimatedListItem(
                      index: 1,
                      child: _InfoRow(
                        icon: Icons.apartment_outlined,
                        text: [
                          ticket['propertyName'],
                          if (ticket['unitNumber'] != null)
                            '${l.unit} ${ticket['unitNumber']}',
                        ].join(' · '),
                        m: m,
                      ),
                    ),
                    const SizedBox(height: 16),
                  ],

                  if (ticket['assigneeName'] != null) ...[
                    AnimatedListItem(
                      index: 2,
                      child: _AssigneeCard(
                        name: ticket['assigneeName'].toString(),
                        category: category,
                        l: l,
                      ),
                    ),
                    const SizedBox(height: 16),
                  ],

                  _sectionLabel(l.description, l, m),
                  const SizedBox(height: 8),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: m.surfaceAlt,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: m.border),
                    ),
                    child: Text(
                      ticket['description'] ?? l.noDescription,
                      style: GoogleFonts.josefinSans(
                        fontSize: 14,
                        color: m.textPrimary,
                        height: 1.5,
                      ),
                    ),
                  ),
                  const SizedBox(height: 20),

                  if (_attachments.isNotEmpty) ...[
                    _sectionLabel(l.attachments, l, m),
                    const SizedBox(height: 10),
                    SizedBox(
                      height: 76,
                      child: ListView.builder(
                        scrollDirection: Axis.horizontal,
                        itemCount: _attachments.length,
                        itemBuilder: (context, index) {
                          final att = _attachments[index];
                          return Padding(
                            padding: const EdgeInsetsDirectional.only(end: 10),
                            child: _AttachmentThumb(att: att, m: m),
                          );
                        },
                      ),
                    ),
                    const SizedBox(height: 20),
                  ],

                  _ActionsPanel(
                    status: status,
                    l: l,
                    otpCtrl: _otpCtrl,
                    onAssignToMe: _assignToMe,
                    onSetEta: _setEta,
                    onStartWork: () => _updateStatus('IN_PROGRESS'),
                    onMarkResolved: () => _updateStatus('RESOLVED'),
                    onCloseTicket: _closeTicket,
                    onReopen: () => _updateStatus('REOPENED'),
                  ),
                  const SizedBox(height: 20),

                  Text(
                    l.replies(_replies.length),
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.cinzel(
                            fontSize: 13,
                            letterSpacing: 2.4,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 14),
                  if (_replies.isEmpty)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      child: Center(
                        child: Text(
                          l.noRepliesYet,
                          style: GoogleFonts.josefinSans(
                            fontSize: 12.5,
                            color: m.textMuted,
                          ),
                        ),
                      ),
                    )
                  else
                    ..._replies.asMap().entries.map(
                      (entry) => AnimatedListItem(
                        index: entry.key,
                        child: _UpdateCard(
                          reply: entry.value,
                          isCurrentUser:
                              entry.value['userId'] ==
                              ref.read(authProvider).userId,
                          l: l,
                        ),
                      ),
                    ),

                  if (_history.isNotEmpty) ...[
                    const SizedBox(height: 20),
                    _sectionLabel(l.activityHistory, l, m),
                    const SizedBox(height: 10),
                    ..._history.map((h) => _HistoryRow(item: h, l: l, m: m)),
                  ],
                ],
              ),
            ),
          ),
          if (status != 'CLOSED') _buildReplyInput(l, m),
        ],
      ),
    );
  }

  Widget _sectionLabel(String text, _L l, MiftahColors m) {
    return Text(
      text,
      style: l.ar
          ? GoogleFonts.notoNaskhArabic(fontSize: 12, color: m.textMuted)
          : GoogleFonts.josefinSans(
              fontSize: 11,
              fontWeight: FontWeight.w500,
              letterSpacing: 2.0,
              color: m.textMuted,
            ),
    );
  }

  /// Dark chrome header per admin design pattern (mirrors renter's ticket
  /// detail 1g): tracked "TICKET #ID", Cinzel title, and the 4-step status
  /// rail.
  Widget _buildHeader(_L l) {
    final status = (_ticket?['status'] ?? 'OPEN').toString();

    return Container(
      decoration: BoxDecoration(
        color: AppColors.navyDark,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      padding: const EdgeInsets.fromLTRB(8, 4, 20, 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              IconButton(
                onPressed: () => Navigator.of(context).maybePop(),
                icon: Icon(
                  l.ar ? Icons.chevron_right : Icons.chevron_left,
                  color: AppColors.accent,
                  size: 26,
                ),
              ),
              Text(
                l.ticketRef(_shortId(widget.ticketId)),
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      )
                    : GoogleFonts.cinzel(
                        fontSize: 14,
                        letterSpacing: 2.4,
                        color: Colors.white,
                      ),
              ),
            ],
          ),
          if (_ticket != null) ...[
            const SizedBox(height: 6),
            Padding(
              padding: const EdgeInsetsDirectional.only(start: 4),
              child: Text(
                _ticket!['title'] ?? l.untitled,
                style: l.ar
                    ? GoogleFonts.notoNaskhArabic(
                        fontSize: 20,
                        height: 1.35,
                        fontWeight: FontWeight.w600,
                        color: Colors.white,
                      )
                    : GoogleFonts.cinzel(
                        fontSize: 21,
                        height: 1.35,
                        letterSpacing: 0.3,
                        color: Colors.white,
                      ),
              ),
            ),
            const SizedBox(height: 16),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: _StatusRail(status: status, ar: l.ar),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildReplyInput(_L l, MiftahColors m) {
    return Container(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        bottom: MediaQuery.of(context).padding.bottom + 10,
        top: 10,
      ),
      decoration: BoxDecoration(
        color: m.surface,
        border: Border(top: BorderSide(color: m.border)),
      ),
      child: Row(
        children: [
          IconButton(
            onPressed: _uploadReplyAttachment,
            icon: Icon(Icons.photo_camera_outlined, color: m.textMuted),
          ),
          Expanded(
            child: TextField(
              controller: _replyCtrl,
              style: GoogleFonts.josefinSans(
                fontSize: 14,
                color: m.textPrimary,
              ),
              decoration: InputDecoration(
                hintText: l.typeReply,
                hintStyle: GoogleFonts.josefinSans(
                  color: m.textMuted,
                  fontSize: 14,
                ),
                contentPadding: const EdgeInsets.symmetric(
                  horizontal: 18,
                  vertical: 12,
                ),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                  borderSide: BorderSide.none,
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                  borderSide: BorderSide.none,
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                  borderSide: const BorderSide(
                    color: AppColors.accent,
                    width: 1.5,
                  ),
                ),
                filled: true,
                fillColor: m.surfaceAlt,
              ),
              maxLines: 3,
              minLines: 1,
              textInputAction: TextInputAction.send,
              onSubmitted: (_) => _sendReply(),
            ),
          ),
          const SizedBox(width: 10),
          Container(
            width: 46,
            height: 46,
            decoration: const BoxDecoration(
              gradient: MiftahGradients.gold,
              shape: BoxShape.circle,
            ),
            child: IconButton(
              onPressed: _sendReply,
              icon: const Icon(Icons.send, color: AppColors.primary, size: 20),
            ),
          ),
        ],
      ),
    );
  }
}

/// 4-step status rail per design 1g: dots + connecting hairlines, gold for
/// completed steps, white-30% hollow for pending.
class _StatusRail extends StatelessWidget {
  final String status;
  final bool ar;

  const _StatusRail({required this.status, required this.ar});

  int get _currentStep => switch (status) {
    'OPEN' => 0,
    'ASSIGNED' => 1,
    'IN_PROGRESS' => 2,
    'RESOLVED' => 3,
    'CLOSED' => 3,
    'REOPENED' => 0,
    _ => 0,
  };

  @override
  Widget build(BuildContext context) {
    final current = _currentStep;
    final labels = _railLabels(ar);
    return Column(
      children: [
        Row(
          children: List.generate(labels.length * 2 - 1, (index) {
            if (index.isOdd) {
              final segIndex = index ~/ 2;
              final filled = segIndex < current;
              return Expanded(
                child: Container(
                  height: 1,
                  color: filled
                      ? AppColors.accent
                      : Colors.white.withValues(alpha: 0.18),
                ),
              );
            }
            final stepIndex = index ~/ 2;
            final reachedFinalUnclosed =
                stepIndex == 3 && current == 3 && status != 'CLOSED';
            final completed = stepIndex <= current && !reachedFinalUnclosed;
            return Container(
              width: 9,
              height: 9,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: completed ? AppColors.accent : Colors.transparent,
                border: completed
                    ? null
                    : Border.all(
                        color: reachedFinalUnclosed
                            ? AppColors.accent
                            : Colors.white.withValues(alpha: 0.3),
                      ),
              ),
            );
          }),
        ),
        const SizedBox(height: 6),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: labels
              .map(
                (label) => Text(
                  label,
                  style: GoogleFonts.josefinSans(
                    fontSize: 9.5,
                    letterSpacing: ar ? 0 : 0.8,
                    color: Colors.white.withValues(alpha: 0.5),
                  ),
                ),
              )
              .toList(),
        ),
      ],
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
  final MiftahColors m;
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

class _AssigneeCard extends StatelessWidget {
  final String name;
  final String category;
  final _L l;

  const _AssigneeCard({
    required this.name,
    required this.category,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final initial = name.isNotEmpty ? name[0].toUpperCase() : '?';
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Row(
        children: [
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: m.surfaceAlt,
              border: Border.all(color: m.border),
            ),
            alignment: Alignment.center,
            child: Text(
              initial,
              style: GoogleFonts.cinzel(
                fontSize: 16,
                color: m.isDark ? AppColors.accent : AppColors.primary,
              ),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  category.isNotEmpty
                      ? '$name · ${l.category(category)}'
                      : name,
                  style: GoogleFonts.josefinSans(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w500,
                    color: m.textPrimary,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _AttachmentThumb extends StatelessWidget {
  final Map<String, dynamic> att;
  final MiftahColors m;
  const _AttachmentThumb({required this.att, required this.m});

  @override
  Widget build(BuildContext context) {
    final name = (att['name'] ?? att['fileName'] ?? 'File').toString();
    return Container(
      width: 76,
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: m.border),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.insert_drive_file_outlined,
            color: AppColors.accent,
            size: 24,
          ),
          const SizedBox(height: 4),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text(
              name,
              style: GoogleFonts.josefinSans(fontSize: 8, color: m.textMuted),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
            ),
          ),
        ],
      ),
    );
  }
}

/// Management actions panel — same status-driven actions as before
/// (assign / ETA / start / resolve / OTP close / reopen), restyled with the
/// dark-safe surface + gold CTA language used across the app.
class _ActionsPanel extends StatelessWidget {
  final String status;
  final _L l;
  final TextEditingController otpCtrl;
  final VoidCallback onAssignToMe;
  final VoidCallback onSetEta;
  final VoidCallback onStartWork;
  final VoidCallback onMarkResolved;
  final VoidCallback onCloseTicket;
  final VoidCallback onReopen;

  const _ActionsPanel({
    required this.status,
    required this.l,
    required this.otpCtrl,
    required this.onAssignToMe,
    required this.onSetEta,
    required this.onStartWork,
    required this.onMarkResolved,
    required this.onCloseTicket,
    required this.onReopen,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.managementActions,
            style: l.ar
                ? GoogleFonts.notoNaskhArabic(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  )
                : GoogleFonts.josefinSans(
                    fontSize: 10.5,
                    letterSpacing: 2.0,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
          ),
          const SizedBox(height: 14),

          if (status == 'OPEN' || status == 'ASSIGNED') ...[
            Row(
              children: [
                Expanded(
                  child: GoldButton.outlined(
                    label: l.assignToMe,
                    height: 44,
                    onDark: m.isDark,
                    icon: const Icon(Icons.person_add_outlined, size: 16),
                    onPressed: onAssignToMe,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: GoldButton.outlined(
                    label: l.setEta,
                    height: 44,
                    onDark: m.isDark,
                    icon: const Icon(Icons.schedule_outlined, size: 16),
                    onPressed: onSetEta,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            GoldButton(label: l.startWork, height: 46, onPressed: onStartWork),
          ],

          if (status == 'IN_PROGRESS') ...[
            Row(
              children: [
                Expanded(
                  child: GoldButton.outlined(
                    label: l.setEta,
                    height: 44,
                    onDark: m.isDark,
                    icon: const Icon(Icons.schedule_outlined, size: 16),
                    onPressed: onSetEta,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: GoldButton(
                    label: l.markResolved,
                    height: 44,
                    onPressed: onMarkResolved,
                  ),
                ),
              ],
            ),
          ],

          if (status == 'RESOLVED') ...[
            Text(
              l.otpPrompt,
              style: GoogleFonts.josefinSans(
                fontSize: 13,
                color: m.textSecondary,
              ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: otpCtrl,
                    keyboardType: TextInputType.number,
                    style: GoogleFonts.josefinSans(
                      fontSize: 14,
                      color: m.textPrimary,
                    ),
                    decoration: InputDecoration(
                      hintText: l.enterOtp,
                      hintStyle: GoogleFonts.josefinSans(color: m.textMuted),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 12,
                      ),
                      filled: true,
                      fillColor: m.surface,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: m.border),
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: BorderSide(color: m.border),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(12),
                        borderSide: const BorderSide(color: AppColors.accent),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                GoldButton(
                  label: l.close,
                  height: 46,
                  expanded: false,
                  onPressed: onCloseTicket,
                ),
              ],
            ),
          ],

          if (status == 'CLOSED')
            GoldButton.outlined(
              label: l.reopenTicket,
              height: 46,
              onDark: m.isDark,
              icon: const Icon(Icons.replay_outlined, size: 16),
              onPressed: onReopen,
            ),
        ],
      ),
    );
  }
}

/// A single ticket update per renter design pattern: left hairline rail +
/// card. Own messages render on [MiftahColors.surfaceAlt] to stand out.
class _UpdateCard extends StatelessWidget {
  final Map<String, dynamic> reply;
  final bool isCurrentUser;
  final _L l;

  const _UpdateCard({
    required this.reply,
    required this.isCurrentUser,
    required this.l,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final message = reply['message'] ?? '';
    final userName = isCurrentUser ? l.you : (reply['userName'] ?? l.unknown);
    final time = Formatters.timeAgo(reply['createdAt'], ar: l.ar);

    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(width: 1, child: ColoredBox(color: m.border)),
            const SizedBox(width: 12),
            Expanded(
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 15,
                  vertical: 13,
                ),
                decoration: BoxDecoration(
                  color: isCurrentUser ? m.surfaceAlt : m.surface,
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: m.border),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      message,
                      style: GoogleFonts.josefinSans(
                        fontSize: 13,
                        color: m.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      '$userName · $time',
                      style: GoogleFonts.josefinSans(
                        fontSize: 11,
                        color: m.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _HistoryRow extends StatelessWidget {
  final Map<String, dynamic> item;
  final _L l;
  final MiftahColors m;
  const _HistoryRow({required this.item, required this.l, required this.m});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 7,
            height: 7,
            margin: const EdgeInsets.only(top: 6),
            decoration: BoxDecoration(
              color: AppColors.accent.withValues(alpha: 0.6),
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  item['description'] ?? item['action'] ?? '',
                  style: GoogleFonts.josefinSans(
                    fontSize: 13,
                    color: m.textPrimary,
                  ),
                ),
                Text(
                  Formatters.timeAgo(item['createdAt'], ar: l.ar),
                  style: GoogleFonts.josefinSans(
                    fontSize: 11,
                    color: m.textMuted,
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

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

final _ticketDetailProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, String>((ref, id) {
      final service = ref.watch(_ticketServiceProvider);
      return service.getTicketById(id);
    });

final _ticketRepliesProvider = FutureProvider.autoDispose
    .family<List<dynamic>, String>((ref, id) {
      final service = ref.watch(_ticketServiceProvider);
      return service.getReplies(id);
    });

final _ticketAttachmentsProvider = FutureProvider.autoDispose
    .family<List<dynamic>, String>((ref, id) {
      final service = ref.watch(_ticketServiceProvider);
      return service.getAttachments(id);
    });

String _shortId(String id) {
  if (id.isEmpty) return '----';
  final s = id.replaceAll('-', '');
  return s.substring(0, s.length > 6 ? 6 : s.length).toUpperCase();
}

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String ticketRef(String id) => ar ? 'الطلب #$id' : 'TICKET #$id';
  String get untitled => ar ? 'بدون عنوان' : 'Untitled';
  String get description => ar ? 'الوصف' : 'DESCRIPTION';
  String get photos => ar ? 'الصور' : 'PHOTOS';
  String get addPhoto => ar ? '+ إضافة' : '+ ADD';
  String get photoUploadSoon =>
      ar ? 'رفع الصور قريبًا' : 'Photo upload coming soon';
  String get closingOtp => ar ? 'رمز إغلاق الطلب' : 'Closing OTP';
  String get shareOtp => ar
      ? 'شارك هذا الرمز مع مدير العقار لإغلاق الطلب'
      : 'Share this code with your property manager to close the ticket';
  String get copyOtp => ar ? 'نسخ الرمز' : 'Copy OTP';
  String get otpCopied => ar ? 'تم نسخ الرمز' : 'OTP copied to clipboard';
  String get rateService => ar ? 'قيّم هذه الخدمة' : 'Rate This Service';
  String get optionalComment => ar ? 'تعليق اختياري...' : 'Optional comment...';
  String get submitRating => ar ? 'إرسال التقييم' : 'Submit Rating';
  String get yourRating => ar ? 'تقييمك' : 'Your Rating';
  String get updates => ar ? 'التحديثات' : 'UPDATES';
  String get noUpdatesYet => ar ? 'لا توجد تحديثات بعد' : 'No updates yet';
  String get failedToLoadReplies =>
      ar ? 'فشل تحميل الردود' : 'Failed to load replies';
  String get failedToLoadTicket =>
      ar ? 'فشل تحميل الطلب' : 'Failed to load ticket';
  String get addUpdateHint => ar ? 'أضف تحديثًا...' : 'Add an update...';
  String get failedToSendReply =>
      ar ? 'فشل إرسال الرد' : 'Failed to send reply';
  String get thankYouFeedback =>
      ar ? 'شكرًا لملاحظاتك!' : 'Thank you for your feedback!';
  String get failedToSubmitRating =>
      ar ? 'فشل إرسال التقييم' : 'Failed to submit rating';
  String get you => ar ? 'أنت' : 'You';
  String get user => ar ? 'مستخدم' : 'User';
  String get assignedTechnician => ar ? 'الفني المسند' : 'Assigned technician';
  String get cameraPermissionRequired =>
      ar ? 'إذن الكاميرا مطلوب' : 'Camera permission required';

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
      case 'OTHER':
      case '':
        return ar ? 'عام' : 'Other';
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

/// 4-step status rail labels (EN/AR).
List<String> _railLabels(bool ar) => ar
    ? ['أُثيرت', 'أُسندت', 'في الموقع', 'مغلقة']
    : ['RAISED', 'ASSIGNED', 'ON SITE', 'CLOSED'];

class TicketDetailScreen extends ConsumerStatefulWidget {
  final String ticketId;

  const TicketDetailScreen({super.key, required this.ticketId});

  @override
  ConsumerState<TicketDetailScreen> createState() => _TicketDetailScreenState();
}

class _TicketDetailScreenState extends ConsumerState<TicketDetailScreen> {
  final _replyController = TextEditingController();
  bool _isSendingReply = false;

  // Rating
  int _rating = 0;
  final _ratingCommentController = TextEditingController();
  bool _isSubmittingRating = false;

  @override
  void dispose() {
    _replyController.dispose();
    _ratingCommentController.dispose();
    super.dispose();
  }

  Future<void> _sendReply() async {
    final message = _replyController.text.trim();
    if (message.isEmpty) return;

    setState(() => _isSendingReply = true);
    try {
      await ref.read(_ticketServiceProvider).addReply(widget.ticketId, message);
      _replyController.clear();
      ref.invalidate(_ticketRepliesProvider(widget.ticketId));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_L(context.isAr).failedToSendReply),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
    if (mounted) setState(() => _isSendingReply = false);
  }

  Future<void> _submitRating() async {
    if (_rating == 0) return;
    setState(() => _isSubmittingRating = true);
    try {
      await ref
          .read(_ticketServiceProvider)
          .rateTicket(
            widget.ticketId,
            _rating,
            comment: _ratingCommentController.text.trim().isNotEmpty
                ? _ratingCommentController.text.trim()
                : null,
          );
      ref.invalidate(_ticketDetailProvider(widget.ticketId));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_L(context.isAr).thankYouFeedback),
            backgroundColor: AppColors.success,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_L(context.isAr).failedToSubmitRating),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
    if (mounted) setState(() => _isSubmittingRating = false);
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final ticketAsync = ref.watch(_ticketDetailProvider(widget.ticketId));
    final repliesAsync = ref.watch(_ticketRepliesProvider(widget.ticketId));
    final attachmentsAsync = ref.watch(
      _ticketAttachmentsProvider(widget.ticketId),
    );
    final currentUserId = ref.watch(authProvider).userId;

    return Scaffold(
      backgroundColor: m.background,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            _buildHeader(ticketAsync, l),
            Expanded(
              child: ticketAsync.when(
                data: (ticket) {
                  final status = ticket['status'] ?? 'OPEN';
                  final isClosed = status == 'CLOSED';
                  final isResolved = status == 'RESOLVED';
                  final hasRating =
                      ticket['rating'] != null ||
                      ticket['satisfactionRating'] != null;
                  final assigneeName =
                      (ticket['assigneeName'] ?? ticket['assignedToName'])
                          ?.toString();

                  return Column(
                    children: [
                      Expanded(
                        child: RefreshIndicator(
                          color: AppColors.accent,
                          onRefresh: () async {
                            ref.invalidate(
                              _ticketDetailProvider(widget.ticketId),
                            );
                            ref.invalidate(
                              _ticketRepliesProvider(widget.ticketId),
                            );
                          },
                          child: ListView(
                            padding: EdgeInsets.fromLTRB(
                              20,
                              16,
                              20,
                              AppInsets.bottomNav(context),
                            ),
                            children: [
                              // Priority + raised time
                              AnimatedListItem(
                                index: 0,
                                child: Row(
                                  children: [
                                    StatusBadge(
                                      label: l.priority(
                                        ticket['priority'] ?? 'MEDIUM',
                                      ),
                                      color: StatusHelper.getPriorityColor(
                                        ticket['priority'] ?? 'MEDIUM',
                                      ),
                                    ),
                                    const Spacer(),
                                    Text(
                                      Formatters.timeAgo(
                                        ticket['createdAt'],
                                        ar: context.isAr,
                                      ),
                                      style: GoogleFonts.josefinSans(
                                        fontSize: 11.5,
                                        color: m.textMuted,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                              const SizedBox(height: 16),

                              // Assignee / technician card
                              if (assigneeName != null &&
                                  assigneeName.isNotEmpty) ...[
                                AnimatedListItem(
                                  index: 1,
                                  child: _AssigneeCard(
                                    name: assigneeName,
                                    category: ticket['category'] ?? '',
                                    l: l,
                                  ),
                                ),
                                const SizedBox(height: 16),
                              ],

                              // Description
                              if (ticket['description'] != null &&
                                  (ticket['description'] as String)
                                      .isNotEmpty) ...[
                                _sectionLabel(l.description),
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
                                    ticket['description'],
                                    style: GoogleFonts.josefinSans(
                                      fontSize: 14,
                                      color: m.textPrimary,
                                      height: 1.5,
                                    ),
                                  ),
                                ),
                                const SizedBox(height: 20),
                              ],

                              // Attachments
                              attachmentsAsync.when(
                                data: (attachments) {
                                  if (attachments.isEmpty && isClosed) {
                                    return const SizedBox.shrink();
                                  }
                                  return Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      _sectionLabel(l.photos),
                                      const SizedBox(height: 10),
                                      SizedBox(
                                        height: 84,
                                        child: ListView(
                                          scrollDirection: Axis.horizontal,
                                          children: [
                                            for (final att in attachments)
                                              Padding(
                                                padding:
                                                    const EdgeInsetsDirectional.only(
                                                      end: 10,
                                                    ),
                                                child: _AttachmentThumb(
                                                  att: att,
                                                  onTap: () {
                                                    final url =
                                                        att['url'] ?? '';
                                                    final name =
                                                        (att['fileName'] ??
                                                                att['name'] ??
                                                                '')
                                                            .toString();
                                                    final isImage =
                                                        name
                                                            .toLowerCase()
                                                            .endsWith('.jpg') ||
                                                        name
                                                            .toLowerCase()
                                                            .endsWith('.png') ||
                                                        name
                                                            .toLowerCase()
                                                            .endsWith('.jpeg');
                                                    if (isImage &&
                                                        url.isNotEmpty) {
                                                      _showFullScreenImage(
                                                        context,
                                                        url,
                                                      );
                                                    }
                                                  },
                                                ),
                                              ),
                                            if (!isClosed)
                                              _AddPhotoTile(
                                                l: l,
                                                onTap: () {
                                                  ScaffoldMessenger.of(
                                                    context,
                                                  ).showSnackBar(
                                                    SnackBar(
                                                      content: Text(
                                                        l.photoUploadSoon,
                                                      ),
                                                    ),
                                                  );
                                                },
                                              ),
                                          ],
                                        ),
                                      ),
                                      const SizedBox(height: 20),
                                    ],
                                  );
                                },
                                loading: () => const SizedBox.shrink(),
                                error: (_, _) => const SizedBox.shrink(),
                              ),

                              // OTP Section (RESOLVED status)
                              if (isResolved) ...[
                                _OtpSection(
                                  otp:
                                      ticket['closureOtp'] ??
                                      ticket['closingOtp'] ??
                                      ticket['otp'],
                                  l: l,
                                ),
                                const SizedBox(height: 20),
                              ],

                              // Rating Section (CLOSED, no rating)
                              if (isClosed && !hasRating) ...[
                                _buildRatingSection(l),
                                const SizedBox(height: 20),
                              ],

                              // Existing rating display
                              if (hasRating) ...[
                                _buildExistingRating(ticket, l),
                                const SizedBox(height: 20),
                              ],

                              // Updates / replies
                              Divider(color: m.divider),
                              const SizedBox(height: 14),
                              Text(
                                l.updates,
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
                              repliesAsync.when(
                                data: (replies) {
                                  if (replies.isEmpty) {
                                    return Padding(
                                      padding: const EdgeInsets.symmetric(
                                        vertical: 20,
                                      ),
                                      child: Center(
                                        child: Text(
                                          l.noUpdatesYet,
                                          style: GoogleFonts.josefinSans(
                                            fontSize: 12.5,
                                            color: m.textMuted,
                                          ),
                                        ),
                                      ),
                                    );
                                  }
                                  return Column(
                                    children: replies
                                        .asMap()
                                        .entries
                                        .map<Widget>((entry) {
                                          return AnimatedListItem(
                                            index: entry.key,
                                            child: _UpdateCard(
                                              reply: entry.value,
                                              isCurrentUser:
                                                  entry.value['userId'] ==
                                                  currentUserId,
                                              l: l,
                                            ),
                                          );
                                        })
                                        .toList(),
                                  );
                                },
                                loading: () => const ListShimmer(itemCount: 2),
                                error: (_, _) => Text(
                                  l.failedToLoadReplies,
                                  style: GoogleFonts.josefinSans(
                                    color: m.textMuted,
                                  ),
                                ),
                              ),
                              const SizedBox(height: 80),
                            ],
                          ),
                        ),
                      ),

                      // Reply input
                      if (!isClosed) _buildReplyInput(l),
                    ],
                  );
                },
                loading: () => Padding(
                  padding: const EdgeInsets.all(20),
                  child: ListShimmer(itemCount: 3),
                ),
                error: (err, _) => ErrorState(
                  message: l.failedToLoadTicket,
                  onRetry: () =>
                      ref.invalidate(_ticketDetailProvider(widget.ticketId)),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionLabel(String text) {
    final m = context.miftah;
    return Text(
      text,
      style: GoogleFonts.josefinSans(
        fontSize: 11,
        fontWeight: FontWeight.w500,
        letterSpacing: 2.0,
        color: m.textMuted,
      ),
    );
  }

  /// Dark chrome header per design 1g: gold back chevron, tracked
  /// "TICKET #ID", Cinzel title, and the 4-step status rail.
  Widget _buildHeader(AsyncValue<Map<String, dynamic>> ticketAsync, _L l) {
    final ticket = ticketAsync.valueOrNull;
    final status = ticket?['status'] ?? 'OPEN';

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
                onPressed: () => context.pop(),
                icon: const Icon(
                  Icons.chevron_left,
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
          if (ticket != null) ...[
            const SizedBox(height: 6),
            Padding(
              padding: const EdgeInsetsDirectional.only(start: 4),
              child: Text(
                ticket['title'] ?? l.untitled,
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

  Widget _buildRatingSection(_L l) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.rateService,
            style: GoogleFonts.josefinSans(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            ),
          ),
          const SizedBox(height: 14),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(5, (index) {
              return GestureDetector(
                onTap: () {
                  HapticFeedback.lightImpact();
                  setState(() => _rating = index + 1);
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 6),
                  child: TweenAnimationBuilder<double>(
                    tween: Tween(
                      begin: index < _rating ? 0.0 : 1.0,
                      end: index < _rating ? 1.0 : 0.0,
                    ),
                    duration: const Duration(milliseconds: 200),
                    curve: Curves.easeOutCubic,
                    builder: (context, value, child) {
                      return Transform.scale(
                        scale: 1.0 + (value * 0.15),
                        child: Icon(
                          index < _rating
                              ? Icons.star_rounded
                              : Icons.star_border_rounded,
                          color: AppColors.accent,
                          size: 40,
                        ),
                      );
                    },
                  ),
                ),
              );
            }),
          ),
          const SizedBox(height: 14),
          TextField(
            controller: _ratingCommentController,
            style: GoogleFonts.josefinSans(fontSize: 14, color: m.textPrimary),
            decoration: InputDecoration(
              hintText: l.optionalComment,
              hintStyle: GoogleFonts.josefinSans(
                color: m.textMuted,
                fontSize: 14,
              ),
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
            maxLines: 2,
          ),
          const SizedBox(height: 14),
          _isSubmittingRating
              ? const Center(
                  child: SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                )
              : GoldButton(
                  label: l.submitRating,
                  height: 46,
                  onPressed: _rating > 0 ? _submitRating : null,
                ),
        ],
      ),
    );
  }

  Widget _buildExistingRating(Map<String, dynamic> ticket, _L l) {
    final m = context.miftah;
    final rating =
        ticket['rating'] as int? ?? ticket['satisfactionRating'] as int? ?? 0;
    final comment =
        ticket['ratingComment'] ?? ticket['satisfactionComment'] ?? '';

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: m.successBg,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.yourRating,
            style: GoogleFonts.josefinSans(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: List.generate(5, (index) {
              return Icon(
                index < rating ? Icons.star_rounded : Icons.star_border_rounded,
                color: AppColors.accent,
                size: 28,
              );
            }),
          ),
          if (comment.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(
              comment,
              style: GoogleFonts.josefinSans(
                fontSize: 13,
                color: m.textPrimary,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildReplyInput(_L l) {
    final m = context.miftah;
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
          Expanded(
            child: TextField(
              controller: _replyController,
              style: GoogleFonts.josefinSans(
                fontSize: 14,
                color: m.textPrimary,
              ),
              decoration: InputDecoration(
                hintText: l.addUpdateHint,
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
              onPressed: _isSendingReply ? null : _sendReply,
              icon: _isSendingReply
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: AppColors.primary,
                      ),
                    )
                  : const Icon(Icons.send, color: AppColors.primary, size: 20),
            ),
          ),
        ],
      ),
    );
  }

  void _showFullScreenImage(BuildContext context, String url) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => Scaffold(
          backgroundColor: Colors.black,
          appBar: AppBar(
            backgroundColor: Colors.black,
            iconTheme: const IconThemeData(color: Colors.white),
          ),
          body: Center(
            child: InteractiveViewer(
              child: Image.network(
                url,
                errorBuilder: (_, _, _) => const Icon(
                  Icons.broken_image_outlined,
                  color: Colors.white54,
                  size: 64,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 4-step status rail per design 1g: dots + connecting hairlines, gold for
/// completed steps, white-30% hollow for pending. Maps the app's five
/// ticket statuses onto the four displayed stages.
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
            // RESOLVED reaches the final stage but the ticket isn't closed
            // yet (OTP handshake pending) — show that dot as active-hollow,
            // filled only once the status is actually CLOSED.
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
          children: [
            for (var i = 0; i < labels.length; i++)
              Expanded(
                child: Text(
                  labels[i],
                  textAlign: i == 0
                      ? TextAlign.start
                      : (i == labels.length - 1
                            ? TextAlign.end
                            : TextAlign.center),
                  overflow: TextOverflow.ellipsis,
                  style: GoogleFonts.josefinSans(
                    fontSize: 10,
                    letterSpacing: ar ? 0 : 1.0,
                    color: Colors.white.withValues(alpha: 0.5),
                  ),
                ),
              ),
          ],
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
                const SizedBox(height: 3),
                Text(
                  l.assignedTechnician,
                  style: GoogleFonts.josefinSans(
                    fontSize: 11.5,
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

class _AttachmentThumb extends StatelessWidget {
  final Map<String, dynamic> att;
  final VoidCallback onTap;

  const _AttachmentThumb({required this.att, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final url = att['url'] ?? '';
    final name = (att['fileName'] ?? att['name'] ?? '').toString();
    final isImage =
        name.toLowerCase().endsWith('.jpg') ||
        name.toLowerCase().endsWith('.png') ||
        name.toLowerCase().endsWith('.jpeg');

    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 76,
        height: 76,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          color: m.surfaceAlt,
          border: Border.all(color: m.border),
        ),
        clipBehavior: Clip.hardEdge,
        child: isImage && url.isNotEmpty
            ? Stack(
                fit: StackFit.expand,
                children: [
                  Image.network(
                    url,
                    fit: BoxFit.cover,
                    errorBuilder: (_, _, _) =>
                        Icon(Icons.broken_image, color: m.textMuted),
                  ),
                  Container(
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.05),
                    ),
                  ),
                ],
              )
            : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.insert_drive_file, color: m.textMuted, size: 24),
                  const SizedBox(height: 4),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: Text(
                      name,
                      style: GoogleFonts.josefinSans(
                        fontSize: 8,
                        color: m.textMuted,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      textAlign: TextAlign.center,
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}

class _AddPhotoTile extends StatelessWidget {
  final VoidCallback onTap;
  final _L l;

  const _AddPhotoTile({required this.onTap, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 76,
        height: 76,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: AppColors.accent.withValues(alpha: 0.4),
            style: BorderStyle.solid,
          ),
        ),
        child: Center(
          child: Text(
            l.addPhoto,
            style: GoogleFonts.josefinSans(
              fontSize: 11,
              letterSpacing: l.ar ? 0 : 1.0,
              color: m.isDark ? AppColors.accent : AppColors.accentDark,
            ),
          ),
        ),
      ),
    );
  }
}

class _OtpSection extends StatelessWidget {
  final String? otp;
  final _L l;

  const _OtpSection({this.otp, required this.l});

  @override
  Widget build(BuildContext context) {
    if (otp == null || otp!.isEmpty) return const SizedBox.shrink();
    final m = context.miftah;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.3)),
      ),
      child: Column(
        children: [
          const Icon(
            Icons.verified_outlined,
            color: AppColors.accent,
            size: 36,
          ),
          const SizedBox(height: 10),
          Text(
            l.closingOtp,
            style: GoogleFonts.josefinSans(
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: m.textPrimary,
            ),
          ),
          const SizedBox(height: 16),
          // OTP display with individual boxes
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: otp!.split('').asMap().entries.map((entry) {
              return TweenAnimationBuilder<double>(
                tween: Tween(begin: 0.0, end: 1.0),
                duration: Duration(milliseconds: 300 + entry.key * 100),
                curve: Curves.easeOutCubic,
                builder: (context, value, child) {
                  return Opacity(
                    opacity: value,
                    child: Transform.scale(
                      scale: 0.8 + (0.2 * value),
                      child: Container(
                        width: 44,
                        height: 52,
                        margin: const EdgeInsets.symmetric(horizontal: 5),
                        decoration: BoxDecoration(
                          color: m.surface,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: m.border),
                        ),
                        child: Center(
                          child: Text(
                            entry.value,
                            style: GoogleFonts.josefinSans(
                              fontSize: 24,
                              fontWeight: FontWeight.w700,
                              color: m.isDark
                                  ? AppColors.accent
                                  : AppColors.accentDark,
                            ),
                          ),
                        ),
                      ),
                    ),
                  );
                },
              );
            }).toList(),
          ),
          const SizedBox(height: 16),
          Text(
            l.shareOtp,
            style: GoogleFonts.josefinSans(fontSize: 12.5, color: m.textMuted),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 14),
          GoldButton.outlined(
            label: l.copyOtp,
            height: 42,
            expanded: false,
            onDark: m.isDark,
            icon: const Icon(Icons.copy, size: 16),
            onPressed: () {
              Clipboard.setData(ClipboardData(text: otp!));
              HapticFeedback.lightImpact();
              ScaffoldMessenger.of(
                context,
              ).showSnackBar(SnackBar(content: Text(l.otpCopied)));
            },
          ),
        ],
      ),
    );
  }
}

/// A single ticket update per design 1g: left hairline rail + card. Own
/// messages render on [MiftahColors.surfaceAlt] to stand out from replies.
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
    final userName = isCurrentUser
        ? l.you
        : (reply['userName'] ?? reply['user']?['name'] ?? l.user);
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

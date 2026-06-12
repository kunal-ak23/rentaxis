import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

final _ticketDetailProvider =
    FutureProvider.autoDispose.family<Map<String, dynamic>, String>((ref, id) {
  final service = ref.watch(_ticketServiceProvider);
  return service.getTicketById(id);
});

final _ticketRepliesProvider =
    FutureProvider.autoDispose.family<List<dynamic>, String>((ref, id) {
  final service = ref.watch(_ticketServiceProvider);
  return service.getReplies(id);
});

final _ticketAttachmentsProvider =
    FutureProvider.autoDispose.family<List<dynamic>, String>((ref, id) {
  final service = ref.watch(_ticketServiceProvider);
  return service.getAttachments(id);
});

class TicketDetailScreen extends ConsumerStatefulWidget {
  final String ticketId;

  const TicketDetailScreen({super.key, required this.ticketId});

  @override
  ConsumerState<TicketDetailScreen> createState() =>
      _TicketDetailScreenState();
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
      await ref
          .read(_ticketServiceProvider)
          .addReply(widget.ticketId, message);
      _replyController.clear();
      ref.invalidate(_ticketRepliesProvider(widget.ticketId));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Failed to send reply'),
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
      await ref.read(_ticketServiceProvider).rateTicket(
            widget.ticketId,
            _rating,
            comment: _ratingCommentController.text.trim().isNotEmpty
                ? _ratingCommentController.text.trim()
                : null,
          );
      ref.invalidate(_ticketDetailProvider(widget.ticketId));
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Thank you for your feedback!'),
            backgroundColor: AppColors.success,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Failed to submit rating'),
            backgroundColor: AppColors.danger,
          ),
        );
      }
    }
    if (mounted) setState(() => _isSubmittingRating = false);
  }

  @override
  Widget build(BuildContext context) {
    final ticketAsync = ref.watch(_ticketDetailProvider(widget.ticketId));
    final repliesAsync = ref.watch(_ticketRepliesProvider(widget.ticketId));
    final attachmentsAsync =
        ref.watch(_ticketAttachmentsProvider(widget.ticketId));
    final currentUserId = ref.watch(authProvider).userId;

    return Scaffold(
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        title: Text(
          'Ticket Details',
          style: GoogleFonts.cinzel(
            fontSize: 18,
            fontWeight: FontWeight.w600,
            color: AppColors.textPrimary,
          ),
        ),
      ),
      body: ticketAsync.when(
        data: (ticket) {
          final status = ticket['status'] ?? 'OPEN';
          final isResolved = status == 'RESOLVED';
          final isClosed = status == 'CLOSED';
          final hasRating = ticket['rating'] != null;

          return Column(
            children: [
              Expanded(
                child: RefreshIndicator(
                  color: AppColors.primary,
                  onRefresh: () async {
                    ref.invalidate(
                        _ticketDetailProvider(widget.ticketId));
                    ref.invalidate(
                        _ticketRepliesProvider(widget.ticketId));
                  },
                  child: ListView(
                    padding: EdgeInsets.fromLTRB(20, 16, 20, AppInsets.bottomNav(context)),
                    children: [
                      // Status timeline
                      AnimatedListItem(
                        index: 0,
                        child: _StatusTimeline(currentStatus: status),
                      ),
                      const SizedBox(height: 24),

                      // Title & Info
                      AnimatedListItem(
                        index: 1,
                        child: Text(
                          ticket['title'] ?? 'Untitled',
                          style: Theme.of(context).textTheme.headlineSmall,
                        ),
                      ),
                      const SizedBox(height: 10),
                      AnimatedListItem(
                        index: 2,
                        child: Row(
                          children: [
                            StatusBadge(
                              label: status,
                              color: StatusHelper.getTicketStatusColor(
                                  status),
                            ),
                            const SizedBox(width: 8),
                            StatusBadge(
                              label: ticket['priority'] ?? 'MEDIUM',
                              color: StatusHelper.getPriorityColor(
                                  ticket['priority'] ?? 'MEDIUM'),
                            ),
                            const Spacer(),
                            Text(
                              Formatters.timeAgo(ticket['createdAt']),
                              style:
                                  Theme.of(context).textTheme.labelSmall,
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 20),

                      // Description
                      if (ticket['description'] != null &&
                          (ticket['description'] as String)
                              .isNotEmpty) ...[
                        Text('Description',
                            style: Theme.of(context)
                                .textTheme
                                .titleMedium),
                        const SizedBox(height: 8),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(16),
                          decoration: BoxDecoration(
                            color: AppColors.background,
                            borderRadius: BorderRadius.circular(14),
                          ),
                          child: Text(
                            ticket['description'],
                            style:
                                Theme.of(context).textTheme.bodyMedium,
                          ),
                        ),
                        const SizedBox(height: 20),
                      ],

                      // Attachments
                      attachmentsAsync.when(
                        data: (attachments) {
                          if (attachments.isEmpty) {
                            return const SizedBox.shrink();
                          }
                          return Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('Attachments',
                                  style: Theme.of(context)
                                      .textTheme
                                      .titleMedium),
                              const SizedBox(height: 10),
                              SizedBox(
                                height: 84,
                                child: ListView.builder(
                                  scrollDirection: Axis.horizontal,
                                  itemCount: attachments.length,
                                  itemBuilder: (context, index) {
                                    final att = attachments[index];
                                    final url = att['url'] ?? '';
                                    final name = att['fileName'] ??
                                        att['name'] ??
                                        '';
                                    final isImage = name
                                            .toString()
                                            .toLowerCase()
                                            .endsWith('.jpg') ||
                                        name
                                            .toString()
                                            .toLowerCase()
                                            .endsWith('.png') ||
                                        name
                                            .toString()
                                            .toLowerCase()
                                            .endsWith('.jpeg');

                                    return Padding(
                                      padding: const EdgeInsets.only(
                                          right: 10),
                                      child: GestureDetector(
                                        onTap: () {
                                          if (isImage &&
                                              url.isNotEmpty) {
                                            _showFullScreenImage(
                                                context, url);
                                          }
                                        },
                                        child: Container(
                                          width: 76,
                                          height: 76,
                                          decoration: BoxDecoration(
                                            borderRadius:
                                                BorderRadius.circular(
                                                    12),
                                            color: AppColors.background,
                                            boxShadow: AppShadows.soft,
                                          ),
                                          clipBehavior: Clip.hardEdge,
                                          child: isImage &&
                                                  url.isNotEmpty
                                              ? Stack(
                                                  fit: StackFit.expand,
                                                  children: [
                                                    Image.network(
                                                      url,
                                                      fit: BoxFit.cover,
                                                      errorBuilder: (_,
                                                              __,
                                                              ___) =>
                                                          const Icon(
                                                              Icons
                                                                  .broken_image,
                                                              color: AppColors
                                                                  .textMuted),
                                                    ),
                                                    Container(
                                                      decoration:
                                                          BoxDecoration(
                                                        color: Colors
                                                            .black
                                                            .withValues(
                                                                alpha:
                                                                    0.05),
                                                      ),
                                                    ),
                                                  ],
                                                )
                                              : Column(
                                                  mainAxisAlignment:
                                                      MainAxisAlignment
                                                          .center,
                                                  children: [
                                                    const Icon(
                                                      Icons
                                                          .insert_drive_file,
                                                      color: AppColors
                                                          .textMuted,
                                                      size: 24,
                                                    ),
                                                    const SizedBox(
                                                        height: 4),
                                                    Padding(
                                                      padding:
                                                          const EdgeInsets
                                                              .symmetric(
                                                              horizontal:
                                                                  4),
                                                      child: Text(
                                                        name,
                                                        style:
                                                            GoogleFonts
                                                                .josefinSans(
                                                          fontSize: 8,
                                                          color: AppColors
                                                              .textMuted,
                                                        ),
                                                        maxLines: 2,
                                                        overflow:
                                                            TextOverflow
                                                                .ellipsis,
                                                        textAlign:
                                                            TextAlign
                                                                .center,
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
                              const SizedBox(height: 20),
                            ],
                          );
                        },
                        loading: () => const SizedBox.shrink(),
                        error: (_, __) => const SizedBox.shrink(),
                      ),

                      // OTP Section (RESOLVED status)
                      if (isResolved) ...[
                        _OtpSection(
                            otp: ticket['closingOtp'] ?? ticket['otp']),
                        const SizedBox(height: 20),
                      ],

                      // Rating Section (CLOSED, no rating)
                      if (isClosed && !hasRating) ...[
                        _buildRatingSection(),
                        const SizedBox(height: 20),
                      ],

                      // Existing rating display
                      if (hasRating) ...[
                        _buildExistingRating(ticket),
                        const SizedBox(height: 20),
                      ],

                      // Replies
                      Divider(
                        color: AppColors.border.withValues(alpha: 0.5),
                      ),
                      const SizedBox(height: 10),
                      Text('Replies',
                          style:
                              Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 14),
                      repliesAsync.when(
                        data: (replies) {
                          if (replies.isEmpty) {
                            return Padding(
                              padding: const EdgeInsets.symmetric(
                                  vertical: 20),
                              child: Center(
                                child: Text(
                                  'No replies yet',
                                  style: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.copyWith(
                                          color: AppColors.textMuted),
                                ),
                              ),
                            );
                          }
                          return Column(
                            children:
                                replies.asMap().entries.map<Widget>((entry) {
                              return AnimatedListItem(
                                index: entry.key,
                                child: _ReplyBubble(
                                  reply: entry.value,
                                  isCurrentUser:
                                      entry.value['userId'] ==
                                          currentUserId,
                                ),
                              );
                            }).toList(),
                          );
                        },
                        loading: () => const ListShimmer(itemCount: 2),
                        error: (_, __) =>
                            const Text('Failed to load replies'),
                      ),
                      const SizedBox(height: 80),
                    ],
                  ),
                ),
              ),

              // Reply input
              if (!isClosed) _buildReplyInput(),
            ],
          );
        },
        loading: () => Padding(
          padding: const EdgeInsets.all(20),
          child: ListShimmer(itemCount: 3),
        ),
        error: (err, _) => ErrorState(
          message: 'Failed to load ticket',
          onRetry: () =>
              ref.invalidate(_ticketDetailProvider(widget.ticketId)),
        ),
      ),
    );
  }

  Widget _buildRatingSection() {
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.accent.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Rate This Service',
              style: Theme.of(context).textTheme.titleMedium),
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
            style: GoogleFonts.josefinSans(fontSize: 14),
            decoration: InputDecoration(
              hintText: 'Optional comment...',
              hintStyle: GoogleFonts.josefinSans(
                color: AppColors.textMuted,
                fontSize: 14,
              ),
              contentPadding: const EdgeInsets.symmetric(
                  horizontal: 14, vertical: 12),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            maxLines: 2,
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _rating > 0 && !_isSubmittingRating
                  ? _submitRating
                  : null,
              child: _isSubmittingRating
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white),
                    )
                  : const Text('Submit Rating'),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildExistingRating(Map<String, dynamic> ticket) {
    final rating = ticket['rating'] as int? ?? 0;
    final comment = ticket['ratingComment'] ?? '';

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.success.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Your Rating',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 10),
          Row(
            children: List.generate(5, (index) {
              return Icon(
                index < rating
                    ? Icons.star_rounded
                    : Icons.star_border_rounded,
                color: AppColors.accent,
                size: 28,
              );
            }),
          ),
          if (comment.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(comment, style: Theme.of(context).textTheme.bodySmall),
          ],
        ],
      ),
    );
  }

  Widget _buildReplyInput() {
    return Container(
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        bottom: MediaQuery.of(context).padding.bottom + 10,
        top: 10,
      ),
      decoration: BoxDecoration(
        color: AppColors.surface,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.04),
            blurRadius: 8,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _replyController,
              style: GoogleFonts.josefinSans(fontSize: 14),
              decoration: InputDecoration(
                hintText: 'Type a reply...',
                hintStyle: GoogleFonts.josefinSans(
                  color: AppColors.textMuted,
                  fontSize: 14,
                ),
                contentPadding: const EdgeInsets.symmetric(
                    horizontal: 18, vertical: 12),
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
                      color: AppColors.primary, width: 1.5),
                ),
                filled: true,
                fillColor: AppColors.background,
              ),
              maxLines: 3,
              minLines: 1,
              textInputAction: TextInputAction.send,
              onSubmitted: (_) => _sendReply(),
            ),
          ),
          const SizedBox(width: 10),
          Container(
            width: 44,
            height: 44,
            decoration: BoxDecoration(
              color: AppColors.primary,
              shape: BoxShape.circle,
              boxShadow: [
                BoxShadow(
                  color: AppColors.primary.withValues(alpha: 0.3),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
            ),
            child: IconButton(
              onPressed: _isSendingReply ? null : _sendReply,
              icon: _isSendingReply
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Colors.white),
                    )
                  : const Icon(Icons.send, color: Colors.white, size: 20),
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
              child: Image.network(url),
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusTimeline extends StatelessWidget {
  final String currentStatus;

  const _StatusTimeline({required this.currentStatus});

  @override
  Widget build(BuildContext context) {
    final statuses = [
      'OPEN',
      'ASSIGNED',
      'IN_PROGRESS',
      'RESOLVED',
      'CLOSED'
    ];
    final currentIndex =
        statuses.indexOf(currentStatus).clamp(0, statuses.length - 1);

    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: Row(
        children: List.generate(statuses.length * 2 - 1, (index) {
          if (index.isOdd) {
            final stepIndex = index ~/ 2;
            return Expanded(
              child: TweenAnimationBuilder<double>(
                tween: Tween(
                  begin: 0.0,
                  end: stepIndex < currentIndex ? 1.0 : 0.0,
                ),
                duration: Duration(milliseconds: 300 + stepIndex * 100),
                curve: Curves.easeOutCubic,
                builder: (context, value, child) {
                  return Container(
                    height: 2,
                    decoration: BoxDecoration(
                      gradient: LinearGradient(
                        colors: [
                          AppColors.primary,
                          Color.lerp(AppColors.primary, AppColors.border,
                                  1 - value) ??
                              AppColors.border,
                        ],
                      ),
                    ),
                  );
                },
              ),
            );
          }
          final stepIndex = index ~/ 2;
          final isCompleted = stepIndex <= currentIndex;
          final isCurrent = stepIndex == currentIndex;

          return Column(
            children: [
              TweenAnimationBuilder<double>(
                tween: Tween(
                  begin: 0.0,
                  end: isCompleted ? 1.0 : 0.0,
                ),
                duration: Duration(milliseconds: 400 + stepIndex * 80),
                curve: Curves.easeOutCubic,
                builder: (context, value, child) {
                  final size = isCurrent ? 28.0 : 22.0;
                  return Container(
                    width: size,
                    height: size,
                    decoration: BoxDecoration(
                      color: Color.lerp(
                          AppColors.background, AppColors.primary, value),
                      shape: BoxShape.circle,
                      border: Border.all(
                        color: Color.lerp(AppColors.border,
                                AppColors.primary, value) ??
                            AppColors.border,
                        width: isCurrent ? 2.5 : 1.5,
                      ),
                      boxShadow: isCompleted
                          ? [
                              BoxShadow(
                                color: AppColors.primary
                                    .withValues(alpha: 0.2 * value),
                                blurRadius: 6,
                                offset: const Offset(0, 2),
                              ),
                            ]
                          : null,
                    ),
                    child: isCompleted
                        ? Icon(Icons.check,
                            size: isCurrent ? 16 : 12,
                            color:
                                Colors.white.withValues(alpha: value))
                        : null,
                  );
                },
              ),
              const SizedBox(height: 6),
              Text(
                statuses[stepIndex].replaceAll('_', '\n'),
                style: GoogleFonts.josefinSans(
                  fontSize: 8,
                  fontWeight:
                      isCurrent ? FontWeight.w700 : FontWeight.w400,
                  color: isCompleted
                      ? AppColors.primary
                      : AppColors.textMuted,
                ),
                textAlign: TextAlign.center,
              ),
            ],
          );
        }),
      ),
    );
  }
}

class _OtpSection extends StatelessWidget {
  final String? otp;

  const _OtpSection({this.otp});

  @override
  Widget build(BuildContext context) {
    if (otp == null || otp!.isEmpty) return const SizedBox.shrink();

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(24),
      decoration: BoxDecoration(
        color: AppColors.primary.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          const Icon(Icons.verified_outlined,
              color: AppColors.primary, size: 36),
          const SizedBox(height: 10),
          Text(
            'Closing OTP',
            style: Theme.of(context).textTheme.titleMedium,
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
                        margin:
                            const EdgeInsets.symmetric(horizontal: 5),
                        decoration: BoxDecoration(
                          color: AppColors.surface,
                          borderRadius: BorderRadius.circular(12),
                          boxShadow: AppShadows.soft,
                        ),
                        child: Center(
                          child: Text(
                            entry.value,
                            style: GoogleFonts.josefinSans(
                              fontSize: 24,
                              fontWeight: FontWeight.w700,
                              color: AppColors.primary,
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
            'Share this code with your property manager to close the ticket',
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: AppColors.textMuted),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: otp!));
              HapticFeedback.lightImpact();
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                    content: Text('OTP copied to clipboard')),
              );
            },
            icon: const Icon(Icons.copy, size: 16),
            label: const Text('Copy OTP'),
          ),
        ],
      ),
    );
  }
}

class _ReplyBubble extends StatelessWidget {
  final Map<String, dynamic> reply;
  final bool isCurrentUser;

  const _ReplyBubble(
      {required this.reply, required this.isCurrentUser});

  @override
  Widget build(BuildContext context) {
    final message = reply['message'] ?? '';
    final userName =
        reply['userName'] ?? reply['user']?['name'] ?? 'User';
    final time = Formatters.timeAgo(reply['createdAt']);

    return Align(
      alignment:
          isCurrentUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          if (!isCurrentUser) ...[
            // Avatar circle
            Container(
              width: 28,
              height: 28,
              decoration: BoxDecoration(
                color: AppColors.primary.withValues(alpha: 0.1),
                shape: BoxShape.circle,
              ),
              child: Center(
                child: Text(
                  userName.isNotEmpty ? userName[0].toUpperCase() : 'U',
                  style: GoogleFonts.josefinSans(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: AppColors.primary,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 8),
          ],
          Flexible(
            child: Container(
              constraints: BoxConstraints(
                  maxWidth:
                      MediaQuery.of(context).size.width * 0.72),
              margin: const EdgeInsets.only(bottom: 12),
              padding: const EdgeInsets.symmetric(
                  horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: isCurrentUser
                    ? AppColors.primary.withValues(alpha: 0.08)
                    : AppColors.surface,
                borderRadius: BorderRadius.only(
                  topLeft: const Radius.circular(18),
                  topRight: const Radius.circular(18),
                  bottomLeft:
                      Radius.circular(isCurrentUser ? 18 : 4),
                  bottomRight:
                      Radius.circular(isCurrentUser ? 4 : 18),
                ),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.03),
                    blurRadius: 6,
                    offset: const Offset(0, 2),
                  ),
                ],
              ),
              child: Column(
                crossAxisAlignment: isCurrentUser
                    ? CrossAxisAlignment.end
                    : CrossAxisAlignment.start,
                children: [
                  Text(
                    userName,
                    style: GoogleFonts.josefinSans(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: isCurrentUser
                          ? AppColors.primary
                          : AppColors.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    message,
                    style: Theme.of(context).textTheme.bodyMedium,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    time,
                    style: GoogleFonts.josefinSans(
                      fontSize: 10,
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ),
            ),
          ),
          if (isCurrentUser) ...[
            const SizedBox(width: 8),
            Container(
              width: 28,
              height: 28,
              decoration: BoxDecoration(
                color: AppColors.accent.withValues(alpha: 0.15),
                shape: BoxShape.circle,
              ),
              child: Center(
                child: Text(
                  userName.isNotEmpty ? userName[0].toUpperCase() : 'U',
                  style: GoogleFonts.josefinSans(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: AppColors.accent,
                  ),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

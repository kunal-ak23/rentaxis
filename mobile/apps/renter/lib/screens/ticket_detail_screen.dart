import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
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
        backgroundColor: AppColors.navyDark,
        title: const Text('Ticket Details'),
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
                    ref.invalidate(_ticketDetailProvider(widget.ticketId));
                    ref.invalidate(_ticketRepliesProvider(widget.ticketId));
                  },
                  child: ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      // Status timeline
                      _StatusTimeline(currentStatus: status),
                      const SizedBox(height: 20),

                      // Title & Info
                      Text(
                        ticket['title'] ?? 'Untitled',
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          StatusBadge(
                            label: status,
                            color:
                                StatusHelper.getTicketStatusColor(status),
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
                            style: Theme.of(context).textTheme.labelSmall,
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),

                      // Description
                      if (ticket['description'] != null &&
                          (ticket['description'] as String).isNotEmpty) ...[
                        Text('Description',
                            style: Theme.of(context).textTheme.titleMedium),
                        const SizedBox(height: 6),
                        Container(
                          width: double.infinity,
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: AppColors.background,
                            borderRadius: BorderRadius.circular(8),
                            border: Border.all(color: AppColors.border),
                          ),
                          child: Text(
                            ticket['description'],
                            style: Theme.of(context).textTheme.bodyMedium,
                          ),
                        ),
                        const SizedBox(height: 16),
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
                              const SizedBox(height: 8),
                              SizedBox(
                                height: 80,
                                child: ListView.builder(
                                  scrollDirection: Axis.horizontal,
                                  itemCount: attachments.length,
                                  itemBuilder: (context, index) {
                                    final att = attachments[index];
                                    final url = att['url'] ?? '';
                                    final name =
                                        att['fileName'] ?? att['name'] ?? '';
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
                                      padding:
                                          const EdgeInsets.only(right: 8),
                                      child: GestureDetector(
                                        onTap: () {
                                          if (isImage && url.isNotEmpty) {
                                            _showFullScreenImage(
                                                context, url);
                                          }
                                        },
                                        child: Container(
                                          width: 72,
                                          height: 72,
                                          decoration: BoxDecoration(
                                            borderRadius:
                                                BorderRadius.circular(8),
                                            border: Border.all(
                                                color: AppColors.border),
                                            color: AppColors.background,
                                          ),
                                          clipBehavior: Clip.hardEdge,
                                          child: isImage && url.isNotEmpty
                                              ? Image.network(
                                                  url,
                                                  fit: BoxFit.cover,
                                                  errorBuilder: (_, __, ___) =>
                                                      const Icon(
                                                          Icons.broken_image,
                                                          color: AppColors
                                                              .textMuted),
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
                                                    Text(
                                                      name,
                                                      style:
                                                          const TextStyle(
                                                              fontSize: 8),
                                                      maxLines: 2,
                                                      overflow: TextOverflow
                                                          .ellipsis,
                                                      textAlign:
                                                          TextAlign.center,
                                                    ),
                                                  ],
                                                ),
                                        ),
                                      ),
                                    );
                                  },
                                ),
                              ),
                              const SizedBox(height: 16),
                            ],
                          );
                        },
                        loading: () => const SizedBox.shrink(),
                        error: (_, __) => const SizedBox.shrink(),
                      ),

                      // OTP Section (RESOLVED status)
                      if (isResolved) ...[
                        _OtpSection(otp: ticket['closingOtp'] ?? ticket['otp']),
                        const SizedBox(height: 16),
                      ],

                      // Rating Section (CLOSED, no rating)
                      if (isClosed && !hasRating) ...[
                        _buildRatingSection(),
                        const SizedBox(height: 16),
                      ],

                      // Existing rating display
                      if (hasRating) ...[
                        _buildExistingRating(ticket),
                        const SizedBox(height: 16),
                      ],

                      // Replies
                      const Divider(),
                      const SizedBox(height: 8),
                      Text('Replies',
                          style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 12),
                      repliesAsync.when(
                        data: (replies) {
                          if (replies.isEmpty) {
                            return Padding(
                              padding: const EdgeInsets.symmetric(vertical: 16),
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
                            children: replies.map<Widget>((reply) {
                              return _ReplyBubble(
                                reply: reply,
                                isCurrentUser:
                                    reply['userId'] == currentUserId,
                              );
                            }).toList(),
                          );
                        },
                        loading: () => const Center(
                          child: Padding(
                            padding: EdgeInsets.all(16),
                            child: CircularProgressIndicator(
                                color: AppColors.primary),
                          ),
                        ),
                        error: (_, __) => const Text('Failed to load replies'),
                      ),
                      const SizedBox(height: 80),
                    ],
                  ),
                ),
              ),

              // Reply input
              if (!isClosed)
                _buildReplyInput(),
            ],
          );
        },
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
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
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.accent.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.accent.withValues(alpha: 0.2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Rate This Service',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: List.generate(5, (index) {
              return GestureDetector(
                onTap: () => setState(() => _rating = index + 1),
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4),
                  child: Icon(
                    index < _rating ? Icons.star : Icons.star_border,
                    color: AppColors.accent,
                    size: 36,
                  ),
                ),
              );
            }),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _ratingCommentController,
            decoration: const InputDecoration(
              hintText: 'Optional comment...',
              contentPadding:
                  EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            ),
            maxLines: 2,
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed:
                  _rating > 0 && !_isSubmittingRating ? _submitRating : null,
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
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.success.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.success.withValues(alpha: 0.2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Your Rating',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Row(
            children: List.generate(5, (index) {
              return Icon(
                index < rating ? Icons.star : Icons.star_border,
                color: AppColors.accent,
                size: 24,
              );
            }),
          ),
          if (comment.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(comment, style: Theme.of(context).textTheme.bodySmall),
          ],
        ],
      ),
    );
  }

  Widget _buildReplyInput() {
    return Container(
      padding: EdgeInsets.only(
        left: 12,
        right: 12,
        bottom: MediaQuery.of(context).padding.bottom + 8,
        top: 8,
      ),
      decoration: const BoxDecoration(
        color: AppColors.surface,
        border: Border(top: BorderSide(color: AppColors.border)),
      ),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _replyController,
              decoration: InputDecoration(
                hintText: 'Type a reply...',
                contentPadding: const EdgeInsets.symmetric(
                    horizontal: 14, vertical: 10),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(24),
                  borderSide: const BorderSide(color: AppColors.border),
                ),
              ),
              maxLines: 3,
              minLines: 1,
              textInputAction: TextInputAction.send,
              onSubmitted: (_) => _sendReply(),
            ),
          ),
          const SizedBox(width: 8),
          IconButton(
            onPressed: _isSendingReply ? null : _sendReply,
            icon: _isSendingReply
                ? const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: AppColors.primary),
                  )
                : const Icon(Icons.send, color: AppColors.primary),
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
    final statuses = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];
    final currentIndex =
        statuses.indexOf(currentStatus).clamp(0, statuses.length - 1);

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.border),
      ),
      child: Row(
        children: List.generate(statuses.length * 2 - 1, (index) {
          if (index.isOdd) {
            final stepIndex = index ~/ 2;
            return Expanded(
              child: Container(
                height: 2,
                color: stepIndex < currentIndex
                    ? AppColors.primary
                    : AppColors.border,
              ),
            );
          }
          final stepIndex = index ~/ 2;
          final isCompleted = stepIndex <= currentIndex;
          final isCurrent = stepIndex == currentIndex;

          return Column(
            children: [
              Container(
                width: isCurrent ? 28 : 20,
                height: isCurrent ? 28 : 20,
                decoration: BoxDecoration(
                  color: isCompleted ? AppColors.primary : AppColors.background,
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: isCompleted ? AppColors.primary : AppColors.border,
                    width: isCurrent ? 3 : 1.5,
                  ),
                ),
                child: isCompleted
                    ? Icon(Icons.check,
                        size: isCurrent ? 16 : 12, color: Colors.white)
                    : null,
              ),
              const SizedBox(height: 4),
              Text(
                statuses[stepIndex].replaceAll('_', '\n'),
                style: TextStyle(
                  fontSize: 8,
                  fontWeight: isCurrent ? FontWeight.w700 : FontWeight.w400,
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
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.primary.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.primary.withValues(alpha: 0.2)),
      ),
      child: Column(
        children: [
          const Icon(Icons.verified_outlined,
              color: AppColors.primary, size: 32),
          const SizedBox(height: 8),
          Text(
            'Closing OTP',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 12),
          // OTP display
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: otp!.split('').map((char) {
              return Container(
                width: 40,
                height: 48,
                margin: const EdgeInsets.symmetric(horizontal: 4),
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: AppColors.primary),
                ),
                child: Center(
                  child: Text(
                    char,
                    style: const TextStyle(
                      fontSize: 22,
                      fontWeight: FontWeight.w700,
                      color: AppColors.primary,
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
          const SizedBox(height: 12),
          Text(
            'Share this code with your property manager to close the ticket',
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: AppColors.textSecondary),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: () {
              Clipboard.setData(ClipboardData(text: otp!));
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('OTP copied to clipboard')),
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

  const _ReplyBubble({required this.reply, required this.isCurrentUser});

  @override
  Widget build(BuildContext context) {
    final message = reply['message'] ?? '';
    final userName = reply['userName'] ?? reply['user']?['name'] ?? 'User';
    final time = Formatters.timeAgo(reply['createdAt']);

    return Align(
      alignment:
          isCurrentUser ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        constraints:
            BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.75),
        margin: const EdgeInsets.only(bottom: 10),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: isCurrentUser
              ? AppColors.primary.withValues(alpha: 0.1)
              : AppColors.background,
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(14),
            topRight: const Radius.circular(14),
            bottomLeft: Radius.circular(isCurrentUser ? 14 : 4),
            bottomRight: Radius.circular(isCurrentUser ? 4 : 14),
          ),
          border: Border.all(
            color: isCurrentUser
                ? AppColors.primary.withValues(alpha: 0.2)
                : AppColors.border,
          ),
        ),
        child: Column(
          crossAxisAlignment: isCurrentUser
              ? CrossAxisAlignment.end
              : CrossAxisAlignment.start,
          children: [
            Text(
              userName,
              style: TextStyle(
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
              style: const TextStyle(
                fontSize: 10,
                color: AppColors.textMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

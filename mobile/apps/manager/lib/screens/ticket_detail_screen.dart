import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

class TicketDetailScreen extends ConsumerStatefulWidget {
  final String ticketId;
  const TicketDetailScreen({super.key, required this.ticketId});

  @override
  ConsumerState<TicketDetailScreen> createState() =>
      _TicketDetailScreenState();
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
        _error = 'Failed to load ticket';
        _isLoading = false;
      });
    }
  }

  Future<void> _updateStatus(String newStatus) async {
    setState(() => _isActioning = true);
    try {
      await ref
          .read(_ticketServiceProvider)
          .updateStatus(widget.ticketId, newStatus);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Status updated to $newStatus')),
        );
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to update status')),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Future<void> _assignToMe() async {
    final authState = ref.read(authProvider);
    final userId = authState.userId;
    if (userId == null) return;

    setState(() => _isActioning = true);
    try {
      await ref
          .read(_ticketServiceProvider)
          .assignTicket(widget.ticketId, userId);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Ticket assigned to you')),
        );
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to assign ticket')),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Future<void> _closeTicket() async {
    final otp = _otpCtrl.text.trim();
    if (otp.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('OTP is required to close the ticket')),
      );
      return;
    }

    setState(() => _isActioning = true);
    try {
      await ref
          .read(_ticketServiceProvider)
          .closeTicket(widget.ticketId, otp);
      _otpCtrl.clear();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Ticket closed')),
        );
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to close ticket. Check OTP.')),
        );
      }
    } finally {
      if (mounted) setState(() => _isActioning = false);
    }
  }

  Future<void> _setEta() async {
    final hoursCtrl = TextEditingController();
    final result = await showDialog<int>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Set ETA'),
        content: TextField(
          controller: hoursCtrl,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(
            labelText: 'Estimated hours',
            hintText: 'e.g. 24',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () {
              final hours = int.tryParse(hoursCtrl.text);
              if (hours != null && hours > 0) {
                Navigator.pop(ctx, hours);
              }
            },
            child: const Text('Set'),
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
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('ETA set to $result hours')),
        );
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to set ETA')),
        );
      }
    }
  }

  Future<void> _sendReply() async {
    final message = _replyCtrl.text.trim();
    if (message.isEmpty) return;

    try {
      await ref
          .read(_ticketServiceProvider)
          .addReply(widget.ticketId, message);
      _replyCtrl.clear();
      _loadData();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to send reply')),
        );
      }
    }
  }

  Future<void> _uploadReplyAttachment() async {
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
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Photo attached')),
        );
        _loadData();
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Failed to upload')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: const Text('Ticket')),
        body: const Center(
          child: CircularProgressIndicator(color: AppColors.primary),
        ),
      );
    }

    if (_error != null || _ticket == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Ticket')),
        body: ErrorState(message: _error ?? 'Not found', onRetry: _loadData),
      );
    }

    final ticket = _ticket!;
    final status = ticket['status'] ?? 'OPEN';
    final statusColor = StatusHelper.getTicketStatusColor(status);
    final priority = ticket['priority'] ?? 'MEDIUM';
    final priorityColor = StatusHelper.getPriorityColor(priority);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Ticket Details'),
      ),
      body: LoadingOverlay(
        isLoading: _isActioning,
        child: Column(
          children: [
            Expanded(
              child: RefreshIndicator(
                onRefresh: _loadData,
                color: AppColors.primary,
                child: SingleChildScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // Header
                      _buildHeader(ticket, status, statusColor, priority,
                          priorityColor),
                      const SizedBox(height: 16),

                      // Status timeline
                      _buildStatusTimeline(status),
                      const SizedBox(height: 16),

                      // Description
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: AppColors.surface,
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Description',
                                style: Theme.of(context)
                                    .textTheme
                                    .titleMedium),
                            const SizedBox(height: 8),
                            Text(
                              ticket['description'] ?? 'No description',
                              style: const TextStyle(
                                fontSize: 14,
                                color: AppColors.textSecondary,
                                height: 1.5,
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 16),

                      // Attachments
                      if (_attachments.isNotEmpty) ...[
                        Text('Attachments',
                            style: Theme.of(context).textTheme.titleMedium),
                        const SizedBox(height: 8),
                        SizedBox(
                          height: 80,
                          child: ListView.separated(
                            scrollDirection: Axis.horizontal,
                            itemCount: _attachments.length,
                            separatorBuilder: (_, __) =>
                                const SizedBox(width: 8),
                            itemBuilder: (context, index) {
                              final att = _attachments[index];
                              return Container(
                                width: 80,
                                decoration: BoxDecoration(
                                  color: AppColors.info
                                      .withValues(alpha: 0.08),
                                  borderRadius: BorderRadius.circular(8),
                                  border: Border.all(
                                      color: AppColors.border),
                                ),
                                child: Column(
                                  mainAxisAlignment:
                                      MainAxisAlignment.center,
                                  children: [
                                    const Icon(
                                        Icons.insert_drive_file_outlined,
                                        color: AppColors.info,
                                        size: 28),
                                    const SizedBox(height: 4),
                                    Text(
                                      att['name'] ??
                                          att['fileName'] ??
                                          'File',
                                      style: const TextStyle(
                                          fontSize: 10),
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      textAlign: TextAlign.center,
                                    ),
                                  ],
                                ),
                              );
                            },
                          ),
                        ),
                        const SizedBox(height: 16),
                      ],

                      // Management actions panel
                      _buildActionsPanel(status),
                      const SizedBox(height: 16),

                      // Replies (chat style)
                      Text('Replies (${_replies.length})',
                          style:
                              Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      if (_replies.isEmpty)
                        const Padding(
                          padding: EdgeInsets.symmetric(vertical: 16),
                          child: Center(
                            child: Text('No replies yet',
                                style: TextStyle(
                                    color: AppColors.textMuted,
                                    fontSize: 13)),
                          ),
                        )
                      else
                        ..._replies.map((reply) => _ReplyBubble(
                              reply: reply,
                              isOwn: reply['userId'] ==
                                  ref.read(authProvider).userId,
                            )),

                      // Activity history
                      if (_history.isNotEmpty) ...[
                        const SizedBox(height: 16),
                        Text('Activity History',
                            style: Theme.of(context)
                                .textTheme
                                .titleMedium),
                        const SizedBox(height: 8),
                        ..._history.map((h) => Padding(
                              padding: const EdgeInsets.only(bottom: 8),
                              child: Row(
                                crossAxisAlignment:
                                    CrossAxisAlignment.start,
                                children: [
                                  Container(
                                    width: 8,
                                    height: 8,
                                    margin: const EdgeInsets.only(top: 6),
                                    decoration: const BoxDecoration(
                                      color: AppColors.textMuted,
                                      shape: BoxShape.circle,
                                    ),
                                  ),
                                  const SizedBox(width: 12),
                                  Expanded(
                                    child: Column(
                                      crossAxisAlignment:
                                          CrossAxisAlignment.start,
                                      children: [
                                        Text(
                                          h['description'] ??
                                              h['action'] ??
                                              '',
                                          style: const TextStyle(
                                              fontSize: 13),
                                        ),
                                        Text(
                                          Formatters.timeAgo(
                                              h['createdAt']),
                                          style: const TextStyle(
                                            fontSize: 11,
                                            color: AppColors.textMuted,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                ],
                              ),
                            )),
                      ],
                    ],
                  ),
                ),
              ),
            ),

            // Reply input bar
            if (status != 'CLOSED')
              Container(
                padding: EdgeInsets.fromLTRB(
                    12,
                    8,
                    12,
                    8 + MediaQuery.of(context).padding.bottom),
                decoration: const BoxDecoration(
                  color: AppColors.surface,
                  border: Border(
                      top: BorderSide(color: AppColors.border)),
                ),
                child: Row(
                  children: [
                    IconButton(
                      icon: const Icon(Icons.photo_camera_outlined,
                          color: AppColors.textMuted),
                      onPressed: _uploadReplyAttachment,
                    ),
                    Expanded(
                      child: TextField(
                        controller: _replyCtrl,
                        decoration: const InputDecoration(
                          hintText: 'Type a reply...',
                          border: InputBorder.none,
                          contentPadding: EdgeInsets.symmetric(
                              horizontal: 12, vertical: 8),
                        ),
                        maxLines: null,
                        textInputAction: TextInputAction.send,
                        onSubmitted: (_) => _sendReply(),
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.send_rounded,
                          color: AppColors.primary),
                      onPressed: _sendReply,
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildHeader(Map<String, dynamic> ticket, String status,
      Color statusColor, String priority, Color priorityColor) {
    return Container(
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
                  ticket['title'] ?? 'No title',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              StatusBadge(label: status, color: statusColor),
              const SizedBox(width: 8),
              StatusBadge(label: priority, color: priorityColor),
              if (ticket['category'] != null) ...[
                const SizedBox(width: 8),
                StatusBadge(
                  label: (ticket['category'] as String).replaceAll('_', ' '),
                  color: AppColors.accent,
                ),
              ],
            ],
          ),
          if (ticket['propertyName'] != null) ...[
            const SizedBox(height: 10),
            Row(
              children: [
                const Icon(Icons.apartment_outlined,
                    size: 14, color: Colors.white60),
                const SizedBox(width: 6),
                Text(
                  '${ticket['propertyName']}${ticket['unitNumber'] != null ? ' - Unit ${ticket['unitNumber']}' : ''}',
                  style:
                      const TextStyle(color: Colors.white70, fontSize: 13),
                ),
              ],
            ),
          ],
          if (ticket['assignedToName'] != null) ...[
            const SizedBox(height: 6),
            Row(
              children: [
                const Icon(Icons.person_outline,
                    size: 14, color: Colors.white60),
                const SizedBox(width: 6),
                Text(
                  'Assigned to: ${ticket['assignedToName']}',
                  style:
                      const TextStyle(color: Colors.white70, fontSize: 13),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildStatusTimeline(String currentStatus) {
    final statuses = ['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];
    final currentIdx =
        statuses.indexOf(currentStatus).clamp(0, statuses.length - 1);

    return SizedBox(
      height: 50,
      child: Row(
        children: statuses.asMap().entries.map((entry) {
          final idx = entry.key;
          final s = entry.value;
          final isActive = idx <= currentIdx;
          final isCurrent = s == currentStatus;
          final color = isActive
              ? StatusHelper.getTicketStatusColor(s)
              : AppColors.border;

          return Expanded(
            child: Column(
              children: [
                Row(
                  children: [
                    if (idx > 0)
                      Expanded(
                        child: Container(
                          height: 2,
                          color: isActive ? color : AppColors.border,
                        ),
                      ),
                    Container(
                      width: isCurrent ? 14 : 10,
                      height: isCurrent ? 14 : 10,
                      decoration: BoxDecoration(
                        color: isActive ? color : Colors.transparent,
                        shape: BoxShape.circle,
                        border: Border.all(color: color, width: 2),
                      ),
                    ),
                    if (idx < statuses.length - 1)
                      Expanded(
                        child: Container(
                          height: 2,
                          color: idx < currentIdx ? color : AppColors.border,
                        ),
                      ),
                  ],
                ),
                const SizedBox(height: 6),
                Text(
                  s.replaceAll('_', '\n'),
                  style: TextStyle(
                    fontSize: 8,
                    fontWeight:
                        isCurrent ? FontWeight.w700 : FontWeight.w400,
                    color: isActive
                        ? AppColors.textPrimary
                        : AppColors.textMuted,
                  ),
                  textAlign: TextAlign.center,
                  maxLines: 2,
                ),
              ],
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildActionsPanel(String status) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.primary.withValues(alpha: 0.04),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.primary.withValues(alpha: 0.15)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Management Actions',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),

          if (status == 'OPEN' || status == 'ASSIGNED') ...[
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _assignToMe,
                    icon: const Icon(Icons.person_add_outlined, size: 18),
                    label: const Text('Assign to Me'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _setEta,
                    icon: const Icon(Icons.schedule_outlined, size: 18),
                    label: const Text('Set ETA'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
          ],

          if (status == 'OPEN' || status == 'ASSIGNED')
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: () => _updateStatus('IN_PROGRESS'),
                icon: const Icon(Icons.play_arrow_outlined, size: 18),
                label: const Text('Start Work'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppColors.warning,
                ),
              ),
            ),

          if (status == 'IN_PROGRESS') ...[
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _setEta,
                    icon: const Icon(Icons.schedule_outlined, size: 18),
                    label: const Text('Set ETA'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => _updateStatus('RESOLVED'),
                    icon:
                        const Icon(Icons.check_circle_outline, size: 18),
                    label: const Text('Resolved'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppColors.success,
                    ),
                  ),
                ),
              ],
            ),
          ],

          if (status == 'RESOLVED') ...[
            const Text(
              'Enter renter OTP to close the ticket:',
              style: TextStyle(fontSize: 13, color: AppColors.textSecondary),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _otpCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      hintText: 'Enter OTP',
                      contentPadding: EdgeInsets.symmetric(
                          horizontal: 12, vertical: 10),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton(
                  onPressed: _closeTicket,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.primary,
                  ),
                  child: const Text('Close'),
                ),
              ],
            ),
          ],

          if (status == 'CLOSED')
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: () => _updateStatus('REOPENED'),
                icon: const Icon(Icons.replay_outlined, size: 18),
                label: const Text('Reopen Ticket'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: AppColors.warning,
                  side: const BorderSide(color: AppColors.warning),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _ReplyBubble extends StatelessWidget {
  final Map<String, dynamic> reply;
  final bool isOwn;

  const _ReplyBubble({required this.reply, required this.isOwn});

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: isOwn ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        constraints:
            BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.75),
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: isOwn
              ? AppColors.primary.withValues(alpha: 0.1)
              : AppColors.surface,
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(12),
            topRight: const Radius.circular(12),
            bottomLeft: Radius.circular(isOwn ? 12 : 2),
            bottomRight: Radius.circular(isOwn ? 2 : 12),
          ),
          border: Border.all(
            color: isOwn
                ? AppColors.primary.withValues(alpha: 0.2)
                : AppColors.border,
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (!isOwn)
              Text(
                reply['userName'] ?? 'Unknown',
                style: const TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w600,
                  color: AppColors.primary,
                ),
              ),
            if (!isOwn) const SizedBox(height: 4),
            Text(
              reply['message'] ?? '',
              style: const TextStyle(fontSize: 14),
            ),
            const SizedBox(height: 4),
            Text(
              Formatters.timeAgo(reply['createdAt']),
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

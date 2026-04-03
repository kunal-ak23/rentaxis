import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

final _ticketsProvider =
    FutureProvider.autoDispose<List<dynamic>>((ref) async {
  final service = ref.watch(_ticketServiceProvider);
  return service.getTickets();
});

class TicketsScreen extends ConsumerStatefulWidget {
  const TicketsScreen({super.key});

  @override
  ConsumerState<TicketsScreen> createState() => _TicketsScreenState();
}

class _TicketsScreenState extends ConsumerState<TicketsScreen> {
  int _segment = 0; // 0 = My Assigned, 1 = All
  String? _statusFilter;
  String? _priorityFilter;

  final _statusOptions = [
    null,
    'OPEN',
    'ASSIGNED',
    'IN_PROGRESS',
    'RESOLVED',
    'CLOSED',
  ];

  final _priorityOptions = [null, 'LOW', 'MEDIUM', 'HIGH', 'URGENT'];

  Future<void> _refresh() async {
    ref.invalidate(_ticketsProvider);
  }

  @override
  Widget build(BuildContext context) {
    final ticketsAsync = ref.watch(_ticketsProvider);
    final authState = ref.watch(authProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Tickets'), titleSpacing: 20),
      body: Column(
        children: [
          // Segment control
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
            child: Container(
              decoration: BoxDecoration(
                color: AppColors.background,
                borderRadius: BorderRadius.circular(16),
                boxShadow: AppShadows.soft,
              ),
              child: Row(
                children: [
                  _SegmentTab(
                    label: 'My Assigned',
                    isSelected: _segment == 0,
                    onTap: () => setState(() => _segment = 0),
                  ),
                  _SegmentTab(
                    label: 'All Tickets',
                    isSelected: _segment == 1,
                    onTap: () => setState(() => _segment = 1),
                  ),
                ],
              ),
            ),
          ),

          // Filter chips row
          SizedBox(
            height: 44,
            child: ListView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 20),
              children: [
                // Status filter
                DropdownButton<String?>(
                  value: _statusFilter,
                  hint: const Text('Status',
                      style: TextStyle(fontSize: 13)),
                  underline: const SizedBox.shrink(),
                  items: _statusOptions
                      .map((s) => DropdownMenuItem<String?>(
                            value: s,
                            child: Text(
                              s?.replaceAll('_', ' ') ?? 'All Status',
                              style: TextStyle(
                                fontSize: 13,
                                color: s != null
                                    ? StatusHelper.getTicketStatusColor(s)
                                    : null,
                              ),
                            ),
                          ))
                      .toList(),
                  onChanged: (v) =>
                      setState(() => _statusFilter = v),
                ),
                const SizedBox(width: 16),
                // Priority filter
                DropdownButton<String?>(
                  value: _priorityFilter,
                  hint: const Text('Priority',
                      style: TextStyle(fontSize: 13)),
                  underline: const SizedBox.shrink(),
                  items: _priorityOptions
                      .map((p) => DropdownMenuItem<String?>(
                            value: p,
                            child: Text(
                              p ?? 'All Priority',
                              style: TextStyle(
                                fontSize: 13,
                                color: p != null
                                    ? StatusHelper.getPriorityColor(p)
                                    : null,
                              ),
                            ),
                          ))
                      .toList(),
                  onChanged: (v) =>
                      setState(() => _priorityFilter = v),
                ),
              ],
            ),
          ),

          // Ticket list
          Expanded(
            child: ticketsAsync.when(
              loading: () => const ListShimmer(itemCount: 3),
              error: (e, _) => ErrorState(
                message: 'Failed to load tickets',
                onRetry: _refresh,
              ),
              data: (tickets) {
                var filtered = tickets.where((t) {
                  // Segment filter
                  if (_segment == 0) {
                    final assignedTo = t['assignedToId'] ?? t['assignedTo'];
                    if (assignedTo != authState.userId) return false;
                  }
                  if (_statusFilter != null &&
                      t['status'] != _statusFilter) {
                    return false;
                  }
                  if (_priorityFilter != null &&
                      t['priority'] != _priorityFilter) {
                    return false;
                  }
                  return true;
                }).toList();

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.confirmation_number_outlined,
                    title: _segment == 0
                        ? 'No tickets assigned to you'
                        : 'No tickets found',
                  );
                }

                return RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.primary,
                  child: ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: const EdgeInsets.fromLTRB(20, 8, 20, 150),
                    itemCount: filtered.length,
                    itemBuilder: (context, index) {
                      final ticket = filtered[index];
                      return AnimatedListItem(
                        index: index,
                        child: _TicketCard(
                          ticket: ticket,
                          onTap: () =>
                              context.push('/tickets/${ticket['id']}'),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: Padding(
        padding: const EdgeInsets.only(bottom: 100),
        child: FloatingActionButton(
          backgroundColor: AppColors.primary,
          onPressed: () => context.push('/tickets/create'),
          child: const Icon(Icons.add, color: Colors.white),
        ),
      ),
    );
  }
}

class _SegmentTab extends StatelessWidget {
  final String label;
  final bool isSelected;
  final VoidCallback onTap;

  const _SegmentTab({
    required this.label,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: isSelected ? AppColors.primary : Colors.transparent,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: GoogleFonts.josefinSans(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: isSelected ? Colors.white : AppColors.textSecondary,
            ),
          ),
        ),
      ),
    );
  }
}

class _TicketCard extends StatelessWidget {
  final Map<String, dynamic> ticket;
  final VoidCallback onTap;

  const _TicketCard({required this.ticket, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final status = ticket['status'] ?? 'OPEN';
    final priority = ticket['priority'] ?? 'MEDIUM';
    final statusColor = StatusHelper.getTicketStatusColor(status);
    final priorityColor = StatusHelper.getPriorityColor(priority);

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      ticket['title'] ?? 'No title',
                      style: GoogleFonts.josefinSans(
                        fontWeight: FontWeight.w600,
                        fontSize: 15,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 8),
                  StatusBadge(label: priority, color: priorityColor),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  if (ticket['propertyName'] != null) ...[
                    const Icon(Icons.apartment_outlined,
                        size: 14, color: AppColors.textMuted),
                    const SizedBox(width: 4),
                    Flexible(
                      child: Text(
                        '${ticket['propertyName']}${ticket['unitNumber'] != null ? ' - Unit ${ticket['unitNumber']}' : ''}',
                        style: GoogleFonts.josefinSans(
                          fontSize: 12,
                          color: AppColors.textSecondary,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const SizedBox(width: 12),
                  ],
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  StatusBadge(label: status, color: statusColor),
                  const SizedBox(width: 8),
                  if (ticket['assignedToName'] != null) ...[
                    const Icon(Icons.person_outline,
                        size: 14, color: AppColors.textMuted),
                    const SizedBox(width: 4),
                    Text(
                      ticket['assignedToName'],
                      style: GoogleFonts.josefinSans(
                        fontSize: 12,
                        color: AppColors.textSecondary,
                      ),
                    ),
                  ],
                  const Spacer(),
                  Text(
                    Formatters.timeAgo(ticket['createdAt']),
                    style: GoogleFonts.josefinSans(
                      fontSize: 11,
                      color: AppColors.textMuted,
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

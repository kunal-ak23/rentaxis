import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

final _ticketServiceProvider = Provider<TicketService>((ref) {
  final client = ref.watch(apiClientProvider);
  return TicketService(client.dio);
});

final ticketsProvider = FutureProvider.autoDispose<List<dynamic>>((ref) {
  final service = ref.watch(_ticketServiceProvider);
  return service.getTickets();
});

class TicketsScreen extends ConsumerStatefulWidget {
  const TicketsScreen({super.key});

  @override
  ConsumerState<TicketsScreen> createState() => _TicketsScreenState();
}

class _TicketsScreenState extends ConsumerState<TicketsScreen> {
  String _searchQuery = '';

  @override
  Widget build(BuildContext context) {
    final ticketsAsync = ref.watch(ticketsProvider);

    return Scaffold(
      body: Stack(
        children: [
          Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Page title
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
            child: Text(
              'Maintenance Tickets',
              style: GoogleFonts.cinzel(
                fontSize: 18,
                fontWeight: FontWeight.w600,
                color: AppColors.textPrimary,
              ),
            ),
          ),
          // Search bar
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 8),
            child: Container(
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.circular(14),
                boxShadow: AppShadows.soft,
              ),
              child: TextField(
                onChanged: (value) => setState(() => _searchQuery = value),
                style: GoogleFonts.josefinSans(fontSize: 14),
                decoration: InputDecoration(
                  hintText: 'Search tickets...',
                  hintStyle: GoogleFonts.josefinSans(
                    color: AppColors.textMuted,
                    fontSize: 14,
                  ),
                  prefixIcon: const Icon(Icons.search,
                      size: 20, color: AppColors.textMuted),
                  contentPadding: const EdgeInsets.symmetric(
                      horizontal: 18, vertical: 14),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: BorderSide.none,
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: BorderSide.none,
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(14),
                    borderSide: const BorderSide(
                        color: AppColors.primary, width: 1.5),
                  ),
                  filled: true,
                  fillColor: AppColors.surface,
                ),
              ),
            ),
          ),

          Expanded(
            child: ticketsAsync.when(
              data: (tickets) {
                var filtered = tickets;
                if (_searchQuery.isNotEmpty) {
                  final query = _searchQuery.toLowerCase();
                  filtered = tickets.where((t) {
                    final title = (t['title'] ?? '').toLowerCase();
                    final category = (t['category'] ?? '').toLowerCase();
                    return title.contains(query) || category.contains(query);
                  }).toList();
                }

                if (filtered.isEmpty) {
                  return EmptyState(
                    icon: Icons.build_outlined,
                    title: _searchQuery.isNotEmpty
                        ? 'No Matching Tickets'
                        : 'No Tickets Yet',
                    subtitle: _searchQuery.isNotEmpty
                        ? 'Try a different search term'
                        : 'Raise a ticket if you need maintenance',
                    actionLabel:
                        _searchQuery.isEmpty ? 'Create Ticket' : null,
                    onAction: _searchQuery.isEmpty
                        ? () => context.push('/tickets/create')
                        : null,
                  );
                }

                return RefreshIndicator(
                  color: AppColors.primary,
                  onRefresh: () async => ref.invalidate(ticketsProvider),
                  child: ListView.builder(
                    padding: EdgeInsets.fromLTRB(20, 8, 20, AppInsets.bottomNav(context)),
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
              loading: () => Padding(
                padding: const EdgeInsets.all(20),
                child: ListShimmer(itemCount: 4),
              ),
              error: (err, _) => ErrorState(
                message: 'Failed to load tickets',
                onRetry: () => ref.invalidate(ticketsProvider),
              ),
            ),
          ),
          ],
        ),
        // FAB positioned above the floating nav
        Positioned(
          right: 20,
          bottom: 110,
          child: FloatingActionButton(
            onPressed: () => context.push('/tickets/create'),
            backgroundColor: AppColors.primary,
            elevation: 6,
            child: const Icon(Icons.add, color: Colors.white),
          ),
        ),
        ],
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
    final title = ticket['title'] ?? 'Untitled';
    final status = ticket['status'] ?? 'OPEN';
    final priority = ticket['priority'] ?? 'MEDIUM';
    final category = ticket['category'] ?? '';
    final propertyName =
        ticket['property']?['name'] ?? ticket['propertyName'] ?? '';
    final unitNumber =
        ticket['unit']?['unitNumber'] ?? ticket['unitNumber'] ?? '';
    final createdAt = Formatters.timeAgo(ticket['createdAt']);

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(16),
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Title row
                Row(
                  children: [
                    _categoryIcon(category),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            title,
                            style: Theme.of(context).textTheme.titleMedium,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          if (propertyName.isNotEmpty ||
                              unitNumber.isNotEmpty) ...[
                            const SizedBox(height: 3),
                            Text(
                              [
                                propertyName,
                                if (unitNumber.isNotEmpty)
                                  'Unit $unitNumber',
                              ].join(' - '),
                              style: Theme.of(context)
                                  .textTheme
                                  .bodySmall
                                  ?.copyWith(color: AppColors.textMuted),
                            ),
                          ],
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      createdAt,
                      style: Theme.of(context).textTheme.labelSmall,
                    ),
                  ],
                ),
                const SizedBox(height: 12),

                // Badges
                Row(
                  children: [
                    StatusBadge(
                      label: status,
                      color: StatusHelper.getTicketStatusColor(status),
                    ),
                    const SizedBox(width: 8),
                    StatusBadge(
                      label: priority,
                      color: StatusHelper.getPriorityColor(priority),
                    ),
                    if (category.isNotEmpty) ...[
                      const SizedBox(width: 8),
                      Text(
                        category.replaceAll('_', ' '),
                        style: Theme.of(context)
                            .textTheme
                            .labelSmall
                            ?.copyWith(color: AppColors.textMuted),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _categoryIcon(String category) {
    final iconData = switch (category.toUpperCase()) {
      'PLUMBING' => Icons.plumbing,
      'ELECTRICAL' => Icons.electrical_services,
      'HVAC' => Icons.ac_unit,
      'APPLIANCE' => Icons.kitchen,
      'STRUCTURAL' => Icons.foundation,
      'PEST_CONTROL' => Icons.pest_control,
      'CLEANING' => Icons.cleaning_services,
      'SECURITY' => Icons.security,
      _ => Icons.build_outlined,
    };

    return Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        color: AppColors.primary.withValues(alpha: 0.08),
        shape: BoxShape.circle,
      ),
      child: Icon(iconData, color: AppColors.primary, size: 22),
    );
  }
}

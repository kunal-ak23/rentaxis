import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

// ── Provider ──────────────────────────────────────────────────────────────────

final _interestsProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, String>((ref, listingId) async {
  final service = ref.watch(listingApiServiceProvider);
  final data = await service.getInterests(listingId, size: 100);
  return (data['content'] as List? ?? []).cast<Map<String, dynamic>>();
});

// ── Screen ────────────────────────────────────────────────────────────────────

class ListingInterestsScreen extends ConsumerWidget {
  final String listingId;
  const ListingInterestsScreen({super.key, required this.listingId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final interestsAsync = ref.watch(_interestsProvider(listingId));

    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        elevation: 0,
        title: Text(
          'Interested Renters',
          style: GoogleFonts.cinzel(
            fontSize: 18,
            fontWeight: FontWeight.w700,
            color: AppColors.textPrimary,
          ),
        ),
      ),
      body: interestsAsync.when(
        loading: () => ListView.builder(
          padding: const EdgeInsets.all(16),
          itemCount: 4,
          itemBuilder: (_, __) => Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: ShimmerLoading(height: 80, width: double.infinity),
          ),
        ),
        error: (e, _) => ErrorState(
          message: 'Failed to load interests',
          onRetry: () => ref.invalidate(_interestsProvider(listingId)),
        ),
        data: (items) {
          if (items.isEmpty) {
            return const EmptyState(
              icon: Icons.people_outline,
              title: 'No interest yet',
              subtitle: 'Renters who express interest will appear here',
            );
          }
          return RefreshIndicator(
            onRefresh: () async =>
                ref.invalidate(_interestsProvider(listingId)),
            child: ListView.builder(
              padding:
                  const EdgeInsets.fromLTRB(16, 16, 16, 32),
              itemCount: items.length,
              itemBuilder: (_, i) => AnimatedListItem(
                index: i,
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: _InterestCard(interest: items[i]),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

// ── Card ──────────────────────────────────────────────────────────────────────

class _InterestCard extends StatelessWidget {
  final Map<String, dynamic> interest;
  const _InterestCard({required this.interest});

  @override
  Widget build(BuildContext context) {
    final name = interest['renterName'] as String? ?? 'Renter';
    final email = interest['renterEmail'] as String?;
    final phone = interest['renterPhone'] as String?;
    final note = interest['note'] as String?;
    final status = interest['status'] as String? ?? 'ACTIVE';
    final createdAt = interest['createdAt'] as String?;

    final statusColor = switch (status) {
      'ACTIVE' => AppColors.success,
      'NOTIFIED' => AppColors.info,
      'WITHDRAWN' => AppColors.textMuted,
      _ => AppColors.textMuted,
    };

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: AppShadows.soft,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              CircleAvatar(
                radius: 20,
                backgroundColor: AppColors.primary.withValues(alpha: 0.12),
                child: Text(
                  name.isNotEmpty ? name[0].toUpperCase() : '?',
                  style: GoogleFonts.josefinSans(
                    color: AppColors.primary,
                    fontWeight: FontWeight.w700,
                    fontSize: 16,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      name,
                      style: GoogleFonts.josefinSans(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: AppColors.textPrimary,
                      ),
                    ),
                    if (createdAt != null)
                      Text(
                        Formatters.timeAgo(createdAt),
                        style: GoogleFonts.josefinSans(
                            fontSize: 11, color: AppColors.textMuted),
                      ),
                  ],
                ),
              ),
              StatusBadge(label: status, color: statusColor),
            ],
          ),

          if (note != null && note.isNotEmpty) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.background,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                note,
                style: GoogleFonts.josefinSans(
                    fontSize: 13, color: AppColors.textSecondary, height: 1.5),
              ),
            ),
          ],

          if (phone != null || email != null) ...[
            const Divider(height: 20),
            Row(
              children: [
                if (phone != null)
                  Expanded(
                    child: _ContactBtn(
                      icon: Icons.phone_outlined,
                      label: 'Call',
                      onTap: () => _launch('tel:$phone'),
                    ),
                  ),
                if (phone != null && email != null)
                  const SizedBox(width: 10),
                if (email != null)
                  Expanded(
                    child: _ContactBtn(
                      icon: Icons.email_outlined,
                      label: 'Email',
                      onTap: () => _launch('mailto:$email'),
                    ),
                  ),
                if (phone != null) ...[
                  const SizedBox(width: 10),
                  Expanded(
                    child: _ContactBtn(
                      icon: Icons.message_outlined,
                      label: 'WhatsApp',
                      onTap: () => _launch(
                          'https://wa.me/${phone.replaceAll(RegExp(r'[^0-9]'), '')}'),
                    ),
                  ),
                ],
              ],
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _launch(String url) async {
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) launchUrl(uri);
  }
}

class _ContactBtn extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  const _ContactBtn(
      {required this.icon, required this.label, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.primary.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(10),
          border:
              Border.all(color: AppColors.primary.withValues(alpha: 0.2)),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 16, color: AppColors.primary),
            const SizedBox(width: 6),
            Text(
              label,
              style: GoogleFonts.josefinSans(
                fontSize: 12,
                color: AppColors.primary,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

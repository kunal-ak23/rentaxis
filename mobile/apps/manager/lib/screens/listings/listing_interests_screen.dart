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

// ── Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief. ─

class _L {
  _L(this.ar);
  final bool ar;

  String get interestedRenters =>
      ar ? 'المستأجرون المهتمون' : 'INTERESTED RENTERS';
  String get loadFailed =>
      ar ? 'فشل تحميل المهتمين' : 'Failed to load interests';
  String get noInterestYet => ar ? 'لا يوجد مهتمون بعد' : 'No interest yet';
  String get noInterestDesc => ar
      ? 'سيظهر هنا المستأجرون الذين أبدوا اهتمامهم'
      : 'Renters who express interest will appear here';
  String get renter => ar ? 'مستأجر' : 'Renter';
  String get call => ar ? 'اتصال' : 'Call';
  String get email => ar ? 'بريد' : 'Email';
  String get whatsapp => ar ? 'واتساب' : 'WhatsApp';

  String status(String value) => switch (value) {
    'ACTIVE' => ar ? 'نشط' : 'ACTIVE',
    'NOTIFIED' => ar ? 'تم الإشعار' : 'NOTIFIED',
    'WITHDRAWN' => ar ? 'انسحب' : 'WITHDRAWN',
    _ => value.replaceAll('_', ' '),
  };
}

// ── Screen ────────────────────────────────────────────────────────────────────

class ListingInterestsScreen extends ConsumerWidget {
  final String listingId;
  const ListingInterestsScreen({super.key, required this.listingId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final interestsAsync = ref.watch(_interestsProvider(listingId));

    return Scaffold(
      backgroundColor: m.background,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ChromeHeader(l: l, count: interestsAsync.valueOrNull?.length),
          Expanded(
            child: interestsAsync.when(
              loading: () => ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: 4,
                itemBuilder: (_, _) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: ShimmerLoading(height: 80, width: double.infinity),
                ),
              ),
              error: (e, _) => ErrorState(
                message: l.loadFailed,
                onRetry: () => ref.invalidate(_interestsProvider(listingId)),
              ),
              data: (items) {
                if (items.isEmpty) {
                  return EmptyState(
                    icon: Icons.people_outline,
                    title: l.noInterestYet,
                    subtitle: l.noInterestDesc,
                  );
                }
                return RefreshIndicator(
                  color: AppColors.accent,
                  onRefresh: () async =>
                      ref.invalidate(_interestsProvider(listingId)),
                  child: ListView.builder(
                    padding: EdgeInsets.fromLTRB(
                      16,
                      16,
                      16,
                      AppInsets.bottomNav(context),
                    ),
                    itemCount: items.length,
                    itemBuilder: (_, i) => AnimatedListItem(
                      index: i,
                      child: Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: _InterestCard(interest: items[i], l: l),
                      ),
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

// ── Chrome header ─────────────────────────────────────────────────────────────

class _ChromeHeader extends StatelessWidget {
  final _L l;
  final int? count;
  const _ChromeHeader({required this.l, this.count});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.primary,
        border: Border(
          bottom: BorderSide(color: AppColors.accent.withValues(alpha: 0.14)),
        ),
      ),
      child: SafeArea(
        bottom: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 14, 20, 18),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Expanded(
                child: Text(
                  l.interestedRenters,
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                          color: Colors.white,
                        )
                      : GoogleFonts.cinzel(
                          fontSize: 16,
                          letterSpacing: 2.4,
                          color: Colors.white,
                        ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (count != null) ...[
                const SizedBox(width: 10),
                Text(
                  '$count',
                  style: GoogleFonts.cinzel(
                    fontSize: 18,
                    fontWeight: FontWeight.w600,
                    color: AppColors.accent,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

// ── Card ──────────────────────────────────────────────────────────────────────

class _InterestCard extends StatelessWidget {
  final Map<String, dynamic> interest;
  final _L l;
  const _InterestCard({required this.interest, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final name = interest['renterName'] as String? ?? l.renter;
    final email = interest['renterEmail'] as String?;
    final phone = interest['renterPhone'] as String?;
    final note = interest['note'] as String?;
    final status = interest['status'] as String? ?? 'ACTIVE';
    final createdAt = interest['createdAt'] as String?;

    final statusColor = switch (status) {
      'ACTIVE' => m.success,
      'NOTIFIED' => AppColors.accentDark,
      'WITHDRAWN' => m.textMuted,
      _ => m.textMuted,
    };

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: AppColors.accent.withValues(alpha: 0.35),
                  ),
                  color: AppColors.primary.withValues(alpha: 0.06),
                ),
                alignment: Alignment.center,
                child: Text(
                  name.isNotEmpty ? name[0].toUpperCase() : '?',
                  style: GoogleFonts.cinzel(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: AppColors.accentDark,
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
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 14,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            ),
                    ),
                    if (createdAt != null)
                      Text(
                        Formatters.timeAgo(createdAt, ar: l.ar),
                        style: GoogleFonts.josefinSans(
                          fontSize: 11,
                          color: m.textMuted,
                        ),
                      ),
                  ],
                ),
              ),
              _StatusPill(
                label: l.status(status),
                color: statusColor,
                ar: l.ar,
              ),
            ],
          ),

          if (note != null && note.isNotEmpty) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: m.surfaceAlt,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                note,
                style:
                    (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.josefinSans)(
                      fontSize: 13,
                      color: m.textSecondary,
                      height: 1.5,
                      letterSpacing: 0,
                    ),
              ),
            ),
          ],

          if (phone != null || email != null) ...[
            Divider(height: 20, color: m.divider),
            Row(
              children: [
                if (phone != null)
                  Expanded(
                    child: _ContactBtn(
                      icon: Icons.phone_outlined,
                      label: l.call,
                      ar: l.ar,
                      onTap: () => _launch('tel:$phone'),
                    ),
                  ),
                if (phone != null && email != null) const SizedBox(width: 10),
                if (email != null)
                  Expanded(
                    child: _ContactBtn(
                      icon: Icons.email_outlined,
                      label: l.email,
                      ar: l.ar,
                      onTap: () => _launch('mailto:$email'),
                    ),
                  ),
                if (phone != null) ...[
                  const SizedBox(width: 10),
                  Expanded(
                    child: _ContactBtn(
                      icon: Icons.message_outlined,
                      label: l.whatsapp,
                      ar: l.ar,
                      onTap: () => _launch(
                        'https://wa.me/${phone.replaceAll(RegExp(r'[^0-9]'), '')}',
                      ),
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
    if (await canLaunchUrl(uri)) await launchUrl(uri);
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
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                color: color,
              )
            : GoogleFonts.josefinSans(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                letterSpacing: 1.2,
                color: color,
              ),
      ),
    );
  }
}

class _ContactBtn extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool ar;
  final VoidCallback onTap;
  const _ContactBtn({
    required this.icon,
    required this.label,
    required this.ar,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.accent.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.accent.withValues(alpha: 0.3)),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 16, color: AppColors.accentDark),
            const SizedBox(width: 6),
            Text(
              label,
              style:
                  (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
                    fontSize: 12,
                    color: m.isDark ? AppColors.accent : AppColors.accentDark,
                    fontWeight: FontWeight.w600,
                    letterSpacing: ar ? 0 : 0.4,
                  ),
            ),
          ],
        ),
      ),
    );
  }
}

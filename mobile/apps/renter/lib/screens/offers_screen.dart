import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/promotion_provider.dart';
import '../widgets/ad_card.dart';
import '../widgets/promo_actions.dart';

const _categories = <String>[
  'DINING',
  'FITNESS',
  'RETAIL',
  'SERVICES',
  'HEALTH',
  'EDUCATION',
  'OTHER',
];

/// The full promotions catalogue behind the home carousel.
///
/// No FAB: the renter shell's floating bottom-nav pill covers that corner, so
/// any action belongs in the AppBar.
class OffersScreen extends ConsumerStatefulWidget {
  const OffersScreen({super.key});

  @override
  ConsumerState<OffersScreen> createState() => _OffersScreenState();
}

class _OffersScreenState extends ConsumerState<OffersScreen> {
  String? _category;

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final l = _L(isAr);
    final async = ref.watch(promoOffersProvider(_category));
    final actions = ref.read(promoActionsProvider);
    final queue = ref.read(promoEventQueueProvider);

    return Scaffold(
      appBar: AppBar(title: Text(l.title)),
      body: Column(
        children: [
          // Sized by its chips, not pinned to a height. A fixed 52 squashed
          // every chip to 36 — below Material's 48dp tap target — and at 2.0
          // text scale the label outgrew the pill and painted below it.
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(
              horizontal: MiftahSpacing.page,
              vertical: 8,
            ),
            child: Row(
              children: [
                for (final category in _categories) ...[
                  ChoiceChip(
                    key: Key('offers-chip-$category'),
                    label: Text(l.category(category)),
                    selected: _category == category,
                    onSelected: (_) => setState(
                      // A second tap on the active chip clears the filter,
                      // so there is no separate "All" chip to keep in sync.
                      () => _category = _category == category ? null : category,
                    ),
                  ),
                  const SizedBox(width: 8),
                ],
              ],
            ),
          ),
          Expanded(
            child: async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => ErrorState(
                message: l.loadFailed,
                onRetry: () => ref.invalidate(promoOffersProvider(_category)),
              ),
              data: (ads) {
                if (ads.isEmpty) {
                  return EmptyState(
                    icon: Icons.local_offer_outlined,
                    title: l.emptyTitle,
                    subtitle: l.emptyMessage,
                  );
                }
                return ListView.separated(
                  padding: const EdgeInsets.fromLTRB(
                    MiftahSpacing.page,
                    8,
                    MiftahSpacing.page,
                    96, // clears the shell's floating nav pill
                  ),
                  itemCount: ads.length,
                  separatorBuilder: (_, _) =>
                      const SizedBox(height: MiftahSpacing.gap),
                  itemBuilder: (context, i) {
                    final ad = ads[i];
                    return SizedBox(
                      height: adCardHeight(context) * 1.2,
                      child: AdCard(
                        ad: ad,
                        onTap: () {
                          queue.recordClick(ad.id);
                          actions.handleTap(context, ad);
                        },
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _L {
  const _L(this.ar);
  final bool ar;

  String get title => ar ? 'العروض' : 'Offers';
  String get loadFailed =>
      ar ? 'تعذر تحميل العروض' : 'Could not load offers';
  String get emptyTitle => ar ? 'لا توجد عروض' : 'No offers yet';
  String get emptyMessage => ar
      ? 'ستظهر عروض شركائنا هنا فور توفرها.'
      : 'Deals from our partner businesses will show up here.';

  String category(String value) {
    switch (value) {
      case 'DINING':
        return ar ? 'مطاعم' : 'Dining';
      case 'FITNESS':
        return ar ? 'رياضة' : 'Fitness';
      case 'RETAIL':
        return ar ? 'تسوق' : 'Retail';
      case 'SERVICES':
        return ar ? 'خدمات' : 'Services';
      case 'HEALTH':
        return ar ? 'صحة' : 'Health';
      case 'EDUCATION':
        return ar ? 'تعليم' : 'Education';
      default:
        return ar ? 'أخرى' : 'Other';
    }
  }
}

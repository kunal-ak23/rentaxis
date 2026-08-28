import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../providers/promotion_provider.dart';
import 'ad_card.dart';
import 'ads_carousel.dart';
import 'promo_actions.dart';

/// The promotions strip as the home screen uses it: reads the feed, escapes
/// the page gutter, and wires taps and impressions.
///
/// Renders nothing at all while loading, on error, or when neither the feed
/// nor the catalogue has anything in it. A dead promotions endpoint must never
/// put an error card on the home screen — ads are the least important thing
/// there.
///
/// An empty feed over a non-empty catalogue is the one case that still draws:
/// a plain link to `/offers`, because this widget holds the only route into
/// that screen.
class HomeAdsStrip extends ConsumerStatefulWidget {
  const HomeAdsStrip({super.key});

  @override
  ConsumerState<HomeAdsStrip> createState() => _HomeAdsStripState();
}

class _HomeAdsStripState extends ConsumerState<HomeAdsStrip>
    with WidgetsBindingObserver {
  /// Resolved once and held rather than read on demand: Riverpod's element is
  /// already defunct by the time `State.dispose` runs, so `ref` there throws
  /// `Cannot use "ref" after the widget was disposed`. Holding the instance
  /// costs nothing — [promoEventQueueProvider] is deliberately not
  /// `autoDispose`, so the queue outlives this widget either way.
  late final PromoEventQueue _queue;

  @override
  void initState() {
    super.initState();
    _queue = ref.read(promoEventQueueProvider);
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // Not awaited: dispose cannot be async, and a dropped flush costs at most
    // a missing impression row.
    unawaited(_queue.flush());
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused) {
      unawaited(_queue.flush());
    }
  }

  @override
  Widget build(BuildContext context) {
    final ads =
        ref.watch(homePromoFeedProvider).valueOrNull ?? const <PromoAd>[];

    // Only offer "See all" when there is genuinely more behind it. The offers
    // list is watched rather than fetched eagerly, so this resolves quietly
    // after the strip is already on screen.
    //
    // Watched BEFORE the empty-feed exit, not after: the see-all tile is the
    // only door into /offers, and `OFFERS_ONLY` is a real placement in the
    // admin ad editor that the backend keeps out of the home feed. A tenant
    // that sets every ad to it would otherwise have an empty home feed, no
    // tile, and a catalogue no renter can reach. The cost is one extra GET on
    // a home screen that has no promo cards.
    final offerCount =
        ref.watch(promoOffersProvider(null)).valueOrNull?.length ?? 0;

    if (ads.isEmpty) {
      if (offerCount == 0) return const SizedBox.shrink();
      return _leadingGap(_OffersLink(onTap: () => context.push('/offers')));
    }

    final hasMore = offerCount > ads.length;

    final actions = ref.read(promoActionsProvider);

    // The home ListView has a 20px horizontal gutter; the strip needs the full
    // screen width for the next card to peek in correctly. OverflowBox lets it
    // out of the gutter without changing the padding of every sibling — but an
    // OverflowBox sizes ITSELF to its incoming constraints, and a ListView
    // hands it unbounded height, so the strip's height must be pinned here:
    // the card, plus the dots row (10px gap + 6px dots) when dots show.
    final screenWidth = MediaQuery.sizeOf(context).width;
    final pageCount = ads.length + (hasMore ? 1 : 0);
    final stripHeight = adCardHeight(context) + (pageCount > 1 ? 16 : 0);
    return _leadingGap(
      SizedBox(
        height: stripHeight,
        child: OverflowBox(
          maxWidth: screenWidth,
          minWidth: screenWidth,
          alignment: Alignment.center,
          child: AdsCarousel(
            ads: ads,
            onImpression: _queue.recordImpression,
            onSeeAll: hasMore ? () => context.push('/offers') : null,
            onTapAd: (ad) {
              _queue.recordClick(ad.id);
              actions.handleTap(context, ad);
            },
          ),
        ),
      ),
    );
  }

  /// The section carries its own leading gap, the way `home_screen.dart` writes
  /// the penalty strip: a section that can disappear has to take its spacing
  /// with it, or the two gaps around it collapse into a doubled 22px hole on
  /// every home screen with no promotions — which is most of them.
  Widget _leadingGap(Widget child) => Padding(
    padding: const EdgeInsets.only(top: MiftahSpacing.gap),
    child: child,
  );
}

/// Stand-in entry point for /offers when the home feed has no cards of its own
/// but the catalogue is not empty. Not the carousel's `_SeeAllTile`: that one
/// is a full-height card page, and a lone card banner where the strip would be
/// reads as an ad the renter never asked for. A quiet row does not.
class _OffersLink extends StatelessWidget {
  const _OffersLink({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    final m = context.miftah;
    final radius = BorderRadius.circular(MiftahRadii.card);
    return Material(
      color: m.surface,
      borderRadius: radius,
      child: InkWell(
        key: const Key('promo-offers-link'),
        borderRadius: radius,
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(
            horizontal: MiftahSpacing.cardPad,
            vertical: 14,
          ),
          decoration: BoxDecoration(
            borderRadius: radius,
            border: Border.all(color: m.border),
          ),
          child: Row(
            children: [
              const Icon(
                Icons.local_offer_outlined,
                color: MiftahColors.brass,
                size: 20,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  isAr ? 'كل العروض' : 'See all offers',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: isAr
                      ? MiftahType.ar(
                          size: 14,
                          weight: FontWeight.w700,
                          color: m.textPrimary,
                        )
                      : MiftahType.cardTitle(color: m.textPrimary),
                ),
              ),
              // Material chevrons do not mirror themselves under RTL, so the
              // forward-pointing one has to be chosen per direction.
              Icon(
                isAr ? Icons.chevron_left : Icons.chevron_right,
                color: m.textMuted,
                size: 22,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

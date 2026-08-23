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
/// Renders nothing at all while loading, on error, or with an empty feed. A
/// dead promotions endpoint must never put an error card on the home screen —
/// ads are the least important thing there.
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
    if (ads.isEmpty) return const SizedBox.shrink();

    // Only offer "See all" when there is genuinely more behind it. The offers
    // list is watched rather than fetched eagerly, so this resolves quietly
    // after the strip is already on screen.
    final offerCount =
        ref.watch(promoOffersProvider(null)).valueOrNull?.length ?? 0;
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
    return SizedBox(
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
    );
  }
}

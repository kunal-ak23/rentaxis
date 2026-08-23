import 'dart:async';

import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'ad_card.dart';

/// Auto-scrolling promo strip: a peeking `PageView` that advances itself every
/// few seconds, pauses while the renter is dragging it, and shows dot
/// indicators below.
///
/// Takes its data and callbacks as parameters and reads no providers, which is
/// what makes the timing behaviour testable without a container. The ads
/// arrive already ordered by the server's daily rotation — render them as
/// given and never re-sort.
class AdsCarousel extends StatefulWidget {
  const AdsCarousel({
    super.key,
    required this.ads,
    required this.onTapAd,
    this.onSeeAll,
    this.onImpression,
  });

  final List<PromoAd> ads;
  final void Function(PromoAd ad) onTapAd;

  /// When non-null, a "See all offers" tile is appended as a real extra page.
  /// Pass null when the catalogue holds nothing beyond this strip.
  final VoidCallback? onSeeAll;

  /// Called once per ad as its page settles. De-duplication is the caller's
  /// job — [PromoEventQueue] already does it.
  final void Function(String adId)? onImpression;

  @override
  State<AdsCarousel> createState() => _AdsCarouselState();
}

class _AdsCarouselState extends State<AdsCarousel> {
  static const _autoAdvanceInterval = Duration(seconds: 4);
  static const _resumeDelay = Duration(seconds: 3);
  static const _pageAnimationDuration = Duration(milliseconds: 450);

  final _pageController = PageController(viewportFraction: 0.88);
  Timer? _autoAdvanceTimer;
  Timer? _resumeTimer;
  int _page = 0;
  int? _lastLength;
  bool? _lastReducedMotion;
  bool _userDragInProgress = false;

  /// Ads plus the optional see-all tile.
  int get _pageCount => widget.ads.length + (widget.onSeeAll != null ? 1 : 0);

  void _startAutoAdvance(int length) {
    _autoAdvanceTimer?.cancel();
    if (length <= 1) return;
    _autoAdvanceTimer = Timer.periodic(_autoAdvanceInterval, (_) {
      if (!_pageController.hasClients) return;
      _pageController.animateToPage(
        (_page + 1) % length,
        duration: _pageAnimationDuration,
        curve: Curves.easeOutCubic,
      );
    });
  }

  void _pauseAutoAdvance() {
    _autoAdvanceTimer?.cancel();
    _resumeTimer?.cancel();
  }

  void _scheduleResume(int length) {
    _resumeTimer?.cancel();
    if (_lastReducedMotion == true || length <= 1) return;
    _resumeTimer = Timer(_resumeDelay, () {
      if (!mounted) return;
      _startAutoAdvance(length);
    });
  }

  /// Called on every build but does work only when the page count or the
  /// reduced-motion flag actually changed — restarting the timer on each
  /// rebuild would reset the interval and the strip would never advance.
  void _syncAutoAdvance(int length, bool reducedMotion) {
    if (_lastLength == length && _lastReducedMotion == reducedMotion) return;
    _lastLength = length;
    _lastReducedMotion = reducedMotion;
    _autoAdvanceTimer?.cancel();
    _resumeTimer?.cancel();
    if (!reducedMotion) _startAutoAdvance(length);
  }

  void _reportImpression(int index) {
    final onImpression = widget.onImpression;
    // The see-all tile is a page but not an ad — it never counts as a view.
    if (onImpression == null || index >= widget.ads.length) return;
    onImpression(widget.ads[index].id);
  }

  @override
  void initState() {
    super.initState();
    // The first page is on screen from the first frame, so it is a view.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || widget.ads.isEmpty) return;
      _reportImpression(0);
    });
  }

  @override
  void dispose() {
    _autoAdvanceTimer?.cancel();
    _resumeTimer?.cancel();
    _pageController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.ads.isEmpty) return const SizedBox.shrink();

    final pageCount = _pageCount;
    final reducedMotion = MediaQuery.disableAnimationsOf(context);
    _syncAutoAdvance(pageCount, reducedMotion);

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        SizedBox(
          height: adCardHeight(context),
          child: NotificationListener<ScrollNotification>(
            onNotification: (notification) {
              // dragDetails distinguishes a real finger from our own
              // animateToPage, which also emits scroll notifications.
              if (notification is ScrollStartNotification &&
                  notification.dragDetails != null) {
                _userDragInProgress = true;
                _pauseAutoAdvance();
              } else if (notification is ScrollEndNotification &&
                  _userDragInProgress) {
                _userDragInProgress = false;
                _scheduleResume(pageCount);
              }
              return false;
            },
            // padEnds stays at its default `true`. A fractional-viewport
            // PageView with padEnds: false tops out at `count - 1 /
            // viewportFraction` pages — at 0.88 that is 1.14 pages short of
            // the end, so the last ad can never become the current page: it
            // sits clamped against the trailing edge, never on stage, and the
            // auto-advance animates to an index the controller then refuses.
            // Padded ends cost a wider outer margin and a smaller peek; a
            // reachable last banner is worth both.
            child: PageView.builder(
              controller: _pageController,
              itemCount: pageCount,
              onPageChanged: (i) {
                setState(() => _page = i);
                _reportImpression(i);
              },
              itemBuilder: (context, i) {
                // Uniform: padded ends already inset the first and last page
                // by half the off-viewport fraction, so a wider edge padding
                // would only make the outer cards narrower than the rest.
                const padding = EdgeInsets.symmetric(horizontal: 6);
                if (i >= widget.ads.length) {
                  return Padding(
                    padding: padding,
                    child: _SeeAllTile(onTap: widget.onSeeAll!),
                  );
                }
                final ad = widget.ads[i];
                return Padding(
                  padding: padding,
                  child: AdCard(ad: ad, onTap: () => widget.onTapAd(ad)),
                );
              },
            ),
          ),
        ),
        if (pageCount > 1) ...[
          const SizedBox(height: 10),
          _PromoDots(count: pageCount, activeIndex: _page),
        ],
      ],
    );
  }
}

class _PromoDots extends StatelessWidget {
  const _PromoDots({required this.count, required this.activeIndex});

  final int count;
  final int activeIndex;

  @override
  Widget build(BuildContext context) {
    return Row(
      key: const Key('promo-dots'),
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        for (var i = 0; i < count; i++) ...[
          AnimatedContainer(
            key: Key('promo-dot-$i'),
            duration: const Duration(milliseconds: 250),
            curve: Curves.easeOutCubic,
            width: i == activeIndex ? 18 : 6,
            height: 6,
            decoration: BoxDecoration(
              color: i == activeIndex
                  ? MiftahColors.brass
                  : MiftahColors.borderStrong,
              borderRadius: BorderRadius.circular(3),
            ),
          ),
          if (i < count - 1) const SizedBox(width: 5),
        ],
      ],
    );
  }
}

class _SeeAllTile extends StatelessWidget {
  const _SeeAllTile({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final isAr = context.isAr;
    return InkWell(
      key: const Key('promo-see-all'),
      borderRadius: BorderRadius.circular(MiftahRadii.card),
      onTap: onTap,
      child: Container(
        decoration: BoxDecoration(
          color: MiftahColors.surface,
          borderRadius: BorderRadius.circular(MiftahRadii.card),
          border: Border.all(color: MiftahColors.border),
        ),
        alignment: Alignment.center,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.local_offer_outlined,
                color: MiftahColors.brass, size: 26),
            const SizedBox(height: 8),
            Text(
              isAr ? 'كل العروض' : 'See all offers',
              style: MiftahType.cardTitle(),
            ),
          ],
        ),
      ),
    );
  }
}

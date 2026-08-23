import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/ads_carousel.dart';

import 'support/fake_promotion_service.dart';

List<PromoAd> ads(int count) =>
    List.generate(count, (i) => testAd(id: 'ad-$i', titleEn: 'Offer $i'));

Widget host(
  Widget child, {
  bool disableAnimations = false,
  Size size = const Size(400, 800),
  TextDirection? textDirection,
}) =>
    MaterialApp(
      theme: AppTheme.lightTheme,
      home: MediaQuery(
        data: MediaQueryData(
          size: size,
          disableAnimations: disableAnimations,
        ),
        child: Scaffold(
          body: textDirection == null
              ? child
              : Directionality(textDirection: textDirection, child: child),
        ),
      ),
    );

double? currentPage(WidgetTester tester) =>
    tester.widget<PageView>(find.byType(PageView)).controller!.page;

/// Resizes the surface the strip actually lays out in. A `MediaQuery` override
/// alone does not — see the note in [main].
void setSurfaceWidth(WidgetTester tester, double width) {
  tester.view.devicePixelRatio = 1.0;
  tester.view.physicalSize = Size(width, 800);
}

/// The painted bounds of the nth ad card, in global coordinates.
Rect cardRect(WidgetTester tester, int index) => tester.getRect(
      find.ancestor(
        of: find.text('Offer $index'),
        matching: find.byKey(const Key('ad-card-surface')),
      ),
    );

/// The dot row marks the active index by widening that dot to 18; the rest
/// stay 6. Reading the rendered width is how a test sees which dot is lit.
double dotWidth(WidgetTester tester, int index) =>
    tester.getSize(find.byKey(Key('promo-dot-$index'))).width;

void main() {
  // The MediaQuery in `host` declares a 400x800 canvas but a MediaQuery
  // override does not resize the surface the widget actually lays out in —
  // without this the strip renders at the default 800x600, where a page is
  // 800*0.92 = 736px and the swipe tests' 300px drag is under the half-page
  // PageView snaps on. This makes the declared canvas real.
  setUp(() {
    final view =
        TestWidgetsFlutterBinding.ensureInitialized().platformDispatcher.views.first;
    view.devicePixelRatio = 1.0;
    view.physicalSize = const Size(400, 800);
    addTearDown(view.reset);
  });

  testWidgets('an empty list renders nothing', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: const [], onTapAd: (_) {})));

    expect(find.byType(PageView), findsNothing);
    expect(find.byType(SizedBox), findsWidgets);
    expect(tester.getSize(find.byType(AdsCarousel)), Size.zero);
  });

  testWidgets('a single banner shows no dots and starts no timer',
      (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(1), onTapAd: (_) {})));
    await tester.pump();

    expect(find.byKey(const Key('promo-dots')), findsNothing);

    await tester.pump(const Duration(seconds: 10));
    await tester.pumpAndSettle();

    expect(currentPage(tester), 0);
  });

  testWidgets('multiple banners auto-advance after 4 seconds', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();
    expect(currentPage(tester), 0);

    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();

    expect(currentPage(tester), 1);
  });

  testWidgets('auto-advance wraps around at the end', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(2), onTapAd: (_) {})));
    await tester.pump();

    for (var i = 0; i < 2; i++) {
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();
    }

    expect(currentPage(tester), 0);
  });

  testWidgets('a drag pauses auto-advance', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    // Drag at t=3s, one second short of the tick the running timer has already
    // scheduled for t=4s. That ordering is the whole test: the pause has to
    // swallow a tick that was genuinely due, and the only window where a
    // cancelled timer differs from an uncancelled one is between the drag and
    // the resume. Drag at t=0 instead and the assertion below never reaches
    // t=4s at all, so it passes whether or not anything was cancelled.
    await tester.pump(const Duration(seconds: 3));
    await tester.drag(find.byType(PageView), const Offset(-300, 0));
    await tester.pumpAndSettle();
    expect(currentPage(tester), 1, reason: 'the drag itself lands on page 1');

    // t is now ~3.8s. Two more seconds carries us past that 4s tick while
    // staying well inside the 3s resume shadow (which reaches ~6.8s, with its
    // own first tick at ~10.8s), so an advance here can only be the tick the
    // drag was supposed to cancel.
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();

    expect(currentPage(tester), 1);
  });

  testWidgets('auto-advance does not resume before the 3s delay is up',
      (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    await tester.drag(find.byType(PageView), const Offset(-200, 0));
    await tester.pumpAndSettle();
    final afterDrag = currentPage(tester);

    // The lower edge of the resume delay. Five seconds is past the 4s the
    // interval alone would need, but short of the 3s + 4s a correctly delayed
    // resume needs — so a strip that resumed immediately has moved by now and
    // one that waited has not. Without this the delay could be zero and the
    // suite would not notice.
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();

    expect(currentPage(tester), afterDrag);
  });

  testWidgets('auto-advance resumes 3 seconds after the drag ends',
      (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    await tester.drag(find.byType(PageView), const Offset(-200, 0));
    await tester.pumpAndSettle();
    final afterDrag = currentPage(tester)!;

    // The upper edge: 3s to resume, then a further 4s for the first tick. Any
    // delay longer than 3s leaves the strip still parked here.
    await tester.pump(const Duration(seconds: 3));
    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();

    expect(currentPage(tester), (afterDrag + 1) % 3);
  });

  testWidgets('reduced motion never auto-advances', (tester) async {
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (_) {}),
      disableAnimations: true,
    ));
    await tester.pump();

    await tester.pump(const Duration(seconds: 20));
    await tester.pumpAndSettle();

    expect(currentPage(tester), 0);
  });

  testWidgets('reduced motion still allows a manual swipe', (tester) async {
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (_) {}),
      disableAnimations: true,
    ));
    await tester.pump();

    await tester.drag(find.byType(PageView), const Offset(-300, 0));
    await tester.pumpAndSettle();

    expect(currentPage(tester), isNot(0));
  });

  testWidgets('dots appear only when there is more than one page',
      (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    expect(find.byKey(const Key('promo-dots')), findsOneWidget);
    expect(find.byKey(const Key('promo-dot-0')), findsOneWidget);
    expect(find.byKey(const Key('promo-dot-2')), findsOneWidget);
  });

  testWidgets('reports an impression for the first page on build',
      (tester) async {
    final seen = <String>[];
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (_) {}, onImpression: seen.add),
    ));
    await tester.pump();

    expect(seen, ['ad-0']);
  });

  testWidgets('reports an impression when a page settles', (tester) async {
    final seen = <String>[];
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (_) {}, onImpression: seen.add),
    ));
    await tester.pump();

    await tester.pump(const Duration(seconds: 4));
    await tester.pumpAndSettle();

    expect(seen, ['ad-0', 'ad-1']);
  });

  testWidgets('taps report the tapped ad', (tester) async {
    final tapped = <String>[];
    await tester.pumpWidget(host(
      AdsCarousel(ads: ads(3), onTapAd: (ad) => tapped.add(ad.id)),
    ));
    await tester.pump();

    await tester.tap(find.text('Offer 0'));
    await tester.pump();

    expect(tapped, ['ad-0']);
  });

  group('see-all tile', () {
    testWidgets('is appended and counted as a page', (tester) async {
      await tester.pumpWidget(host(AdsCarousel(
        ads: ads(3),
        onTapAd: (_) {},
        onSeeAll: () {},
      )));
      await tester.pump();

      expect(find.byKey(const Key('promo-dot-3')), findsOneWidget);

      // A PageView builds lazily — it mounts its viewport plus one viewport of
      // cache either side, so a 4th page of 0.88 viewports each is not in the
      // element tree at page 0 at any surface size. Scroll to it before
      // asserting it is there.
      for (var i = 0; i < 3; i++) {
        await tester.drag(find.byType(PageView), const Offset(-300, 0));
        await tester.pumpAndSettle();
      }

      expect(currentPage(tester), 3);
      expect(find.byKey(const Key('promo-see-all')), findsOneWidget);
    });

    testWidgets('is omitted when onSeeAll is null', (tester) async {
      await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
      await tester.pump();

      expect(find.byKey(const Key('promo-see-all')), findsNothing);
      expect(find.byKey(const Key('promo-dot-3')), findsNothing);
    });

    testWidgets('fires no impression of its own', (tester) async {
      final seen = <String>[];
      await tester.pumpWidget(host(AdsCarousel(
        ads: ads(1),
        onTapAd: (_) {},
        onSeeAll: () {},
        onImpression: seen.add,
      )));
      await tester.pump();

      await tester.drag(find.byType(PageView), const Offset(-300, 0));
      await tester.pumpAndSettle();

      expect(seen, ['ad-0']);
    });
  });

  group('a feed that shrinks under the renter', () {
    /// Parks the strip on the last of three cards, then rebuilds it in place
    /// with only the first two — one ad expired. `HomeAdsStrip` passes no key,
    /// so `ref.invalidate(homePromoFeedProvider)` reuses this very State and
    /// everything it remembers about the page it was on.
    Future<void> expireTheLastAd(
      WidgetTester tester, {
      List<String>? impressions,
    }) async {
      final onImpression = impressions?.add;
      final three = ads(3);
      await tester.pumpWidget(host(
        AdsCarousel(ads: three, onTapAd: (_) {}, onImpression: onImpression),
      ));
      await tester.pump();

      for (var i = 0; i < 2; i++) {
        await tester.drag(find.byType(PageView), const Offset(-300, 0));
        await tester.pumpAndSettle();
      }
      expect(currentPage(tester), 2);

      // Everything from here is about the new slate only.
      impressions?.clear();

      final before = tester.state(find.byType(AdsCarousel));
      await tester.pumpWidget(host(
        AdsCarousel(
          ads: three.take(2).toList(),
          onTapAd: (_) {},
          onImpression: onImpression,
        ),
      ));
      await tester.pump();
      expect(
        identical(before, tester.state(find.byType(AdsCarousel))),
        isTrue,
        reason: 'the State must survive, or there is no stale page to clamp',
      );
    }

    testWidgets('reports an impression for the card left on stage',
        (tester) async {
      final seen = <String>[];
      await expireTheLastAd(tester, impressions: seen);

      // The PageView clamps its own scroll offset to the new last page during
      // layout, silently — onPageChanged never fires. So nothing else will
      // ever credit ad-1 with the view the renter is having right now.
      expect(currentPage(tester), 1);
      expect(seen, ['ad-1']);
    });

    testWidgets('lights the dot for the card left on stage', (tester) async {
      await expireTheLastAd(tester);
      await tester.pumpAndSettle();

      expect(dotWidth(tester, 1), 18);
      expect(dotWidth(tester, 0), 6);
    });

    testWidgets('keeps auto-advancing', (tester) async {
      await expireTheLastAd(tester);

      // A page index left at 2 makes the tick compute (2 + 1) % 2 = 1 — the
      // page the controller is already parked on. Nothing moves, so
      // onPageChanged never fires, so the index never repairs itself: the
      // strip is frozen for the rest of the session.
      await tester.pump(const Duration(seconds: 4));
      await tester.pumpAndSettle();

      expect(currentPage(tester), 0);
    });
  });

  group('page geometry', () {
    // HomeAdsStrip breaks the strip out of the home ListView's 20px gutter to
    // get full screen width, so the carousel owes that gutter back itself.
    // Under `padEnds: true` the arithmetic is forced: for viewport fraction f
    // and item padding p, margin = (1 - f) * W / 2 + p and peek = (1 - f) * W
    // / 2 - p, so margin - peek is always 2p and the two cannot be tuned
    // independently. f = 0.92 with p = 4 is the point that lands the margin on
    // the home gutter across real phone widths while still leaving the next
    // card enough of itself showing to read as a card.
    for (final width in [360.0, 393.0, 430.0]) {
      testWidgets('a card sits on the home gutter at ${width}pt', (tester) async {
        setSurfaceWidth(tester, width);
        await tester.pumpWidget(host(
          AdsCarousel(ads: ads(3), onTapAd: (_) {}),
          size: Size(width, 800),
        ));
        await tester.pump();

        final first = cardRect(tester, 0);
        final second = cardRect(tester, 1);

        // The worst case in this range is 360pt, where the margin is exactly
        // 18.4 — 1.6 off. The extra hundredth is there only to absorb double
        // precision noise, not to buy slack.
        expect(first.left, closeTo(20, 1.61));
        expect(width - first.right, closeTo(20, 1.61));
        expect(width - second.left, greaterThanOrEqualTo(10),
            reason: 'the next card must peek in far enough to be legible');
        expect(second.left - first.right, closeTo(8, 0.01),
            reason: 'two item paddings meet between cards');
      });
    }

    testWidgets('the gutter survives Arabic', (tester) async {
      const width = 393.0;
      setSurfaceWidth(tester, width);
      await tester.pumpWidget(host(
        AdsCarousel(ads: ads(3), onTapAd: (_) {}),
        size: const Size(width, 800),
        textDirection: TextDirection.rtl,
      ));
      await tester.pump();

      final first = cardRect(tester, 0);
      final second = cardRect(tester, 1);

      expect(first.left, closeTo(20, 1.6));
      expect(width - first.right, closeTo(20, 1.6));
      // Pages run right-to-left, so the second card peeks in from the left.
      expect(second.right, greaterThanOrEqualTo(10));
      expect(first.left - second.right, closeTo(8, 0.01));
    });
  });

  testWidgets('cancels its timers on dispose', (tester) async {
    await tester.pumpWidget(host(AdsCarousel(ads: ads(3), onTapAd: (_) {})));
    await tester.pump();

    await tester.pumpWidget(host(const SizedBox.shrink()));
    await tester.pump(const Duration(seconds: 10));

    // The real check is flutter_test's teardown assertion: a leaked
    // Timer.periodic fails the test with "A Timer is still pending". This
    // guards the pump itself.
    expect(tester.takeException(), isNull);
  });
}

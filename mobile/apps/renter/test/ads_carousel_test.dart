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
}) =>
    MaterialApp(
      theme: AppTheme.lightTheme,
      home: MediaQuery(
        data: MediaQueryData(
          size: const Size(400, 800),
          disableAnimations: disableAnimations,
        ),
        child: Scaffold(body: child),
      ),
    );

double? currentPage(WidgetTester tester) =>
    tester.widget<PageView>(find.byType(PageView)).controller!.page;

void main() {
  // The MediaQuery in `host` declares a 400x800 canvas but a MediaQuery
  // override does not resize the surface the widget actually lays out in —
  // without this the strip renders at the default 800x600, where a page is
  // 800*0.88 = 704px and the swipe tests' 300px drag is under the half-page
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

    await tester.drag(find.byType(PageView), const Offset(-200, 0));
    await tester.pumpAndSettle();
    final afterDrag = currentPage(tester);

    // Well past the 4s tick, but inside the 3s resume delay's shadow: the
    // timer was cancelled by the drag, so nothing should move on its own.
    await tester.pump(const Duration(seconds: 2));
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

    // 3s to resume, then a further 4s for the first tick.
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

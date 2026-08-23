import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/promotion_provider.dart';

import 'support/fake_promotion_service.dart';

ProviderContainer containerWith(FakePromotionService fake) {
  final container = ProviderContainer(
    overrides: [promotionServiceProvider.overrideWithValue(fake)],
  );
  addTearDown(container.dispose);
  return container;
}

void main() {
  test('home feed provider returns the server order untouched', () async {
    final fake = FakePromotionService(
      feedAds: [testAd(id: 'c'), testAd(id: 'a'), testAd(id: 'b')],
    );
    final container = containerWith(fake);

    final ads = await container.read(homePromoFeedProvider.future);

    expect(ads.map((a) => a.id), ['c', 'a', 'b']);
  });

  test('offers provider passes the category filter through', () async {
    final fake = FakePromotionService(offerAds: [testAd(category: 'FITNESS')]);
    final container = containerWith(fake);

    await container.read(promoOffersProvider('FITNESS').future);

    expect(fake.requestedCategories, ['FITNESS']);
  });

  group('impression queue', () {
    test('records an impression once per ad per session', () async {
      final fake = FakePromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      queue.recordImpression('ad-1');
      queue.recordImpression('ad-1');
      queue.recordImpression('ad-2');
      await queue.flush();

      expect(fake.postedEvents.map((e) => e.adId), ['ad-1', 'ad-2']);
      expect(fake.postedEvents.every((e) => e.type == PromoEventType.impression),
          isTrue);
    });

    test('records every click, including repeats', () async {
      final fake = FakePromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      queue.recordClick('ad-1');
      queue.recordClick('ad-1');
      await queue.flush();

      expect(fake.postedEvents, hasLength(2));
    });

    test('flush clears the queue so a second flush sends nothing', () async {
      final fake = FakePromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      queue.recordImpression('ad-1');
      await queue.flush();
      await queue.flush();

      expect(fake.postedBatches, hasLength(1));
    });

    test('flush with an empty queue does not call the API', () async {
      final fake = FakePromotionService();
      await containerWith(fake).read(promoEventQueueProvider).flush();

      expect(fake.postedBatches, isEmpty);
    });

    test('a failed flush is swallowed and does not requeue forever', () async {
      final fake = _ThrowingPromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      queue.recordImpression('ad-1');
      await queue.flush();
      await queue.flush();

      // Analytics must never surface an error or retry in a loop.
      expect(fake.attempts, 1);
    });

    test('splits a batch larger than the server cap', () async {
      final fake = FakePromotionService();
      final queue = containerWith(fake).read(promoEventQueueProvider);

      for (var i = 0; i < 120; i++) {
        queue.recordImpression('ad-$i');
      }
      await queue.flush();

      expect(fake.postedBatches, hasLength(3));
      expect(fake.postedBatches.every((b) => b.length <= 50), isTrue);
      expect(fake.postedEvents, hasLength(120));
    });
  });
}

class _ThrowingPromotionService extends FakePromotionService {
  int attempts = 0;

  @override
  Future<void> postEvents(List<PromoEvent> events) async {
    attempts++;
    throw StateError('network down');
  }
}

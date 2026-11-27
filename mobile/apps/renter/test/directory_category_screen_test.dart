import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/router.dart' show directoryRoutes;
import 'package:renter/screens/directory/business_screen.dart';
import 'package:renter/screens/directory/category_screen.dart';

import 'support/directory_host.dart';
import 'support/fake_promotion_service.dart';

const _carWash = DirectoryCategory(
  id: 'c-1',
  nameEn: 'Car wash',
  nameAr: 'غسيل السيارات',
);

const _quickWash = DirectoryBusiness(
  id: 'b-1',
  nameEn: 'QuickWash',
  nameAr: 'كويك واش',
  descriptionEn: 'Hand car wash at your building, every day of the week, '
      'with interior detailing and waxing on request.',
  descriptionAr: 'غسيل سيارات يدوي في مبناك',
  websiteUrl: 'https://quickwash.ae',
);

const _shine = DirectoryBusiness(id: 'b-2', nameEn: 'Shine Co');

FakePromotionService _fake({List<DirectoryBusiness> businesses = const [
  _quickWash,
  _shine,
]}) =>
    FakePromotionService(
      categoryDetails: {
        'c-1': (category: _carWash, businesses: businesses),
      },
      businesses: {for (final b in businesses) b.id: b},
    );

void main() {
  testWidgets('lists the category businesses with name and description',
      (tester) async {
    final fake = _fake();
    await pumpDirectory(tester, fake, '/directory/c-1');

    expect(fake.requestedCategoryIds, ['c-1']);
    expect(find.text('Car wash'), findsOneWidget); // AppBar title
    expect(find.text('QuickWash'), findsOneWidget);
    expect(find.text('Shine Co'), findsOneWidget);

    final description = tester.widget<Text>(
      find.text(_quickWash.descriptionEn!),
    );
    expect(description.maxLines, 1);
    expect(description.overflow, TextOverflow.ellipsis);

    // No logo URL on either: both fall back to the storefront icon.
    expect(find.byIcon(Icons.storefront_rounded), findsNWidgets(2));
  });

  testWidgets('tapping a business opens /directory/business/<id>',
      (tester) async {
    final fake = _fake();
    final router = await pumpDirectory(tester, fake, '/directory/c-1');

    await tester.tap(find.text('QuickWash'));
    await tester.pumpAndSettle();

    expect(
      router.routerDelegate.currentConfiguration.last.matchedLocation,
      '/directory/business/b-1',
    );
    expect(find.byType(BusinessScreen), findsOneWidget);
    expect(tester.widget<BusinessScreen>(find.byType(BusinessScreen)).businessId,
        'b-1');
  });

  testWidgets('a 404 shows the not-found state, not an error',
      (tester) async {
    await pumpDirectory(tester, FakePromotionService(), '/directory/gone');

    expect(find.text('This category is no longer available'.toUpperCase()), findsOneWidget);
    expect(find.byType(ErrorState), findsNothing);
  });

  testWidgets('any other failure shows an error with a working retry',
      (tester) async {
    final fake = _fake()..categoryError = StateError('boom');
    await pumpDirectory(tester, fake, '/directory/c-1');

    expect(find.byType(ErrorState), findsOneWidget);
    expect(find.text('QuickWash'), findsNothing);

    fake.categoryError = null;
    await tester.tap(find.text('Retry'));
    await tester.pumpAndSettle();

    expect(find.text('QuickWash'), findsOneWidget);
  });

  testWidgets('an empty category shows the empty state', (tester) async {
    await pumpDirectory(tester, _fake(businesses: const []), '/directory/c-1');

    expect(find.byType(EmptyState), findsOneWidget);
    expect(find.text('No businesses here yet'.toUpperCase()), findsOneWidget);
  });

  testWidgets('has no floating action button', (tester) async {
    await pumpDirectory(tester, _fake(), '/directory/c-1');

    expect(find.byType(FloatingActionButton), findsNothing);
  });

  testWidgets('a bare /directory/business is the category not-found state',
      (tester) async {
    final fake = _fake();
    await pumpDirectory(tester, fake, '/directory/business');

    expect(fake.requestedCategoryIds, ['business']);
    expect(find.byType(BusinessScreen), findsNothing);
    expect(find.text('This category is no longer available'.toUpperCase()),
        findsOneWidget);
    expect(find.byType(ErrorState), findsNothing);
  });

  group('routes', () {
    // `:categoryId` is one segment, so `/directory/business/<id>` can never be
    // read as a category whatever order the routes are listed in. Both orders
    // are pumped to keep it that way.
    for (final reversed in [false, true]) {
      final order = reversed ? 'reversed' : 'declared';
      testWidgets('/directory/business/<id> is the business screen ($order)',
          (tester) async {
        final fake = _fake();
        await pumpDirectory(
          tester,
          fake,
          '/directory/business/b-1',
          routes: reversed ? directoryRoutes.reversed.toList() : directoryRoutes,
        );

        expect(find.byType(BusinessScreen), findsOneWidget);
        expect(find.byType(CategoryScreen), findsNothing);
        expect(fake.requestedBusinessIds, ['b-1']);
        expect(fake.requestedCategoryIds, isEmpty);
      });

      testWidgets('/directory/<id> is the category screen ($order)',
          (tester) async {
        final fake = _fake();
        await pumpDirectory(
          tester,
          fake,
          '/directory/c-1',
          routes: reversed ? directoryRoutes.reversed.toList() : directoryRoutes,
        );

        expect(find.byType(CategoryScreen), findsOneWidget);
        expect(find.byType(BusinessScreen), findsNothing);
      });
    }
  });

  group('Arabic', () {
    testWidgets('names the category and its businesses in Arabic',
        (tester) async {
      await pumpDirectory(tester, _fake(), '/directory/c-1', ar: true);

      expect(find.text('غسيل السيارات'), findsOneWidget);
      expect(find.text('كويك واش'), findsOneWidget);
      expect(find.text('غسيل سيارات يدوي في مبناك'), findsOneWidget);
      // No Arabic name: falls back to English rather than a blank row.
      expect(find.text('Shine Co'), findsOneWidget);
      expect(find.text('Car wash'), findsNothing);
    });

    testWidgets('lays out right-to-left with the back button mirrored',
        (tester) async {
      await pumpDirectory(tester, _fake(), '/directory/c-1', ar: true);

      expect(
        Directionality.of(tester.element(find.byType(CategoryScreen))),
        TextDirection.rtl,
      );
      expect(backButtonOnTheRight(tester), isTrue);

      // The logo leads the row: on the right of the name in RTL.
      final logo = tester.getRect(find.byIcon(Icons.storefront_rounded).first);
      final name = tester.getRect(find.text('كويك واش'));
      expect(logo.left, greaterThan(name.right));
    });

    testWidgets('writes the not-found, error and empty states in Arabic',
        (tester) async {
      await pumpDirectory(tester, FakePromotionService(), '/directory/gone',
          ar: true);
      expect(find.text('هذه الفئة لم تعد متاحة'), findsOneWidget);

      final failing = _fake()..categoryError = StateError('boom');
      await pumpDirectory(tester, failing, '/directory/c-1', ar: true);
      expect(find.text('تعذر تحميل الفئة'), findsOneWidget);
      expect(find.text('إعادة المحاولة'), findsOneWidget);

      await pumpDirectory(tester, _fake(businesses: const []), '/directory/c-1',
          ar: true);
      expect(find.text('لا توجد أنشطة تجارية هنا بعد'), findsOneWidget);
    });
  });

  testWidgets('English back button sits on the left', (tester) async {
    await pumpDirectory(tester, _fake(), '/directory/c-1');
    expect(backButtonOnTheRight(tester), isFalse);
  });
}

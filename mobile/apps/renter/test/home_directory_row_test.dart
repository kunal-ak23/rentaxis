import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/widgets/home_directory_row.dart';
import 'package:renter/widgets/quick_action_tile.dart';

import 'support/fake_promotion_service.dart';

DirectoryCategory cat(int i, {String? iconUrl}) => DirectoryCategory(
      id: 'c$i',
      nameEn: 'Category $i',
      nameAr: 'فئة $i',
      iconUrl: iconUrl,
    );

/// Mimics the home stack: a marker above and below so gaps are measurable.
Widget host(
  FakePromotionService fake, {
  Locale locale = const Locale('en'),
  double width = 400,
  List<String>? pushed,
}) {
  final router = GoRouter(
    routes: [
      GoRoute(
        path: '/',
        builder: (_, _) => Scaffold(
          body: Center(
            child: SizedBox(
              width: width,
              child: ListView(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                children: const [
                  SizedBox(key: Key('above'), height: 10),
                  HomeDirectoryRow(),
                  SizedBox(key: Key('below'), height: 10),
                ],
              ),
            ),
          ),
        ),
      ),
      GoRoute(
        path: '/directory/:id',
        builder: (_, s) {
          pushed?.add(s.pathParameters['id']!);
          return const Scaffold(body: Text('stub'));
        },
      ),
    ],
  );
  return ProviderScope(
    overrides: [promotionServiceProvider.overrideWithValue(fake)],
    child: MaterialApp.router(
      theme: MiftahTheme.light,
      locale: locale,
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      routerConfig: router,
    ),
  );
}

double gapBetween(WidgetTester t) =>
    t.getTopLeft(find.byKey(const Key('below'))).dy -
    t.getBottomLeft(find.byKey(const Key('above'))).dy;

void main() {
  testWidgets('shows a tile per category with the English name', (t) async {
    await t.pumpWidget(host(FakePromotionService(categories: [cat(1), cat(2)])));
    await t.pumpAndSettle();
    expect(find.text('Category 1'), findsOneWidget);
    expect(find.text('Category 2'), findsOneWidget);
  });

  testWidgets('shows Arabic names and orders tiles right-to-left', (t) async {
    await t.pumpWidget(host(
      FakePromotionService(categories: [cat(1), cat(2)]),
      locale: const Locale('ar'),
    ));
    await t.pumpAndSettle();
    expect(find.text('فئة 1'), findsOneWidget);
    expect(find.text('Category 1'), findsNothing);
    expect(
      t.getCenter(find.text('فئة 1')).dx > t.getCenter(find.text('فئة 2')).dx,
      isTrue,
    );
  });

  testWidgets('an empty list renders nothing and leaves no gap', (t) async {
    await t.pumpWidget(host(FakePromotionService()));
    await t.pumpAndSettle();
    expect(gapBetween(t), 0);
  });

  testWidgets('an error renders nothing, no ErrorState, no gap', (t) async {
    await t.pumpWidget(
        host(FakePromotionService(directoryError: StateError('down'))));
    await t.pumpAndSettle();
    expect(find.byType(ErrorState), findsNothing);
    expect(gapBetween(t), 0);
  });

  testWidgets('loading renders nothing and no gap', (t) async {
    await t.pumpWidget(host(FakePromotionService(categories: [cat(1)])));
    expect(find.text('Category 1'), findsNothing);
    expect(gapBetween(t), 0);
    await t.pumpAndSettle();
  });

  testWidgets('categories with an empty id are skipped', (t) async {
    await t.pumpWidget(host(FakePromotionService(
      categories: [const DirectoryCategory(id: '', nameEn: 'Ghost'), cat(1)],
    )));
    await t.pumpAndSettle();
    expect(find.text('Ghost'), findsNothing);
    expect(find.text('Category 1'), findsOneWidget);
  });

  testWidgets('six categories wrap to two rows of four', (t) async {
    await t.pumpWidget(host(
        FakePromotionService(categories: [for (var i = 1; i <= 6; i++) cat(i)])));
    await t.pumpAndSettle();
    final ys = {
      for (var i = 1; i <= 6; i++) t.getCenter(find.text('Category $i')).dy,
    };
    expect(ys, hasLength(2));
    final firstRow = t.getCenter(find.text('Category 1')).dy;
    expect(
      [for (var i = 1; i <= 6; i++) i]
          .where((i) => t.getCenter(find.text('Category $i')).dy == firstRow),
      hasLength(4),
    );
  });

  testWidgets('fits at 320px with a long name and no overflow', (t) async {
    await t.pumpWidget(host(
      FakePromotionService(categories: [
        const DirectoryCategory(id: 'a', nameEn: 'Sample Group'),
        cat(2),
        cat(3),
        cat(4),
      ]),
      width: 320,
    ));
    await t.pumpAndSettle();
    expect(t.takeException(), isNull);
    expect(find.text('Sample Group'), findsOneWidget);
  });

  testWidgets('a long Arabic name wraps to two lines at full size, no overflow',
      (t) async {
    final long = 'خدمات التنظيف والصيانة المنزلية الشاملة للمباني السكنية والتجارية';
    await t.pumpWidget(host(
      FakePromotionService(categories: [
        DirectoryCategory(id: 'a', nameEn: 'Long', nameAr: long),
        cat(2),
        cat(3),
        cat(4),
      ]),
      locale: const Locale('ar'),
      width: 320,
    ));
    await t.pumpAndSettle();
    expect(t.takeException(), isNull);

    final text = t.widget<Text>(find.text(long));
    expect(text.maxLines, 2);
    expect(text.overflow, TextOverflow.ellipsis);
    // Not scaled down: no FittedBox between the tile and its label.
    expect(
      find.ancestor(of: find.text(long), matching: find.byType(FittedBox)),
      findsNothing,
    );
    // Equal-height tiles in the row.
    final h = t.getSize(find.byType(QuickActionTile).first).height;
    for (final tile in t.widgetList(find.byType(QuickActionTile)).indexed) {
      expect(t.getSize(find.byType(QuickActionTile).at(tile.$1)).height, h);
    }
  });

  testWidgets('tapping a tile pushes /directory/<id>', (t) async {
    final pushed = <String>[];
    await t.pumpWidget(
        host(FakePromotionService(categories: [cat(1), cat(2)]), pushed: pushed));
    await t.pumpAndSettle();
    await t.tap(find.text('Category 2'));
    await t.pumpAndSettle();
    expect(pushed, ['c2']);
  });

  testWidgets('icon image falls back to an icon on load error', (t) async {
    await t.pumpWidget(host(FakePromotionService(
      categories: [cat(1, iconUrl: 'http://127.0.0.1:1/none.png')],
    )));
    await t.pumpAndSettle();
    expect(find.byIcon(Icons.storefront_rounded), findsOneWidget);
  });

  testWidgets('no iconUrl shows the fallback icon', (t) async {
    await t.pumpWidget(host(FakePromotionService(categories: [cat(1)])));
    await t.pumpAndSettle();
    expect(find.byType(Image), findsNothing);
    expect(find.byIcon(Icons.storefront_rounded), findsOneWidget);
  });

  testWidgets('a non-empty row owns exactly one leading gap', (t) async {
    await t.pumpWidget(host(FakePromotionService(categories: [cat(1)])));
    await t.pumpAndSettle();
    expect(gapBetween(t) > MiftahSpacing.gap, isTrue);
  });
}

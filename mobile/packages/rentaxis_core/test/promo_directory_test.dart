import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class _Fake implements HttpClientAdapter {
  final List<RequestOptions> seen = [];
  final Map<String, Object> routes;
  _Fake(this.routes);

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<Uint8List>? s, Future<void>? c) async {
    seen.add(o);
    final body = routes[o.path] ?? <dynamic>[];
    return ResponseBody.fromString(jsonEncode(body), 200,
        headers: {Headers.contentTypeHeader: ['application/json']});
  }

  @override
  void close({bool force = false}) {}
}

const _biz = {
  'id': 'b1',
  'nameEn': 'Spice Bazaar',
  'nameAr': 'سبايس بازار',
  'logoUrl': '/api/v1/public/x',
  'descriptionEn': 'Great food',
  'descriptionAr': 'طعام',
  'websiteUrl': 'https://spice.example',
  'phoneE164': '+971500000000',
  'whatsappE164': '+971500000001',
};

const _cat = {
  'id': 'c1',
  'nameEn': 'Dining',
  'nameAr': 'مطاعم',
  'iconUrl': 'https://x/i.png',
  'businessCount': 3,
};

void main() {
  group('DirectoryCategory', () {
    test('parses full row', () {
      final c = DirectoryCategory.fromJson(_cat);
      expect(c.id, 'c1');
      expect(c.businessCount, 3);
      expect(c.iconUrl, 'https://x/i.png');
      expect(c.name(false), 'Dining');
      expect(c.name(true), 'مطاعم');
    });

    test('name falls back both ways when blank', () {
      final noAr = DirectoryCategory.fromJson({..._cat, 'nameAr': '  '});
      expect(noAr.name(true), 'Dining');
      final noEn = DirectoryCategory.fromJson({..._cat, 'nameEn': ''});
      expect(noEn.name(false), 'مطاعم');
    });

    test('relative icon resolves', () {
      final c = DirectoryCategory.fromJson({..._cat, 'iconUrl': '/api/v1/public/icon'});
      expect(c.iconUrl, startsWith('http'));
      expect(c.iconUrl, endsWith('/api/v1/public/icon'));
    });

    test('listFromJson drops junk rows', () {
      final l = DirectoryCategory.listFromJson([_cat, 'junk', null, 5]);
      expect(l, hasLength(1));
    });
  });

  group('DirectoryBusiness', () {
    test('parses full row', () {
      final b = DirectoryBusiness.fromJson(_biz);
      expect(b.websiteUrl, 'https://spice.example');
      expect(b.phoneE164, '+971500000000');
      expect(b.whatsappE164, '+971500000001');
      expect(b.logoUrl, startsWith('http'));
      expect(b.name(true), 'سبايس بازار');
      expect(b.description(false), 'Great food');
    });

    test('fallbacks for name and description', () {
      final b = DirectoryBusiness.fromJson({..._biz, 'nameAr': '', 'descriptionAr': null});
      expect(b.name(true), 'Spice Bazaar');
      expect(b.description(true), 'Great food');
      final c = DirectoryBusiness.fromJson({..._biz, 'descriptionEn': ' ', 'nameEn': null});
      expect(c.description(false), 'طعام');
      expect(c.name(false), 'سبايس بازار');
    });

    test('description is null when both blank', () {
      final b = DirectoryBusiness.fromJson({..._biz, 'descriptionEn': '', 'descriptionAr': null});
      expect(b.description(false), isNull);
    });

    test('listFromJson drops junk rows', () {
      expect(DirectoryBusiness.listFromJson([_biz, 3, 'x']), hasLength(1));
    });
  });

  group('PromotionApiService', () {
    test('directory, category, business, offers', () async {
      final fake = _Fake({
        '/v1/promotions/directory': [_cat],
        '/v1/promotions/directory/c1': {
          'category': _cat,
          'businesses': [_biz],
        },
        '/v1/promotions/directory/businesses/b1': _biz,
        '/v1/promotions/offers': [],
      });
      final dio = Dio()..httpClientAdapter = fake;
      final svc = PromotionApiService(dio);

      expect(await svc.directory(), hasLength(1));
      final r = await svc.directoryCategory('c1');
      expect(r.category.id, 'c1');
      expect(r.businesses.single.id, 'b1');
      expect((await svc.business('b1')).id, 'b1');

      await svc.offers();
      expect(fake.seen.last.queryParameters, isEmpty);
      await svc.offers(category: 'DINING');
      expect(fake.seen.last.queryParameters, {'category': 'DINING'});
      await svc.offers(businessId: 'b1');
      expect(fake.seen.last.queryParameters, {'businessId': 'b1'});
    });
  });
}

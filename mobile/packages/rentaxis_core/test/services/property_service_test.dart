import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/property_service.dart';

void main() {
  group('PropertyService.getProperties', () {
    test('flattens {property: {...}} summaries so id/name read at top level',
        () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      dio.httpClientAdapter = _StubAdapter(responseBody: [
        {
          'property': {'id': 'p1', 'nameEn': 'Sample Tower', 'nameAr': 'برج'},
          'vacancies': 3,
          'propertyCount': 8,
        },
      ]);

      final list = await PropertyService(dio).getProperties();
      final p = list.first as Map<String, dynamic>;

      expect(p['id'], 'p1');
      expect(p['name'], 'Sample Tower');
      expect(p['nameEn'], 'Sample Tower');
      expect(p['vacancies'], 3); // summary extras preserved
      expect(p['property'], isNotNull); // legacy unwrap still works
    });

    test('adds name fallback for flat entities without a name field',
        () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      dio.httpClientAdapter = _StubAdapter(responseBody: [
        {'id': 'p2', 'nameEn': 'Marina Heights'},
      ]);

      final list = await PropertyService(dio).getProperties();
      expect((list.first as Map)['name'], 'Marina Heights');
    });
  });
}

class _StubAdapter implements HttpClientAdapter {
  _StubAdapter({required this.responseBody});

  final Object responseBody;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    return ResponseBody.fromBytes(
      utf8.encode(jsonEncode(responseBody)),
      200,
      headers: {
        'content-type': ['application/json']
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

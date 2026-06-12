import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/cheque_extraction_service.dart';

void main() {
  group('ChequeExtractionResult.fromJson', () {
    test('parses image and extracted fields on happy path', () {
      final result = ChequeExtractionResult.fromJson({
        'image': {
          'url': 'https://x',
          'blobPath': 'cheques/abc.jpg',
          'uploadedAt': '2026-05-04T10:23:00Z',
        },
        'extracted': {
          'chequeNumber': '123',
          'bankName': 'ENBD',
          'payerName': 'Acme',
          'chequeDate': '2026-06-01',
          'amount': 12500.50,
          'confidence': 'HIGH',
        },
        'warnings': <String>[],
      });

      expect(result.imageUrl, 'https://x');
      expect(result.imageBlobPath, 'cheques/abc.jpg');
      expect(result.uploadedAt.toIso8601String(), '2026-05-04T10:23:00.000Z');
      expect(result.extracted?['chequeNumber'], '123');
      expect(result.amount, 12500.50);
      expect(result.confidence, 'HIGH');
      expect(result.warnings, isEmpty);
    });

    test('amount getter handles string, null and missing values', () {
      ChequeExtractionResult build(Map<String, dynamic>? extracted) =>
          ChequeExtractionResult.fromJson({
            'image': {
              'url': 'https://x',
              'blobPath': 'cheques/abc.jpg',
              'uploadedAt': '2026-05-04T10:23:00Z',
            },
            'extracted': extracted,
            'warnings': <String>[],
          });

      expect(build({'amount': '5000.00'}).amount, 5000.00);
      expect(build({'amount': 5000}).amount, 5000);
      expect(build({'amount': null}).amount, isNull);
      expect(build({}).amount, isNull);
      expect(build(null).amount, isNull);
      expect(build(null).confidence, isNull);
      expect(build({'confidence': 'LOW'}).confidence, 'LOW');
    });

    test('preserves null extracted when extraction fails', () {
      final result = ChequeExtractionResult.fromJson({
        'image': {
          'url': 'https://x',
          'blobPath': 'cheques/abc.jpg',
          'uploadedAt': '2026-05-04T10:23:00Z',
        },
        'extracted': null,
        'warnings': ['bank name obscured', 'date illegible'],
      });

      expect(result.imageUrl, 'https://x');
      expect(result.extracted, isNull);
      expect(result.warnings, ['bank name obscured', 'date illegible']);
    });

    test('handles missing warnings field gracefully', () {
      final result = ChequeExtractionResult.fromJson({
        'image': {
          'url': 'https://x',
          'blobPath': 'cheques/abc.jpg',
          'uploadedAt': '2026-05-04T10:23:00Z',
        },
        'extracted': null,
      });

      expect(result.warnings, isEmpty);
    });
  });

  group('ChequeExtractionService.extract', () {
    test('POSTs multipart to /v1/cheques/extract and parses response',
        () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://api.example'));
      final adapter = _CapturingAdapter(
        responseBody: {
          'image': {
            'url': 'https://api.example/c.jpg',
            'blobPath': 'cheques/c.jpg',
            'uploadedAt': '2026-05-04T10:23:00Z',
          },
          'extracted': {
            'chequeNumber': '789',
            'bankName': 'FAB',
            'payerName': null,
            'chequeDate': null,
            'confidence': 'LOW',
          },
          'warnings': ['payer obscured'],
        },
      );
      dio.httpClientAdapter = adapter;

      // Use a bytes-backed MultipartFile so we don't need a real file on disk.
      final form = FormData.fromMap({
        'file': MultipartFile.fromBytes(Uint8List.fromList([1, 2, 3]),
            filename: 'cheque.jpg', contentType: DioMediaType('image', 'jpeg')),
      });
      final response = await dio.post('/v1/cheques/extract', data: form);
      final result = ChequeExtractionResult.fromJson(
        Map<String, dynamic>.from(response.data as Map),
      );

      expect(adapter.lastRequest?.path, '/v1/cheques/extract');
      expect(adapter.lastRequest?.method, 'POST');
      expect(adapter.lastRequest?.headers['content-type'].toString(),
          startsWith('multipart/form-data'));
      expect(result.imageBlobPath, 'cheques/c.jpg');
      expect(result.extracted?['confidence'], 'LOW');
      expect(result.warnings, ['payer obscured']);
    });

    test('propagates DioException on 500 errors', () async {
      final dio = Dio();
      dio.httpClientAdapter = _ErrorAdapter(statusCode: 500);

      expect(
        () => dio.post('/v1/cheques/extract', data: FormData()),
        throwsA(isA<DioException>()),
      );
    });
  });
}

/// Captures requests and returns a stubbed JSON response.
class _CapturingAdapter implements HttpClientAdapter {
  _CapturingAdapter({required this.responseBody});

  final Map<String, dynamic> responseBody;
  RequestOptions? lastRequest;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    lastRequest = options;
    // Drain the body so multipart serialization is exercised.
    if (requestStream != null) {
      await requestStream.drain<void>();
    }
    final bytes = utf8.encode(jsonEncode(responseBody));
    return ResponseBody.fromBytes(bytes, 200, headers: {
      'content-type': ['application/json']
    });
  }

  @override
  void close({bool force = false}) {}
}

class _ErrorAdapter implements HttpClientAdapter {
  _ErrorAdapter({required this.statusCode});
  final int statusCode;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    if (requestStream != null) {
      await requestStream.drain<void>();
    }
    return ResponseBody.fromBytes(utf8.encode('{"error":"boom"}'), statusCode,
        headers: {
          'content-type': ['application/json']
        });
  }

  @override
  void close({bool force = false}) {}
}

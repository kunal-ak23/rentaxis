import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/receipt_service.dart';
import 'package:rentaxis_core/utils/error_message.dart';

void main() {
  test('combined posts ids and returns bytes + header filename', () async {
    final adapter = _Adapter(
      200,
      [37, 80, 68, 70],
      {
        'content-type': ['application/pdf'],
        'content-disposition': ['attachment; filename=receipts-L1.pdf'],
      },
    );
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example'))
      ..httpClientAdapter = adapter;

    final r = await ReceiptService(dio).combined(['a', 'b']);

    expect(adapter.last!.path, '/v1/cheques/receipts/combined');
    expect(adapter.last!.method, 'POST');
    expect(adapter.last!.data, {
      'chequeIds': ['a', 'b']
    });
    expect(r.bytes, [37, 80, 68, 70]);
    expect(r.fileName, 'receipts-L1.pdf');
  });

  test('400 JSON body reaches errorMessage', () async {
    const msg = 'A combined receipt holds the receipts of one contract';
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example'))
      ..httpClientAdapter = _Adapter(
        400,
        utf8.encode(jsonEncode({'message': msg})),
        {
          'content-type': ['application/json'],
        },
      );
    Object? caught;
    try {
      await ReceiptService(dio).combined(['a']);
    } catch (e) {
      caught = e;
    }
    expect(caught, isA<DioException>());
    expect(errorMessage(caught!, 'fallback'), msg);
  });

  test('empty success body throws', () async {
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example'))
      ..httpClientAdapter = _Adapter(200, <int>[], {
        'content-type': ['application/pdf'],
      });
    expect(ReceiptService(dio).combined(['a']), throwsA(isA<DioException>()));
  });
}

class _Adapter implements HttpClientAdapter {
  _Adapter(this.status, this.body, this.headers);
  final int status;
  final List<int> body;
  final Map<String, List<String>> headers;
  RequestOptions? last;

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    last = options;
    return ResponseBody.fromBytes(body, status, headers: headers);
  }

  @override
  void close({bool force = false}) {}
}

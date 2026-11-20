import 'dart:convert';

import 'package:dio/dio.dart';

class CombinedReceipt {
  final List<int> bytes;
  final String fileName;
  const CombinedReceipt({required this.bytes, required this.fileName});
}

/// File name from a Content-Disposition header, else `receipts.pdf`.
String combinedReceiptFileName(String? contentDisposition) {
  if (contentDisposition != null) {
    final m = RegExp(r'filename="?([^";]+)"?', caseSensitive: false)
        .firstMatch(contentDisposition);
    final raw = m?.group(1)?.trim();
    final name = raw?.split(RegExp(r'[\\/]')).last.trim();
    if (name != null && name.isNotEmpty) return name;
  }
  return 'receipts.pdf';
}

class ReceiptService {
  final Dio _dio;
  ReceiptService(this._dio);

  Future<CombinedReceipt> combined(List<String> chequeIds) async {
    final Response<List<int>> response;
    try {
      response = await _dio.post<List<int>>(
        '/v1/cheques/receipts/combined',
        data: {'chequeIds': chequeIds},
        options: Options(responseType: ResponseType.bytes),
      );
    } on DioException catch (e) {
      // With ResponseType.bytes an error body arrives as raw bytes; decode it
      // so errorMessage() can read the server's message.
      final data = e.response?.data;
      if (e.response != null && data is List<int>) {
        Object? decoded;
        try {
          decoded = jsonDecode(utf8.decode(data));
        } catch (_) {
          decoded = null;
        }
        if (decoded is Map) {
          e.response!.data = Map<String, dynamic>.from(decoded);
        }
      }
      rethrow;
    }
    final bytes = response.data;
    if (bytes == null || bytes.isEmpty) {
      throw DioException(
        requestOptions: response.requestOptions,
        response: response,
        type: DioExceptionType.badResponse,
        message: 'Empty receipt response',
      );
    }
    return CombinedReceipt(
      bytes: bytes,
      fileName: combinedReceiptFileName(
          response.headers.value('content-disposition')),
    );
  }
}

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
    final name = m?.group(1)?.trim();
    if (name != null && name.isNotEmpty) return name;
  }
  return 'receipts.pdf';
}

class ReceiptService {
  final Dio _dio;
  ReceiptService(this._dio);

  Future<CombinedReceipt> combined(List<String> chequeIds) async {
    final response = await _dio.post<List<int>>(
      '/v1/cheques/receipts/combined',
      data: {'chequeIds': chequeIds},
      options: Options(responseType: ResponseType.bytes),
    );
    return CombinedReceipt(
      bytes: response.data ?? const <int>[],
      fileName:
          combinedReceiptFileName(response.headers.value('content-disposition')),
    );
  }
}

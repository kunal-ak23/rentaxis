import 'dart:io';

import 'package:dio/dio.dart';

/// The organisation's payee-name check on one scanned cheque, as the server
/// ran it at extract time. Null on the result when the check is off, lists no
/// names, or nothing was read.
class PayeeCheck {
  PayeeCheck._();
  static const mismatch = 'MISMATCH';
  static const unreadable = 'UNREADABLE';
}

/// One cheque the server read, with the server-issued image that shows it —
/// a crop when the photo held several. [imageBlobPath] is what
/// `POST /leases/{id}/cheques/bulk-attach` takes.
class ChequeExtractionResult {
  final String imageUrl;
  final String imageBlobPath;
  final DateTime uploadedAt;
  final Map<String, dynamic>? extracted;
  final List<String> warnings;

  /// MATCH | MISMATCH | UNREADABLE, or null when the check did not run.
  final String? payeeCheck;

  /// Machine-readable flags, e.g. [cropUnreliableFlag].
  final List<String> flags;

  /// A small JPEG data URL of the image, or null.
  final String? thumbnailUrl;

  ChequeExtractionResult({
    required this.imageUrl,
    required this.imageBlobPath,
    required this.uploadedAt,
    this.extracted,
    required this.warnings,
    this.payeeCheck,
    this.flags = const [],
    this.thumbnailUrl,
  });

  /// The server could not cut this cheque out cleanly, so [imageUrl] is the
  /// whole page.
  static const cropUnreliableFlag = 'crop_unreliable';

  /// OCR-extracted cheque amount in AED, or null if unreadable.
  num? get amount {
    final raw = extracted?['amount'];
    if (raw == null) return null;
    return raw is num ? raw : num.tryParse(raw.toString());
  }

  /// Extraction confidence: HIGH | MEDIUM | LOW, or null.
  String? get confidence => extracted?['confidence']?.toString();

  /// The "Pay to" line as read, or null.
  String? get payeeName {
    final v = extracted?['payeeName']?.toString().trim();
    return v == null || v.isEmpty ? null : v;
  }

  bool get cropUnreliable => flags.contains(cropUnreliableFlag);

  /// Nothing at all was read off this cheque (blurred photo, OCR down).
  bool get nothingRead {
    final ex = extracted;
    if (ex == null) return true;
    bool blank(String k) => (ex[k]?.toString() ?? '').trim().isEmpty;
    return blank('chequeNumber') &&
        blank('bankName') &&
        blank('payerName') &&
        blank('chequeDate') &&
        ex['amount'] == null;
  }

  static List<String> _strings(dynamic raw) =>
      raw is List ? raw.map((e) => e.toString()).toList() : const <String>[];

  /// One `/cheques/extract-many` item (`DetectedChequeItemDTO`).
  factory ChequeExtractionResult.fromJson(Map<String, dynamic> json) {
    final image = Map<String, dynamic>.from(json['image'] as Map);
    return ChequeExtractionResult(
      imageUrl: image['url']?.toString() ?? '',
      imageBlobPath: image['blobPath']?.toString() ?? '',
      uploadedAt: DateTime.parse(image['uploadedAt'].toString()),
      extracted: json['extracted'] == null
          ? null
          : Map<String, dynamic>.from(json['extracted'] as Map),
      warnings: _strings(json['warnings']),
      payeeCheck: json['payeeCheck']?.toString(),
      flags: _strings(json['flags']),
      thumbnailUrl: json['thumbnailUrl']?.toString(),
    );
  }
}

/// `POST /cheques/extract-many`: every cheque in one photo or PDF, each with
/// its own server-issued image.
class ChequeMultiExtractionResult {
  final List<ChequeExtractionResult> items;
  final List<String> warnings;

  ChequeMultiExtractionResult({required this.items, required this.warnings});

  factory ChequeMultiExtractionResult.fromJson(Map<String, dynamic> json) {
    return ChequeMultiExtractionResult(
      items: (json['items'] as List)
          .whereType<Map>()
          .map((e) => ChequeExtractionResult.fromJson(Map.from(e)))
          .toList(),
      warnings: ChequeExtractionResult._strings(json['warnings']),
    );
  }
}

/// A refused upload: [code] is the server's refusal code when it sent one
/// (`cheque_upload_pdf_not_supported`, `cheque_storage_unavailable`, ...).
class ChequeUploadRefused implements Exception {
  final String message;
  final String? code;
  ChequeUploadRefused(this.message, this.code);
  @override
  String toString() => message;
}

class ChequeExtractionService {
  final Dio _dio;

  ChequeExtractionService(this._dio);

  /// Every cheque in [file] (a photo of one or several, or a PDF).
  Future<ChequeMultiExtractionResult> extractMany(File file) async {
    final form = FormData.fromMap({
      // The server checks the part's content type; Dio infers it from the
      // file name's extension (.jpg / .png / .heic / .pdf).
      'file': await MultipartFile.fromFile(file.path),
    });
    try {
      final response = await _dio.post(
        '/v1/cheques/extract-many',
        data: form,
        options: Options(
          // A slow model read of a busy photo takes well under a minute.
          receiveTimeout: const Duration(minutes: 2),
        ),
      );
      return ChequeMultiExtractionResult.fromJson(
        Map<String, dynamic>.from(response.data as Map),
      );
    } on DioException catch (e) {
      final body = e.response?.data;
      if (body is Map && (body['error'] != null || body['code'] != null)) {
        throw ChequeUploadRefused(
          body['error']?.toString() ?? 'Upload failed',
          body['code']?.toString(),
        );
      }
      rethrow;
    }
  }
}

import 'dart:io';

import 'package:dio/dio.dart';

class ChequeExtractionResult {
  final String imageUrl;
  final String imageBlobPath;
  final DateTime uploadedAt;
  final Map<String, dynamic>? extracted;
  final List<String> warnings;

  ChequeExtractionResult({
    required this.imageUrl,
    required this.imageBlobPath,
    required this.uploadedAt,
    this.extracted,
    required this.warnings,
  });

  factory ChequeExtractionResult.fromJson(Map<String, dynamic> json) {
    final image = Map<String, dynamic>.from(json['image'] as Map);
    return ChequeExtractionResult(
      imageUrl: image['url']?.toString() ?? '',
      imageBlobPath: image['blobPath']?.toString() ?? '',
      uploadedAt: DateTime.parse(image['uploadedAt'].toString()),
      extracted: json['extracted'] == null
          ? null
          : Map<String, dynamic>.from(json['extracted'] as Map),
      warnings: List<String>.from(json['warnings'] ?? const <String>[]),
    );
  }
}

class ChequeExtractionService {
  final Dio _dio;

  ChequeExtractionService(this._dio);

  Future<ChequeExtractionResult> extract(File image) async {
    final form = FormData.fromMap({
      'file': await MultipartFile.fromFile(image.path),
    });

    final response = await _dio.post('/v1/cheques/extract', data: form);
    return ChequeExtractionResult.fromJson(
      Map<String, dynamic>.from(response.data as Map),
    );
  }
}

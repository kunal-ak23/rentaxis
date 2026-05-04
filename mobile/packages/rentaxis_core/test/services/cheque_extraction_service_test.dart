import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/cheque_extraction_service.dart';

void main() {
  test('fromJson parses image and extracted fields', () {
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
        'confidence': 'HIGH',
      },
      'warnings': <String>[],
    });

    expect(result.imageUrl, 'https://x');
    expect(result.imageBlobPath, 'cheques/abc.jpg');
    expect(result.extracted?['chequeNumber'], '123');
  });
}

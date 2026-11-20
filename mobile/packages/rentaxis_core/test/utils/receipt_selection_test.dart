import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/utils/receipt_selection.dart';
import 'package:rentaxis_core/api/services/receipt_service.dart';

Map<String, dynamic> row(String id, String status,
        {String? clearedAt,
        String? chequeDate,
        String? dueDate,
        bool? settled}) =>
    {
      'id': id,
      'status': status,
      'clearedAt': clearedAt,
      'chequeDate': chequeDate,
      'dueDate': dueDate,
      'settledBeforeAcquisition': settled,
    };

void main() {
  test('maxCombinedReceipts is 100', () => expect(maxCombinedReceipts, 100));

  test('receivedYear prefers clearedAt, then chequeDate, then dueDate', () {
    expect(
        receivedYear(row('a', 'CLEARED',
            clearedAt: '2025-12-31T23:00:00Z',
            chequeDate: '2024-01-01',
            dueDate: '2023-01-01')),
        2025);
    expect(
        receivedYear(row('a', 'CLEARED',
            chequeDate: '2024-01-01', dueDate: '2023-01-01')),
        2024);
    expect(receivedYear(row('a', 'CLEARED', dueDate: '2023-05-01')), 2023);
  });

  test('receivedYear with no date does not crash', () {
    expect(receivedYear(row('a', 'CLEARED')), isNull);
  });

  test('isReceiptable', () {
    expect(isReceiptable(row('a', 'CLEARED')), isTrue);
    expect(isReceiptable(row('a', 'CLEARED', settled: false)), isTrue);
    expect(isReceiptable(row('a', 'CLEARED', settled: true)), isFalse);
    expect(isReceiptable(row('a', 'BOUNCED')), isFalse);
    expect(isReceiptable(row('a', 'PENDING')), isFalse);
  });

  final rows = [
    row('1', 'CLEARED', clearedAt: '2024-03-01'),
    row('2', 'CLEARED', clearedAt: '2025-03-01'),
    row('3', 'CLEARED', chequeDate: '2025-06-01'),
    row('4', 'BOUNCED', clearedAt: '2026-01-01'),
    row('5', 'CLEARED', clearedAt: '2023-01-01', settled: true),
    row('6', 'CLEARED'),
  ];

  test('receiptYears distinct newest first, receiptable only, no-date skipped',
      () {
    expect(receiptYears(rows), [2025, 2024]);
  });

  test('idsInYear receiptable only', () {
    expect(idsInYear(rows, 2025), ['2', '3']);
    expect(idsInYear(rows, 2026), isEmpty);
    expect(idsInYear(rows, 2023), isEmpty);
  });

  group('combinedReceiptFileName', () {
    test('parses filename', () {
      expect(
          combinedReceiptFileName('attachment; filename=receipts-L1.pdf'),
          'receipts-L1.pdf');
      expect(
          combinedReceiptFileName('attachment; filename="receipts-L1.pdf"'),
          'receipts-L1.pdf');
    });
    test('falls back', () {
      expect(combinedReceiptFileName(null), 'receipts.pdf');
      expect(combinedReceiptFileName('attachment'), 'receipts.pdf');
    });
  });
}

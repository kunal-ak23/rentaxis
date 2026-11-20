/// Most receipts one combined download may carry (matches the backend limit).
const maxCombinedReceipts = 100;

/// Calendar year of clearedAt, else chequeDate, else dueDate, read from the
/// first 4 chars of the ISO string (no timezone shift). Null when no date.
int? receivedYear(Map<String, dynamic> row) {
  for (final key in const ['clearedAt', 'chequeDate', 'dueDate']) {
    final v = row[key];
    if (v is String && v.length >= 4) {
      final y = int.tryParse(v.substring(0, 4));
      if (y != null) return y;
    }
  }
  return null;
}

bool isReceiptable(Map<String, dynamic> row) =>
    row['status'] == 'CLEARED' && row['settledBeforeAcquisition'] != true;

/// Distinct years of receiptable rows, newest first. Undated rows excluded.
List<int> receiptYears(Iterable<Map<String, dynamic>> rows) {
  final years = <int>{};
  for (final r in rows) {
    if (!isReceiptable(r)) continue;
    final y = receivedYear(r);
    if (y != null) years.add(y);
  }
  return years.toList()..sort((a, b) => b.compareTo(a));
}

/// Ids of receiptable rows whose year is [year].
List<String> idsInYear(Iterable<Map<String, dynamic>> rows, int year) => [
      for (final r in rows)
        if (isReceiptable(r) && r['id'] != null && receivedYear(r) == year)
          r['id'].toString(),
    ];

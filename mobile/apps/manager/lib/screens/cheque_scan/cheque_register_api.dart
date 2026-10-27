import 'package:dio/dio.dart';
import 'package:intl/intl.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// The accounting-v2 cheque register, as the scan wizard needs it.
///
/// A scanned cheque lands on a row of the lease's own register through
/// `POST /v1/leases/{leaseId}/cheques/bulk-attach` — the endpoint the web
/// scanner uses, and the one that enforces the organisation's payee-name
/// check server-side (an unconfirmed MISMATCH is refused with
/// `payee_mismatch_unconfirmed`).
class ChequeRegisterApi {
  final Dio _dio;
  ChequeRegisterApi(this._dio);

  /// One page of registered PDC rows tenant-wide, soonest cheque date first
  /// (`GET /v1/cheques`). Draft-grid rows are not in the register search; a
  /// contract being drafted takes its scans on the web.
  Future<Map<String, dynamic>> searchScannable({
    String? search,
    int size = 50,
  }) async {
    final response = await _dio.get(
      '/v1/cheques',
      queryParameters: {
        'status': 'REGISTERED',
        'mode': 'PDC',
        if (search != null && search.trim().isNotEmpty) 'search': search.trim(),
        'sort': ['chequeDate,asc', 'seqNo,asc'],
        'size': size,
      },
    );
    return Map<String, dynamic>.from(response.data as Map);
  }

  /// Every row of one lease (`GET /v1/leases/{id}/cheques`), for mapping the
  /// cheques of a multi-cheque photo onto that lease's instalments.
  Future<List<Map<String, dynamic>>> leaseCheques(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/cheques');
    return (response.data as List)
        .whereType<Map>()
        .map((e) => Map<String, dynamic>.from(e))
        .toList();
  }

  /// Attach scans to rows of one lease, all or nothing.
  /// Throws [BulkAttachRefused] with the server's per-row reasons.
  Future<void> bulkAttach(
    String leaseId,
    List<Map<String, dynamic>> items,
  ) async {
    try {
      await _dio.post(
        '/v1/leases/$leaseId/cheques/bulk-attach',
        data: {'items': items},
      );
    } on DioException catch (e) {
      final body = e.response?.data;
      if (body is Map && body['rows'] is List) {
        throw BulkAttachRefused([
          for (final r in body['rows'] as List)
            if (r is Map) r['reason']?.toString() ?? '',
        ]);
      }
      rethrow;
    }
  }

  /// Bank the cheque today (`PUT /v1/cheques/{id}/deposit`).
  Future<void> deposit(String chequeId) async {
    await _dio.put('/v1/cheques/$chequeId/deposit', data: {});
  }
}

/// The server refused a bulk-attach; [reasons] has one entry per bad row.
class BulkAttachRefused implements Exception {
  final List<String> reasons;
  BulkAttachRefused(this.reasons);
}

/// A PDC row a scan can be attached to (`ChequeDetailsService.BULK_EDITABLE`),
/// the same rule as the web's `isScannableRow`.
bool isScannableRow(Map<String, dynamic> row) =>
    row['mode']?.toString() == 'PDC' &&
    (row['status']?.toString() == 'DRAFT' ||
        row['status']?.toString() == 'REGISTERED');

/// One `BulkAttachChequeItem`. The image fields are the server-issued ones
/// from the scan; [payeeMismatchConfirmed] is sent only for a MISMATCH scan,
/// as the web does — the server ignores it otherwise.
Map<String, dynamic> buildAttachItem({
  required String chequeId,
  required ChequeExtractionResult scan,
  required String chequeNumber,
  required String bankName,
  required String payerName,
  required DateTime chequeDate,
  required bool payeeConfirmed,
}) {
  final payer = payerName.trim();
  return {
    'chequeId': chequeId,
    'chequeNumber': chequeNumber.trim(),
    'chequeDate': DateFormat('yyyy-MM-dd').format(chequeDate),
    'bankName': bankName.trim(),
    'payerName': payer.isEmpty ? null : payer,
    'imageUrl': scan.imageUrl,
    'imageBlobPath': scan.imageBlobPath,
    // UTC so the ISO string carries a `Z`: Jackson's OffsetDateTime refuses a
    // string with no offset.
    'imageUploadedAt': scan.uploadedAt.toUtc().toIso8601String(),
    if (scan.payeeCheck == PayeeCheck.mismatch)
      'payeeMismatchConfirmed': payeeConfirmed,
  };
}

/// AED: rounding on the paper, not a different cheque (web AMOUNT_TOLERANCE).
const _amountTolerance = 1;

bool amountsDiffer(num? a, num? b) =>
    a != null && b != null && (a - b).abs() > _amountTolerance;

num? asNum(dynamic raw) =>
    raw == null ? null : (raw is num ? raw : num.tryParse(raw.toString()));

/// The date a scan of this row's cheque should carry: its own cheque date,
/// the posting (contract) date only as a fallback.
String? rowChequeDate(Map<String, dynamic> row) =>
    (row['chequeDate'] ?? row['postingDate'])?.toString();

/// Port of the web's `autoMapChequesToRows`: date first, amount only breaks a
/// tie, and a scan whose amount differs from a row's by more than the
/// tolerance is never auto-mapped to it. Pinned items keep their row.
/// Returns item index → row id.
Map<int, String> autoMapScansToRows({
  required List<({DateTime? date, num? amount, String? pinnedRowId})> items,
  required List<Map<String, dynamic>> rows,
}) {
  final remaining = rows.map((r) => r['id'].toString()).toSet();
  for (final it in items) {
    if (it.pinnedRowId != null) remaining.remove(it.pinnedRowId);
  }
  final pairs = <({int item, String row, int dist})>[];
  for (var i = 0; i < items.length; i++) {
    final it = items[i];
    if (it.pinnedRowId != null || it.date == null) continue;
    for (final row in rows) {
      final id = row['id'].toString();
      if (!remaining.contains(id)) continue;
      final rowAmount = asNum(row['amount']);
      if (amountsDiffer(it.amount, rowAmount)) continue;
      final rowDate = DateTime.tryParse(rowChequeDate(row) ?? '');
      if (rowDate == null) continue;
      final days = DateTime.utc(it.date!.year, it.date!.month, it.date!.day)
          .difference(DateTime.utc(rowDate.year, rowDate.month, rowDate.day))
          .inDays
          .abs();
      final amountDist = it.amount != null && rowAmount != null
          ? ((it.amount! - rowAmount).abs().round()).clamp(0, 999)
          : 0;
      pairs.add((item: i, row: id, dist: days * 1000 + amountDist));
    }
  }
  pairs.sort((a, b) => a.dist.compareTo(b.dist));
  final out = <int, String>{};
  for (final p in pairs) {
    if (out.containsKey(p.item) || !remaining.contains(p.row)) continue;
    out[p.item] = p.row;
    remaining.remove(p.row);
  }
  for (var i = 0; i < items.length; i++) {
    final pinned = items[i].pinnedRowId;
    if (pinned != null) out[i] = pinned;
  }
  return out;
}

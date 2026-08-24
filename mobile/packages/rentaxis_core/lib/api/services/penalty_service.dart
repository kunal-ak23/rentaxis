import 'package:dio/dio.dart';

class PenaltyService {
  final Dio _dio;
  PenaltyService(this._dio);

  /// Returns paginated penalties scoped to the current user's leases.
  /// [status]: 'open' | 'cleared' | 'all'
  /// [leaseId]: optional filter by lease.
  /// Returns the unwrapped Page content array.
  Future<List<Map<String, dynamic>>> listPenalties({
    String status = 'all',
    String? leaseId,
    int page = 0,
    int size = 50,
  }) async {
    final response = await _dio.get(
      '/v1/penalties',
      queryParameters: {
        'status': status,
        'leaseId': ?leaseId,
        'page': page,
        'size': size,
      },
    );
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List<dynamic>? ?? [];
    return content.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  /// All penalties for one lease. There is no /v1/leases/{id}/penalties
  /// route on the backend — the real surface is GET /v1/penalties?leaseId=
  /// (PenaltyController), so this delegates to [listPenalties].
  Future<List<dynamic>> getPenalties(String leaseId) =>
      listPenalties(leaseId: leaseId, size: 200);

  /// Waives a penalty. Backend maps POST /v1/penalties/{id}/waive and
  /// requires a non-blank reason (@NotBlank on WaivePenaltyRequestDTO).
  Future<Map<String, dynamic>> waivePenalty(
    String penaltyId, {
    required String reason,
  }) async {
    final response = await _dio.post(
      '/v1/penalties/$penaltyId/waive',
      data: {'reason': reason},
    );
    return response.data;
  }
}

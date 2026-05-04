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
    final response = await _dio.get('/v1/penalties', queryParameters: {
      'status': status,
      if (leaseId != null) 'leaseId': leaseId,
      'page': page,
      'size': size,
    });
    final data = response.data as Map<String, dynamic>;
    final content = data['content'] as List<dynamic>? ?? [];
    return content.map((e) => Map<String, dynamic>.from(e as Map)).toList();
  }

  Future<List<dynamic>> getPenalties(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/penalties');
    return response.data;
  }

  Future<Map<String, dynamic>> waivePenalty(String penaltyId, {String? reason}) async {
    final response = await _dio.put('/v1/penalties/$penaltyId/waive', data: {
      if (reason != null) 'reason': reason,
    });
    return response.data;
  }

  Future<List<dynamic>> recalculatePenalties(String leaseId) async {
    final response = await _dio.post('/v1/leases/$leaseId/penalties/recalculate');
    return response.data;
  }
}

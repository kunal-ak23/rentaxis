import 'package:dio/dio.dart';

class PenaltyService {
  final Dio _dio;
  PenaltyService(this._dio);

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

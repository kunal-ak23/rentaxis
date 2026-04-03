import 'package:dio/dio.dart';

class SettlementService {
  final Dio _dio;
  SettlementService(this._dio);

  Future<Map<String, dynamic>> getSettlementPreview(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/settlement/preview');
    return response.data;
  }

  Future<Map<String, dynamic>> getSettlement(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/settlement');
    return response.data;
  }
}

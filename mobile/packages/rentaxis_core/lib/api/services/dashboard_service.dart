import 'package:dio/dio.dart';

class DashboardService {
  final Dio _dio;
  DashboardService(this._dio);

  Future<Map<String, dynamic>> getSummary() async {
    final response = await _dio.get('/v1/dashboard/summary');
    return response.data;
  }
}

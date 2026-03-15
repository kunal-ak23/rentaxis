import 'package:dio/dio.dart';

class RenterService {
  final Dio _dio;
  RenterService(this._dio);

  Future<List<dynamic>> getRenters() async {
    final response = await _dio.get('/v1/renters');
    return response.data;
  }

  Future<Map<String, dynamic>> getRenterById(String id) async {
    final response = await _dio.get('/v1/renters/$id');
    return response.data;
  }

  Future<Map<String, dynamic>> createRenter(
      Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/renters', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> updateRenter(
      String id, Map<String, dynamic> data) async {
    final response = await _dio.put('/v1/renters/$id', data: data);
    return response.data;
  }
}

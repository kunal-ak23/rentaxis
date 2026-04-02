import 'package:dio/dio.dart';

class StaffService {
  final Dio _dio;
  StaffService(this._dio);

  Future<List<dynamic>> getStaff() async {
    final response = await _dio.get('/v1/staff');
    return response.data;
  }

  Future<Map<String, dynamic>> getStaffById(String id) async {
    final response = await _dio.get('/v1/staff/$id');
    return response.data;
  }

  Future<List<dynamic>> getStaffByProperty(String propertyId) async {
    final response = await _dio.get('/v1/staff/by-property/$propertyId');
    return response.data;
  }

  Future<Map<String, dynamic>> createStaff(Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/staff', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> updateStaff(String id, Map<String, dynamic> data) async {
    final response = await _dio.put('/v1/staff/$id', data: data);
    return response.data;
  }

  Future<void> deleteStaff(String id) async {
    await _dio.delete('/v1/staff/$id');
  }
}

import 'package:dio/dio.dart';

class PropertyService {
  final Dio _dio;
  PropertyService(this._dio);

  Future<List<dynamic>> getProperties() async {
    final response = await _dio.get('/v1/properties');
    return response.data;
  }

  Future<Map<String, dynamic>> getPropertyById(String id) async {
    final response = await _dio.get('/v1/properties/$id');
    return response.data;
  }

  Future<Map<String, dynamic>> createProperty(
      Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/properties', data: data);
    return response.data;
  }

  Future<List<dynamic>> getPropertyManagers(String propertyId) async {
    final response = await _dio.get('/v1/properties/$propertyId/managers');
    return response.data;
  }
}

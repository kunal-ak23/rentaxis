import 'package:dio/dio.dart';

class VendorService {
  final Dio _dio;
  VendorService(this._dio);

  Future<List<dynamic>> getVendors() async {
    final response = await _dio.get('/v1/vendors');
    return response.data;
  }

  Future<Map<String, dynamic>> getVendorById(String id) async {
    final response = await _dio.get('/v1/vendors/$id');
    return response.data;
  }

  Future<Map<String, dynamic>> createVendor(Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/vendors', data: data);
    return response.data;
  }
}

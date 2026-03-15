import 'package:dio/dio.dart';

class UnitService {
  final Dio _dio;
  UnitService(this._dio);

  Future<List<dynamic>> getUnits() async {
    final response = await _dio.get('/v1/units');
    return response.data;
  }

  Future<List<dynamic>> getUnitsByProperty(String propertyId) async {
    final response = await _dio.get('/v1/units/property/$propertyId');
    return response.data;
  }

  Future<Map<String, dynamic>> createUnit(Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/units', data: data);
    return response.data;
  }
}

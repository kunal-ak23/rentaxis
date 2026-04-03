import 'package:dio/dio.dart';

class BuildingService {
  final Dio _dio;
  BuildingService(this._dio);

  Future<List<dynamic>> getBuildingsByProperty(String propertyId) async {
    final response = await _dio.get('/v1/buildings/property/$propertyId');
    return response.data;
  }
}

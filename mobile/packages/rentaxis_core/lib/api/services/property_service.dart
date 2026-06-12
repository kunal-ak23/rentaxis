import 'package:dio/dio.dart';

class PropertyService {
  final Dio _dio;
  PropertyService(this._dio);

  Future<List<dynamic>> getProperties() async {
    final response = await _dio.get('/v1/properties');
    final data = response.data as List<dynamic>;
    // Backend returns summaries: {property: {id, nameEn, ...}, vacancies, ...}.
    // Flatten so callers can read id/name at the top level regardless of
    // shape, while keeping the summary extras (vacancies, propertyCount, …)
    // and the nested `property` key for callers that still unwrap manually.
    return data.map((item) {
      if (item is Map && item['property'] is Map) {
        final property = Map<String, dynamic>.from(item['property'] as Map);
        return <String, dynamic>{
          ...Map<String, dynamic>.from(item),
          ...property,
          'name': property['nameEn'] ?? property['name'],
        };
      }
      if (item is Map && item['name'] == null) {
        return <String, dynamic>{
          ...Map<String, dynamic>.from(item),
          'name': item['nameEn'],
        };
      }
      return item;
    }).toList();
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

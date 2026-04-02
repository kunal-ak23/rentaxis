import 'package:dio/dio.dart';

class PropertyContactService {
  final Dio _dio;
  PropertyContactService(this._dio);

  Future<List<dynamic>> getContacts(String propertyId) async {
    final response = await _dio.get('/v1/properties/$propertyId/contacts');
    return response.data;
  }

  Future<Map<String, dynamic>> createContact(String propertyId, Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/properties/$propertyId/contacts', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> updateContact(String propertyId, String contactId, Map<String, dynamic> data) async {
    final response = await _dio.put('/v1/properties/$propertyId/contacts/$contactId', data: data);
    return response.data;
  }

  Future<void> deleteContact(String propertyId, String contactId) async {
    await _dio.delete('/v1/properties/$propertyId/contacts/$contactId');
  }
}

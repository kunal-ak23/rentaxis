import 'package:dio/dio.dart';

class SettingsService {
  final Dio _dio;
  SettingsService(this._dio);

  Future<Map<String, dynamic>?> getRentSettings(String propertyId) async {
    final response = await _dio.get('/v1/rent-settings/$propertyId');
    if (response.statusCode == 204) return null;
    return response.data;
  }

  Future<List<dynamic>> getAvailableGateways() async {
    final response = await _dio.get('/v1/gateway-config/gateways');
    return response.data;
  }

  Future<Map<String, dynamic>?> getGatewayConfig() async {
    final response = await _dio.get('/v1/gateway-config');
    if (response.statusCode == 204) return null;
    return response.data;
  }

  Future<List<dynamic>> getAccountMappings() async {
    final response = await _dio.get('/v1/finance/account-mappings');
    return response.data;
  }

  Future<List<dynamic>> getTransactionNatures() async {
    final response = await _dio.get('/v1/finance/account-mappings/natures');
    return response.data;
  }
}

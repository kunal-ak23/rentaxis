import 'package:dio/dio.dart';

class ReportService {
  final Dio _dio;
  ReportService(this._dio);

  Future<Map<String, dynamic>> getPropertyReport(
    String propertyId, {
    String? startDate,
    String? endDate,
  }) async {
    final response = await _dio.get(
      '/v1/finance/reports/property/$propertyId',
      queryParameters: {'startDate': ?startDate, 'endDate': ?endDate},
    );
    return response.data;
  }

  Future<Map<String, dynamic>> getUnitReport(
    String unitId, {
    String? startDate,
    String? endDate,
  }) async {
    final response = await _dio.get(
      '/v1/finance/reports/unit/$unitId',
      queryParameters: {'startDate': ?startDate, 'endDate': ?endDate},
    );
    return response.data;
  }

  Future<Map<String, dynamic>> getVatReturn({
    required String startDate,
    required String endDate,
  }) async {
    final response = await _dio.get(
      '/v1/finance/reports/vat-return',
      queryParameters: {'startDate': startDate, 'endDate': endDate},
    );
    return response.data;
  }

  Future<List<dynamic>> getVendorLedger(
    String vendorId, {
    String? startDate,
    String? endDate,
  }) async {
    final response = await _dio.get(
      '/v1/finance/ledger/vendor/$vendorId',
      queryParameters: {'startDate': ?startDate, 'endDate': ?endDate},
    );
    return response.data;
  }
}

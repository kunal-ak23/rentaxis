import 'package:dio/dio.dart';

class FinanceService {
  final Dio _dio;
  FinanceService(this._dio);

  Future<List<dynamic>> getAccounts() async {
    final response = await _dio.get('/v1/finance/accounts');
    return response.data;
  }

  Future<List<dynamic>> getTransactions({
    String? propertyId,
    String? accountType,
    String? startDate,
    String? endDate,
  }) async {
    final response = await _dio.get(
      '/v1/finance/transactions',
      queryParameters: {
        if (propertyId != null) 'propertyId': propertyId,
        if (accountType != null) 'accountType': accountType,
        if (startDate != null) 'startDate': startDate,
        if (endDate != null) 'endDate': endDate,
      },
    );
    return response.data;
  }

  Future<Map<String, dynamic>> getOrganisationReport({
    String? startDate,
    String? endDate,
  }) async {
    final response = await _dio.get(
      '/v1/finance/reports/organisation',
      queryParameters: {
        if (startDate != null) 'startDate': startDate,
        if (endDate != null) 'endDate': endDate,
      },
    );
    return response.data;
  }

  /// Portfolio-wide P&L plus one summary row per property. The backend scopes
  /// PROPERTY_MANAGER callers to their assignments and includes zero-activity
  /// properties so this list is a complete portfolio, not just an activity log.
  Future<Map<String, dynamic>> getPortfolioProfitLoss({
    String? startDate,
    String? endDate,
  }) async {
    final response = await _dio.get(
      '/v1/finance/reports/portfolio-profit-loss',
      queryParameters: {
        if (startDate != null) 'startDate': startDate,
        if (endDate != null) 'endDate': endDate,
      },
    );
    return Map<String, dynamic>.from(response.data as Map);
  }

  Future<Map<String, dynamic>> getTrialBalance({
    String? startDate,
    String? endDate,
  }) async {
    final response = await _dio.get(
      '/v1/finance/reports/trial-balance',
      queryParameters: {
        if (startDate != null) 'startDate': startDate,
        if (endDate != null) 'endDate': endDate,
      },
    );
    return response.data;
  }
}

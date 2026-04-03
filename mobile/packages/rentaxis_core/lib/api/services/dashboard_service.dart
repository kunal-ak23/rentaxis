import 'dart:developer' as dev;
import 'package:dio/dio.dart';

class DashboardService {
  final Dio _dio;
  DashboardService(this._dio);

  Future<Map<String, dynamic>> getSummary() async {
    dev.log('[DASHBOARD_DEBUG] getSummary() called', name: 'DashboardService');
    dev.log('[DASHBOARD_DEBUG] baseUrl: ${_dio.options.baseUrl}', name: 'DashboardService');
    try {
      final response = await _dio.get('/v1/dashboard/summary');
      dev.log('[DASHBOARD_DEBUG] getSummary() status=${response.statusCode}', name: 'DashboardService');
      dev.log('[DASHBOARD_DEBUG] getSummary() data keys: ${(response.data as Map?)?.keys.toList()}', name: 'DashboardService');
      return response.data;
    } catch (e, stack) {
      dev.log('[DASHBOARD_DEBUG] getSummary() FAILED: $e', name: 'DashboardService', error: e, stackTrace: stack);
      rethrow;
    }
  }
}

import 'package:dio/dio.dart';

class PaymentService {
  final Dio _dio;
  PaymentService(this._dio);

  // Renter endpoints
  Future<List<dynamic>> getMyPayments() async {
    final response = await _dio.get('/v1/online-payments/my-payments');
    return response.data;
  }

  Future<Map<String, dynamic>> createOrder(String paymentScheduleId) async {
    final response = await _dio.post('/v1/online-payments/create-order', data: {
      'paymentScheduleId': paymentScheduleId,
    });
    return response.data;
  }

  Future<Map<String, dynamic>> verifyPayment(
      Map<String, dynamic> data) async {
    final response =
        await _dio.post('/v1/online-payments/verify', data: data);
    return response.data;
  }

  Future<void> cancelPayment(String paymentScheduleId) async {
    await _dio.post('/v1/online-payments/cancel/$paymentScheduleId');
  }

  // PM endpoints
  Future<List<dynamic>> getPayments(
      {String? propertyId, String? status}) async {
    final response = await _dio.get('/v1/payments', queryParameters: {
      if (propertyId != null) 'propertyId': propertyId,
      if (status != null) 'status': status,
    });
    return response.data;
  }

  Future<Map<String, dynamic>> getSummary({String? propertyId}) async {
    final response =
        await _dio.get('/v1/payments/summary', queryParameters: {
      if (propertyId != null) 'propertyId': propertyId,
    });
    return response.data;
  }

  Future<Map<String, dynamic>> collectPayment(
      String id, Map<String, dynamic> data) async {
    final response = await _dio.put('/v1/payments/$id/collect', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> depositPayment(String id,
      {String? notes}) async {
    final response = await _dio.put('/v1/payments/$id/deposit', data: {
      if (notes != null) 'notes': notes,
    });
    return response.data;
  }

  Future<Map<String, dynamic>> clearPayment(String id,
      {String? notes}) async {
    final response = await _dio.put('/v1/payments/$id/clear', data: {
      if (notes != null) 'notes': notes,
    });
    return response.data;
  }

  Future<Map<String, dynamic>> bouncePayment(String id,
      {String? notes}) async {
    final response = await _dio.put('/v1/payments/$id/bounce', data: {
      if (notes != null) 'notes': notes,
    });
    return response.data;
  }

  Future<Map<String, dynamic>> markPaymentFailed(
    String id, {
    required String failureReason,
    String? notes,
  }) async {
    final response = await _dio.post('/v1/payments/$id/mark-failed', data: {
      'failureReason': failureReason,
      if (notes != null && notes.isNotEmpty) 'notes': notes,
    });
    return Map<String, dynamic>.from(response.data as Map);
  }

  Future<List<int>> downloadReceipt(String id) async {
    final response = await _dio.get('/v1/payments/$id/receipt',
        options: Options(responseType: ResponseType.bytes));
    return response.data;
  }

  Future<Map<String, dynamic>> getAgingReport({String? propertyId}) async {
    final response =
        await _dio.get('/v1/payments/aging-report', queryParameters: {
      if (propertyId != null) 'propertyId': propertyId,
    });
    return response.data;
  }
}

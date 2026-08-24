import 'package:dio/dio.dart';

class PaymentService {
  final Dio _dio;
  PaymentService(this._dio);

  // Renter endpoints
  Future<List<dynamic>> getMyPayments() async {
    final response = await _dio.get('/v1/online-payments/my-payments');
    return response.data;
  }

  // Online-payment initiation (POST /v1/online-payments/create-order, /verify,
  // /cancel/{id}) is deliberately not wired up in any app: the renter surfaces
  // are informational and rent collection is handled offline. The backend
  // (OnlinePaymentController) still supports the flow should a gateway SDK
  // integration ship later — re-add wrappers here at that point.

  // PM endpoints

  /// One page of the tenant-wide schedule list (GET /v1/payments). Returns
  /// the raw Spring Page map ({content, totalElements, totalPages, ...}) so
  /// callers can drive real server-side pagination — one big request
  /// silently truncates portfolios with more schedules than a page holds.
  /// [sort] entries use Spring's "field,direction" form (e.g. 'dueDate,asc');
  /// omitted, the backend default (dueDate DESC) applies. When [overdue] is
  /// true the backend ignores [status] and returns its computed overdue view.
  Future<Map<String, dynamic>> getPaymentsPage({
    String? propertyId,
    String? status,
    bool overdue = false,
    List<String>? sort,
    int page = 0,
    int size = 25,
  }) async {
    final response = await _dio.get(
      '/v1/payments',
      queryParameters: {
        'propertyId': ?propertyId,
        'status': ?status,
        if (overdue) 'overdue': overdue,
        'sort': ?sort,
        'page': page,
        'size': size,
      },
    );
    final data = response.data;
    if (data is Map && data['content'] is List) {
      return Map<String, dynamic>.from(data);
    }
    // Older backends returned a bare list — normalize to a one-page shape.
    final list = data as List<dynamic>;
    return {
      'content': list,
      'totalElements': list.length,
      'totalPages': 1,
      'number': 0,
    };
  }

  /// Full schedule of one lease (GET /v1/payments/lease/{id}, unpaginated).
  /// Lease-scoped views must use this instead of filtering the tenant-wide
  /// paged list client-side, which drops rows beyond the fetched page.
  Future<List<dynamic>> getPaymentsForLease(String leaseId) async {
    final response = await _dio.get('/v1/payments/lease/$leaseId');
    return response.data as List<dynamic>;
  }

  Future<Map<String, dynamic>> getSummary({String? propertyId}) async {
    final response = await _dio.get(
      '/v1/payments/summary',
      queryParameters: {'propertyId': ?propertyId},
    );
    return response.data;
  }

  Future<Map<String, dynamic>> collectPayment(
    String id,
    Map<String, dynamic> data,
  ) async {
    final response = await _dio.put('/v1/payments/$id/collect', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> depositPayment(
    String id, {
    String? notes,
  }) async {
    final response = await _dio.put(
      '/v1/payments/$id/deposit',
      data: {'notes': ?notes},
    );
    return response.data;
  }

  Future<Map<String, dynamic>> clearPayment(String id, {String? notes}) async {
    final response = await _dio.put(
      '/v1/payments/$id/clear',
      data: {'notes': ?notes},
    );
    return response.data;
  }

  Future<Map<String, dynamic>> bouncePayment(String id, {String? notes}) async {
    final response = await _dio.put(
      '/v1/payments/$id/bounce',
      data: {'notes': ?notes},
    );
    return response.data;
  }

  Future<Map<String, dynamic>> markPaymentFailed(
    String id, {
    required String failureReason,
    String? notes,
  }) async {
    final response = await _dio.post(
      '/v1/payments/$id/mark-failed',
      data: {
        'failureReason': failureReason,
        if (notes != null && notes.isNotEmpty) 'notes': notes,
      },
    );
    return Map<String, dynamic>.from(response.data as Map);
  }

  Future<List<int>> downloadReceipt(String id) async {
    final response = await _dio.get(
      '/v1/payments/$id/receipt',
      options: Options(responseType: ResponseType.bytes),
    );
    return response.data;
  }

  Future<Map<String, dynamic>> getAgingReport({String? propertyId}) async {
    final response = await _dio.get(
      '/v1/payments/aging-report',
      queryParameters: {'propertyId': ?propertyId},
    );
    return response.data;
  }
}

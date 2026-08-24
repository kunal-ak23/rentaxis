import 'package:dio/dio.dart';

class LeaseService {
  final Dio _dio;
  LeaseService(this._dio);

  Future<List<dynamic>> getMyLeases() async {
    final response = await _dio.get('/v1/leases/my-leases');
    return response.data;
  }

  Future<List<dynamic>> getAllLeases() async {
    final response = await _dio.get('/v1/leases');
    return response.data;
  }

  Future<Map<String, dynamic>> getLeaseById(String id) async {
    final response = await _dio.get('/v1/leases/$id');
    return response.data;
  }

  Future<Map<String, dynamic>> createLease(Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/leases', data: data);
    return response.data;
  }

  Future<Map<String, dynamic>> updateLease(
    String id,
    Map<String, dynamic> data,
  ) async {
    final response = await _dio.put('/v1/leases/$id', data: data);
    return response.data;
  }

  Future<void> activateLease(String id) async {
    await _dio.put('/v1/leases/$id/activate');
  }

  Future<void> terminateLease(String id, {String? notes}) async {
    // The backend reads a TerminateWithSettlementDTO request body
    // (fields: notes, deductions) and declares no @RequestParam, so notes
    // must travel as JSON — a query parameter would be silently dropped.
    await _dio.post('/v1/leases/$id/terminate', data: {'notes': ?notes});
  }

  Future<void> acceptLease(String id) async {
    await _dio.put('/v1/leases/$id/accept');
  }

  Future<void> rejectLease(String id) async {
    await _dio.put('/v1/leases/$id/reject');
  }

  Future<List<dynamic>> getLeaseDocuments(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/documents');
    return response.data;
  }

  Future<List<int>> downloadDocument(String docId) async {
    final response = await _dio.get(
      '/v1/leases/documents/$docId/download',
      options: Options(responseType: ResponseType.bytes),
    );
    return response.data;
  }

  Future<List<dynamic>> getLeaseEvents(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/events');
    return response.data;
  }

  // Attachments
  Future<List<dynamic>> getAttachments(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/attachments');
    return response.data;
  }

  Future<Map<String, dynamic>> uploadAttachment(
    String leaseId,
    String filePath,
    String name,
  ) async {
    final formData = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath),
      'name': name,
    });
    final response = await _dio.post(
      '/v1/leases/$leaseId/attachments',
      data: formData,
    );
    return response.data;
  }

  Future<void> deleteAttachment(String attachmentId) async {
    await _dio.delete('/v1/leases/attachments/$attachmentId');
  }

  Future<Map<String, dynamic>> extendLease(
    String leaseId,
    String newEndDate,
  ) async {
    final response = await _dio.post(
      '/v1/leases/$leaseId/extend',
      data: {'newEndDate': newEndDate},
    );
    return response.data;
  }

  // Contract generation
  Future<List<int>> previewContract(String leaseId) async {
    final response = await _dio.post(
      '/v1/leases/$leaseId/generate-contract/preview',
      options: Options(responseType: ResponseType.bytes),
    );
    return response.data;
  }

  Future<Map<String, dynamic>> generateContract(String leaseId) async {
    final response = await _dio.post('/v1/leases/$leaseId/generate-contract');
    return response.data;
  }
}

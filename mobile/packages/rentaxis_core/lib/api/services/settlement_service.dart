import 'package:dio/dio.dart';

class SettlementService {
  final Dio _dio;
  SettlementService(this._dio);

  Future<Map<String, dynamic>> getSettlementPreview(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/settlement/preview');
    return response.data;
  }

  Future<Map<String, dynamic>> getSettlement(String leaseId) async {
    final response = await _dio.get('/v1/leases/$leaseId/settlement');
    return response.data;
  }

  Future<Map<String, dynamic>> saveDraft(
      String leaseId, Map<String, dynamic> data) async {
    final response = await _dio.post(
      '/v1/leases/$leaseId/settlement/draft',
      data: data,
    );
    return response.data;
  }

  Future<Map<String, dynamic>> finalizeSettlement(String leaseId) async {
    final response = await _dio.post('/v1/leases/$leaseId/settlement/finalize');
    return response.data;
  }

  Future<Map<String, dynamic>> uploadDeductionAttachment(
      String deductionId, String filePath, String name) async {
    final formData = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath),
      'name': name,
    });
    final response = await _dio.post(
      '/v1/settlements/deductions/$deductionId/attachments',
      data: formData,
    );
    return response.data;
  }

  Future<List<dynamic>> getDeductionAttachments(String deductionId) async {
    final response = await _dio.get(
      '/v1/settlements/deductions/$deductionId/attachments',
    );
    return response.data;
  }

  Future<List<int>> downloadDeductionAttachment(String attachmentId) async {
    final response = await _dio.get(
      '/v1/settlements/attachments/$attachmentId/download',
      options: Options(responseType: ResponseType.bytes),
    );
    return response.data;
  }

  Future<void> deleteDeductionAttachment(String attachmentId) async {
    await _dio.delete('/v1/settlements/attachments/$attachmentId');
  }
}

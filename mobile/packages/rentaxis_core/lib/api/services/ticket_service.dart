import 'package:dio/dio.dart';

class TicketService {
  final Dio _dio;
  TicketService(this._dio);

  Future<List<dynamic>> getTickets() async {
    final response = await _dio.get('/v1/tickets');
    return response.data;
  }

  Future<Map<String, dynamic>> getTicketById(String id) async {
    final response = await _dio.get('/v1/tickets/$id');
    return response.data;
  }

  Future<Map<String, dynamic>> createTicket(
      Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/tickets', data: data);
    return response.data;
  }

  Future<void> assignTicket(String id, String assignTo) async {
    await _dio.put('/v1/tickets/$id/assign', data: {'assignTo': assignTo});
  }

  Future<void> updateStatus(String id, String status) async {
    await _dio.put('/v1/tickets/$id/status', data: {'status': status});
  }

  Future<void> closeTicket(String id, String otp) async {
    await _dio.put('/v1/tickets/$id/close', data: {'otp': otp});
  }

  Future<void> rateTicket(String id, int rating, {String? comment}) async {
    await _dio.put('/v1/tickets/$id/rate', data: {
      'rating': rating,
      if (comment != null) 'comment': comment,
    });
  }

  Future<void> setEstimate(String id, int hours) async {
    await _dio.put('/v1/tickets/$id/estimate', data: {'hours': hours});
  }

  // Replies
  Future<List<dynamic>> getReplies(String ticketId) async {
    final response = await _dio.get('/v1/tickets/$ticketId/replies');
    return response.data;
  }

  Future<Map<String, dynamic>> addReply(
      String ticketId, String message) async {
    final response = await _dio.post('/v1/tickets/$ticketId/replies', data: {
      'message': message,
    });
    return response.data;
  }

  // Attachments
  Future<List<dynamic>> getAttachments(String ticketId) async {
    final response = await _dio.get('/v1/tickets/$ticketId/attachments');
    return response.data;
  }

  Future<Map<String, dynamic>> uploadAttachment(
      String ticketId, String filePath) async {
    final formData = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath),
    });
    final response = await _dio.post('/v1/tickets/$ticketId/attachments',
        data: formData);
    return response.data;
  }

  Future<void> deleteAttachment(String attachmentId) async {
    await _dio.delete('/v1/tickets/attachments/$attachmentId');
  }

  Future<List<int>> downloadAttachment(String attachmentId) async {
    final response = await _dio.get(
        '/v1/tickets/attachments/$attachmentId/download',
        options: Options(responseType: ResponseType.bytes));
    return response.data;
  }

  // History
  Future<List<dynamic>> getHistory(String ticketId) async {
    final response = await _dio.get('/v1/tickets/$ticketId/history');
    return response.data;
  }

  // Reports (PM only)
  Future<Map<String, dynamic>> getReports(
      {String? propertyId, String? startDate, String? endDate}) async {
    final response =
        await _dio.get('/v1/tickets/reports', queryParameters: {
      if (propertyId != null) 'propertyId': propertyId,
      if (startDate != null) 'startDate': startDate,
      if (endDate != null) 'endDate': endDate,
    });
    return response.data;
  }
}

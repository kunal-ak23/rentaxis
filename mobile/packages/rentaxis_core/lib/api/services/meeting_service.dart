import 'package:dio/dio.dart';

class MeetingService {
  final Dio _dio;
  MeetingService(this._dio);

  Future<Map<String, dynamic>> createMeeting(Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/meetings', data: data);
    return response.data;
  }

  /// For admin/PM: list all tenant meetings
  Future<List<dynamic>> listMeetings({int page = 0, int size = 50}) async {
    final response = await _dio
        .get('/v1/meetings', queryParameters: {'page': page, 'size': size});
    final data = response.data;
    if (data is Map && data.containsKey('content')) return data['content'];
    return data as List<dynamic>;
  }

  /// For any user: list their own meetings by perspective (requester|host)
  Future<List<dynamic>> listMyMeetings(
      {String perspective = 'requester',
      int page = 0,
      int size = 50}) async {
    final response = await _dio.get('/v1/meetings/my', queryParameters: {
      'perspective': perspective,
      'page': page,
      'size': size,
    });
    final data = response.data;
    if (data is Map && data.containsKey('content')) return data['content'];
    return data as List<dynamic>;
  }

  Future<Map<String, dynamic>> getMeeting(String id) async {
    final response = await _dio.get('/v1/meetings/$id');
    return response.data;
  }

  Future<Map<String, dynamic>> approveMeeting(String id) async {
    final response = await _dio.put('/v1/meetings/$id/approve');
    return response.data;
  }

  Future<Map<String, dynamic>> cancelMeeting(String id) async {
    final response = await _dio.put('/v1/meetings/$id/cancel');
    return response.data;
  }

  Future<Map<String, dynamic>> completeMeeting(String id) async {
    final response = await _dio.put('/v1/meetings/$id/complete');
    return response.data;
  }

  Future<Map<String, dynamic>> noShowMeeting(String id) async {
    final response = await _dio.put('/v1/meetings/$id/no-show');
    return response.data;
  }

  Future<String?> getDefaultHostId() async {
    final response = await _dio.get('/v1/meetings/default-host');
    return response.data['userId'] as String?;
  }

  Future<List<dynamic>> getAvailableSlots(
      String hostUserId, String date) async {
    final response = await _dio.get('/v1/meetings/slots', queryParameters: {
      'hostUserId': hostUserId,
      'date': date,
    });
    return response.data as List<dynamic>;
  }
}

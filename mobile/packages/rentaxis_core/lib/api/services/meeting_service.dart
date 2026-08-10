import 'package:dio/dio.dart';

class MeetingService {
  final Dio _dio;
  MeetingService(this._dio);

  Future<Map<String, dynamic>> createMeeting(Map<String, dynamic> data) async {
    final response = await _dio.post('/v1/meetings', data: data);
    return response.data;
  }

  /// One page of the tenant-wide meeting list (GET /v1/meetings, PM/admin).
  /// Returns the raw Spring Page map ({content, totalElements, totalPages,
  /// ...}) so callers can drive real server-side pagination — a single big
  /// fetch silently truncates tenants with more meetings than one page holds
  /// (sorted slotStart ASC, so the furthest-out meetings vanish first).
  Future<Map<String, dynamic>> listMeetingsPage({
    int page = 0,
    int size = 25,
  }) async {
    final response = await _dio.get(
      '/v1/meetings',
      queryParameters: {'page': page, 'size': size},
    );
    return _asPageMap(response.data);
  }

  /// One page of the caller's own meetings by perspective (requester|host)
  /// (GET /v1/meetings/my). Same raw Spring Page shape as [listMeetingsPage].
  Future<Map<String, dynamic>> listMyMeetingsPage({
    String perspective = 'requester',
    int page = 0,
    int size = 25,
  }) async {
    final response = await _dio.get(
      '/v1/meetings/my',
      queryParameters: {'perspective': perspective, 'page': page, 'size': size},
    );
    return _asPageMap(response.data);
  }

  /// Older backends returned a bare list — normalize to a one-page shape.
  static Map<String, dynamic> _asPageMap(dynamic data) {
    if (data is Map && data['content'] is List) {
      return Map<String, dynamic>.from(data);
    }
    final list = data as List<dynamic>;
    return {
      'content': list,
      'totalElements': list.length,
      'totalPages': 1,
      'number': 0,
    };
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
    String hostUserId,
    String date,
  ) async {
    final response = await _dio.get(
      '/v1/meetings/slots',
      queryParameters: {'hostUserId': hostUserId, 'date': date},
    );
    return response.data as List<dynamic>;
  }
}

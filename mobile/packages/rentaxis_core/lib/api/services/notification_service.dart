import 'package:dio/dio.dart';

class NotificationApiService {
  final Dio _dio;
  NotificationApiService(this._dio);

  Future<List<dynamic>> getNotifications({int page = 0, int size = 20}) async {
    final response = await _dio.get('/v1/notifications', queryParameters: {
      'page': page,
      'size': size,
    });
    return response.data;
  }

  Future<int> getUnreadCount() async {
    final response = await _dio.get('/v1/notifications/unread-count');
    return response.data['count'];
  }

  Future<void> markAsRead(String id) async {
    await _dio.put('/v1/notifications/$id/read');
  }

  Future<void> markAllAsRead() async {
    await _dio.put('/v1/notifications/read-all');
  }

  Future<void> registerDevice(String token, String platform) async {
    await _dio.post('/v1/notifications/devices/register', data: {
      'token': token,
      'platform': platform,
    });
  }
}

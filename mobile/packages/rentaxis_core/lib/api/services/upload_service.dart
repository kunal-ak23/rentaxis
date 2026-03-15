import 'package:dio/dio.dart';

class UploadService {
  final Dio _dio;
  UploadService(this._dio);

  Future<Map<String, dynamic>> uploadFile(String filePath,
      {String folder = 'assets'}) async {
    final formData = FormData.fromMap({
      'file': await MultipartFile.fromFile(filePath),
      'folder': folder,
    });
    final response = await _dio.post('/v1/assets/upload', data: formData);
    return response.data;
  }
}

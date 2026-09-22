import 'package:dio/dio.dart';

/// Reads the per-tenant feature flags. The endpoint returns a flat map of every
/// flag constant to a boolean, e.g. {"LISTINGS": true, "MOBILE_FINANCE": false}.
///
/// An absent key is NOT an error: the backend only lists the constants it knows
/// about, so a key this build asks for and the server never sends must read as
/// the flag's default (off). Callers get that by asking for `== true`.
class TenantFeatureService {
  TenantFeatureService(this._dio);

  final Dio _dio;

  Future<Map<String, bool>> getFeatures() async {
    final res = await _dio.get('/v1/tenant/features');
    final data = res.data;
    if (data is! Map) return const {};
    return data.map((key, value) => MapEntry('$key', value == true));
  }
}

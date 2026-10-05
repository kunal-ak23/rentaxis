import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../api/api_client.dart';

/// Turns a media URL from the API into one an image widget can load.
///
/// Listing photos and uploaded promotion images sit in private storage, so the
/// API names them by backend routes such as `/api/v1/public/listing-media/{id}`
/// (bug 26/27) — relative to the API host, which the app knows and the image
/// widget does not. An absolute URL (an external link) is returned unchanged.
String? resolveMediaUrl(String? url, {String? apiBase}) {
  if (url == null) return null;
  final trimmed = url.trim();
  if (trimmed.isEmpty) return null;
  if (!trimmed.startsWith('/')) return trimmed;
  // Uri.resolve with an absolute path keeps the scheme and host, drops `/api`.
  return Uri.parse(apiBase ?? ApiClient.defaultBaseUrl).resolve(trimmed).toString();
}

/// True for a route only the signed-in staff of the organisation may load —
/// the listing editor's `/api/listings/{id}/media/{mediaId}/file`.
bool isStaffMediaRoute(String? url) => url != null && url.startsWith('/api/listings/');

/// The same identity headers the API client sends ([AuthInterceptor]), for an
/// image request to a staff-only media route.
Future<Map<String, String>> mediaAuthHeaders([
  FlutterSecureStorage storage = const FlutterSecureStorage(),
]) async {
  final headers = <String, String>{};
  final token = await storage.read(key: 'authToken');
  final userId = await storage.read(key: 'userId');
  final role = await storage.read(key: 'userRole');
  final tenantId = await storage.read(key: 'tenantId');
  final userTenantId = await storage.read(key: 'userTenantId');
  if (token != null) headers['Authorization'] = 'Bearer $token';
  if (userId != null) headers['X-User-Id'] = userId;
  if (role != null) headers['X-User-Role'] = role;
  if (tenantId != null) headers['X-Tenant-Id'] = tenantId;
  if (userTenantId != null) headers['X-User-Tenant-Id'] = userTenantId;
  return headers;
}

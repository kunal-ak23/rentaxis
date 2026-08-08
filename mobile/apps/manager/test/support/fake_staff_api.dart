/// Fakes for the manager's staff screens.
///
/// Unlike the gate-pass harness, the staff screens build their services from
/// `apiClientProvider`'s Dio via private in-file providers, so tests fake at
/// the `HttpClientAdapter` seam instead of subclassing a service.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class FakeStaffApi implements HttpClientAdapter {
  FakeStaffApi({
    List<Map<String, dynamic>>? staffRows,
    List<Map<String, dynamic>>? propertyRows,
    Map<String, Map<String, dynamic>>? staffById,
  }) : staffRows = staffRows ?? [],
       propertyRows = propertyRows ?? [],
       staffById = staffById ?? {};

  /// Raw Staff entity rows, as `GET /v1/staff` returns them.
  final List<Map<String, dynamic>> staffRows;

  /// PropertyStats-shaped rows, as `GET /v1/properties` returns them:
  /// `{property: {id, nameEn, ...}, ...stats}`.
  final List<Map<String, dynamic>> propertyRows;

  /// id → Staff row for `GET /v1/staff/{id}`.
  final Map<String, Map<String, dynamic>> staffById;

  /// Captured `POST /v1/staff` bodies.
  final List<Map<String, dynamic>> createBodies = [];

  /// Captured `PUT /v1/staff/{id}` calls.
  final List<(String, Map<String, dynamic>)> updateCalls = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final path = options.path;
    final method = options.method;

    Object? body;
    if (method == 'GET' && path.endsWith('/v1/staff')) {
      body = staffRows;
    } else if (method == 'GET' && path.endsWith('/v1/properties')) {
      body = propertyRows;
    } else if (method == 'GET' && path.contains('/v1/staff/')) {
      body = staffById[path.split('/').last];
    } else if (method == 'POST' && path.endsWith('/v1/staff')) {
      final data = Map<String, dynamic>.from(options.data as Map);
      createBodies.add(data);
      body = {'id': 'staff-new', ...data};
    } else if (method == 'PUT' && path.contains('/v1/staff/')) {
      final id = path.split('/').last;
      final data = Map<String, dynamic>.from(options.data as Map);
      updateCalls.add((id, data));
      body = {'id': id, ...data};
    }

    if (body == null) {
      return ResponseBody.fromString('not found', 404);
    }
    return ResponseBody.fromString(
      jsonEncode(body),
      200,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

/// An `ApiClient` whose Dio talks to [api]. The real interceptors are cleared:
/// the auth interceptor reads flutter_secure_storage, unavailable in tests.
ApiClient fakeApiClient(FakeStaffApi api) {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = api;
  return client;
}

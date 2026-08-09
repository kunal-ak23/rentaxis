/// Fakes for the manager's settings screens (rent settings, payment gateway).
///
/// Like `fake_staff_api.dart`, these screens build their services from
/// `apiClientProvider`'s Dio via private in-file providers, so tests fake at
/// the `HttpClientAdapter` seam instead of subclassing a service.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class FakeSettingsApi implements HttpClientAdapter {
  FakeSettingsApi({
    this.gatewayConfig,
    this.gateways = const [],
    this.propertyRows = const [],
    this.rentSettingsByPropertyId = const {},
    this.accountMappings = const [],
    this.transactionNatures = const [],
    this.statusOverrides = const {},
  });

  /// `GET /v1/gateway-config` body (TenantGatewayConfigDTO shape).
  /// null answers 204 No Content, as the backend does with no config.
  final Map<String, dynamic>? gatewayConfig;

  /// `GET /v1/gateway-config/gateways` rows (PaymentGatewayDTO shape).
  final List<Map<String, dynamic>> gateways;

  /// `GET /v1/properties` rows.
  final List<Map<String, dynamic>> propertyRows;

  /// propertyId → `GET /v1/rent-settings/{propertyId}` body
  /// (RentCollectionSettingsDTO shape). Missing id answers 204.
  final Map<String, Map<String, dynamic>> rentSettingsByPropertyId;

  /// `GET /v1/finance/account-mappings` rows (AccountMappingDTO shape).
  final List<Map<String, dynamic>> accountMappings;

  /// `GET /v1/finance/account-mappings/natures` rows.
  final List<String> transactionNatures;

  /// Path suffix → forced HTTP status (e.g. `{'/v1/gateway-config': 403}`)
  /// for exercising error handling.
  final Map<String, int> statusOverrides;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    final path = options.path;

    for (final entry in statusOverrides.entries) {
      if (path.endsWith(entry.key)) {
        return ResponseBody.fromString(
          jsonEncode({'error': 'Forbidden'}),
          entry.value,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType],
          },
        );
      }
    }

    if (options.method != 'GET') {
      return ResponseBody.fromString('not found', 404);
    }

    if (path.endsWith('/v1/finance/account-mappings/natures')) {
      return _json(transactionNatures);
    }
    if (path.endsWith('/v1/finance/account-mappings')) {
      return _json(accountMappings);
    }
    if (path.endsWith('/v1/gateway-config/gateways')) {
      return _json(gateways);
    }
    if (path.endsWith('/v1/gateway-config')) {
      if (gatewayConfig == null) return ResponseBody.fromString('', 204);
      return _json(gatewayConfig);
    }
    if (path.endsWith('/v1/properties')) {
      return _json(propertyRows);
    }
    if (path.contains('/v1/rent-settings/')) {
      final settings = rentSettingsByPropertyId[path.split('/').last];
      if (settings == null) return ResponseBody.fromString('', 204);
      return _json(settings);
    }

    return ResponseBody.fromString('not found', 404);
  }

  ResponseBody _json(Object? body) {
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
ApiClient fakeSettingsApiClient(FakeSettingsApi api) {
  final client = ApiClient(baseUrl: 'http://fake/api');
  client.dio.interceptors.clear();
  client.dio.httpClientAdapter = api;
  return client;
}

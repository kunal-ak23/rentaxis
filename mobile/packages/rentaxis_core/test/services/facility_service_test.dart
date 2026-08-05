import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/facility_service.dart';

void main() {
  group('FacilityApiService admin inbox', () {
    test('getBookings sends only the filters that are set', () async {
      final adapter = _StubAdapter(responseBody: {'content': []});
      final service = FacilityApiService(_dio(adapter));

      await service.getBookings(status: 'PENDING');

      final request = adapter.captured.single;
      expect(request.path, '/v1/bookings');
      expect(request.method, 'GET');
      expect(request.queryParameters, {
        'status': 'PENDING',
        'page': 0,
        'size': 20,
      });
    });

    test('approveBooking posts a trimmed adminNote and omits a blank one',
        () async {
      final adapter = _StubAdapter(
        responseBody: {'id': 'bk-1', 'status': 'APPROVED'},
      );
      final service = FacilityApiService(_dio(adapter));

      await service.approveBooking('bk-1', adminNote: '  Enjoy!  ');
      await service.approveBooking('bk-1', adminNote: '   ');
      await service.rejectBooking('bk-1');

      expect(adapter.captured[0].path, '/v1/bookings/bk-1/approve');
      expect(adapter.captured[0].data, {'adminNote': 'Enjoy!'});
      expect(adapter.captured[1].data, isEmpty);
      expect(adapter.captured[2].path, '/v1/bookings/bk-1/reject');
      expect(adapter.captured[2].data, isEmpty);
    });

    test(
        'releaseBooking POSTs to the release endpoint and returns the '
        'decoded map', () async {
      final adapter = _StubAdapter(
        responseBody: {'id': 'bk-2', 'status': 'RELEASED'},
      );
      final service = FacilityApiService(_dio(adapter));

      final result = await service.releaseBooking('bk-2');

      final request = adapter.captured.single;
      expect(request.path, '/v1/bookings/bk-2/release');
      expect(request.method, 'POST');
      expect(result, {'id': 'bk-2', 'status': 'RELEASED'});
    });
  });

  group('FacilityApiService inventory', () {
    test('bulkCreateParkingSpots posts to the bulk endpoint and returns a list',
        () async {
      final adapter = _StubAdapter(responseBody: [
        {'id': 's1', 'spotNumber': 'B1-01'},
        {'id': 's2', 'spotNumber': 'B1-02'},
      ]);
      final service = FacilityApiService(_dio(adapter));

      final created = await service.bulkCreateParkingSpots({
        'propertyId': 'prop-1',
        'spotNumbers': ['B1-01', 'B1-02'],
        'covered': true,
        'buildingIds': <String>[],
      });

      final request = adapter.captured.single;
      expect(request.path, '/v1/parking-spots/bulk');
      expect(request.method, 'POST');
      expect(created, hasLength(2));
    });

    test('deactivateAmenity DELETEs and completes on an empty 204 body',
        () async {
      final adapter = _StubAdapter(statusCode: 204);
      final service = FacilityApiService(_dio(adapter));

      await service.deactivateAmenity('am-1');

      final request = adapter.captured.single;
      expect(request.path, '/v1/amenities/am-1');
      expect(request.method, 'DELETE');
    });

    test('deactivateParkingSpot DELETEs and completes on an empty 204 body',
        () async {
      final adapter = _StubAdapter(statusCode: 204);
      final service = FacilityApiService(_dio(adapter));

      await service.deactivateParkingSpot('spot-1');

      final request = adapter.captured.single;
      expect(request.path, '/v1/parking-spots/spot-1');
      expect(request.method, 'DELETE');
    });
  });

  group('FacilityApiService renter', () {
    test('createBooking posts the body verbatim', () async {
      final adapter = _StubAdapter(
        responseBody: {'id': 'bk-9', 'status': 'PENDING'},
      );
      final service = FacilityApiService(_dio(adapter));

      await service.createBooking({
        'resourceType': 'PARKING_SPOT',
        'resourceId': 'spot-1',
        'unitId': 'unit-1',
        'preferredDate': '2026-08-20',
        'note': 'Second car',
      });

      final request = adapter.captured.single;
      expect(request.path, '/v1/bookings');
      expect(request.data, {
        'resourceType': 'PARKING_SPOT',
        'resourceId': 'spot-1',
        'unitId': 'unit-1',
        'preferredDate': '2026-08-20',
        'note': 'Second car',
      });
    });

    test('myFacilities hits the renter endpoint', () async {
      final adapter = _StubAdapter(
        responseBody: {'amenities': [], 'parkingSpots': []},
      );
      final service = FacilityApiService(_dio(adapter));

      await service.myFacilities();
      expect(adapter.captured.single.path, '/v1/facilities/my');
    });

    test(
        'cancelBooking POSTs to the cancel endpoint and returns the '
        'decoded map', () async {
      final adapter = _StubAdapter(
        responseBody: {'id': 'bk-3', 'status': 'CANCELLED'},
      );
      final service = FacilityApiService(_dio(adapter));

      final result = await service.cancelBooking('bk-3');

      final request = adapter.captured.single;
      expect(request.path, '/v1/bookings/bk-3/cancel');
      expect(request.method, 'POST');
      expect(result, {'id': 'bk-3', 'status': 'CANCELLED'});
    });
  });
}

Dio _dio(_StubAdapter adapter) {
  final dio = Dio(BaseOptions(baseUrl: 'https://api.example/api'));
  dio.httpClientAdapter = adapter;
  return dio;
}

class _StubAdapter implements HttpClientAdapter {
  /// [responseBody] is left null for a 204/empty-body response — Dio's
  /// transformer maps zero bytes to `null` data rather than attempting (and
  /// failing) a JSON decode.
  _StubAdapter({this.responseBody, this.statusCode = 200});

  final Object? responseBody;
  final int statusCode;
  final List<RequestOptions> captured = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    captured.add(options);
    final bytes = responseBody == null
        ? Uint8List(0)
        : utf8.encode(jsonEncode(responseBody));
    return ResponseBody.fromBytes(
      bytes,
      statusCode,
      headers: {
        'content-type': ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

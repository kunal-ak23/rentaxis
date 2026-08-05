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
  });
}

Dio _dio(_StubAdapter adapter) {
  final dio = Dio(BaseOptions(baseUrl: 'https://api.example/api'));
  dio.httpClientAdapter = adapter;
  return dio;
}

class _StubAdapter implements HttpClientAdapter {
  _StubAdapter({required this.responseBody});

  final Object responseBody;
  final List<RequestOptions> captured = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    captured.add(options);
    return ResponseBody.fromBytes(
      utf8.encode(jsonEncode(responseBody)),
      200,
      headers: {
        'content-type': ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

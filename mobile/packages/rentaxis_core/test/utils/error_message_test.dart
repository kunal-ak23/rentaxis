import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/utils/error_message.dart';

/// Builds a `DioException` carrying [data] as the response body, the way a
/// failed `/v1/...` call would arrive at a caller's `catch (e)`. Mirrors the
/// manager app's `facilities_parse_test.dart`/`booking_approvals_screen_test.dart`
/// `_dioError` helpers — this owning package tests its own util directly
/// rather than through one app's usage of it.
DioException _dioError(Object? data, {int statusCode = 400}) {
  final options = RequestOptions(path: '/v1/parking-spots');
  final response = Response<Object?>(
    requestOptions: options,
    statusCode: statusCode,
    data: data,
  );
  return DioException(
    requestOptions: options,
    response: response,
    type: DioExceptionType.badResponse,
  );
}

void main() {
  group('errorMessage', () {
    test('prefers a String message on a Map body', () {
      final e = _dioError({'error': true, 'message': 'Spot already booked'});
      expect(errorMessage(e, 'fallback'), 'Spot already booked');
    });

    test('falls back to a String error when message is absent (409 shape)', () {
      final e = _dioError({
        'error': 'Spot already booked',
        'nextAvailableSlot': null,
      });
      expect(errorMessage(e, 'fallback'), 'Spot already booked');
    });

    test('message wins over error when both are present', () {
      final e = _dioError({'error': 'code', 'message': 'Human readable'});
      expect(errorMessage(e, 'fallback'), 'Human readable');
    });

    test('decodes a body that arrived as a raw JSON string', () {
      final e = _dioError('{"message":"Decoded from string body"}');
      expect(errorMessage(e, 'fallback'), 'Decoded from string body');
    });

    test(
      'a non-JSON String body falls back rather than surfacing raw text',
      () {
        final e = _dioError('<html>502 Bad Gateway</html>');
        expect(errorMessage(e, 'fallback'), 'fallback');
      },
    );

    test('a Map body with neither message nor error falls back', () {
      final e = _dioError({'status': 400});
      expect(errorMessage(e, 'fallback'), 'fallback');
    });

    test('a null body falls back', () {
      final e = _dioError(null);
      expect(errorMessage(e, 'fallback'), 'fallback');
    });

    test('a non-Dio error always falls back', () {
      expect(errorMessage(Exception('boom'), 'fallback'), 'fallback');
      expect(errorMessage('plain string error', 'fallback'), 'fallback');
      expect(errorMessage(StateError('bad state'), 'fallback'), 'fallback');
    });
  });
}

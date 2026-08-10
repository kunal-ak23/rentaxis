import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:renter/screens/create_meeting_screen.dart';
import 'package:renter/screens/profile_screen.dart';

/// Pins the renter app's copies of the same backend error mappings the
/// manager app tests (see the manager's error_mapping_test.dart):
/// - POST /v1/meetings 409 carries `{error, nextAvailableSlot}`.
/// - PUT /auth/me/password 400 carries
///   `{"error": "Current password is incorrect"}`.
void main() {
  group('formatNextAvailableSlot', () {
    test('formats an ISO instant in device-local time', () {
      final s = formatNextAvailableSlot({
        'nextAvailableSlot': '2026-08-12T09:30:00Z',
      }, ar: false);
      final local = DateTime.parse('2026-08-12T09:30:00Z').toLocal();
      expect(s, isNotNull);
      expect(s, contains('${local.day}/${local.month}'));
      expect(s, anyOf(contains('AM'), contains('PM')));
    });

    test('returns null for the "unavailable" sentinel and junk bodies', () {
      expect(
        formatNextAvailableSlot({
          'nextAvailableSlot': 'unavailable',
        }, ar: false),
        isNull,
      );
      expect(formatNextAvailableSlot(null, ar: false), isNull);
      expect(formatNextAvailableSlot('oops', ar: false), isNull);
      expect(formatNextAvailableSlot(<String, dynamic>{}, ar: false), isNull);
    });
  });

  group('isWrongCurrentPasswordError', () {
    DioException dioError(int status, Object? data) => DioException(
      requestOptions: RequestOptions(path: '/auth/me/password'),
      response: Response(
        requestOptions: RequestOptions(path: '/auth/me/password'),
        statusCode: status,
        data: data,
      ),
    );

    test('matches the backend 400 wrong-current-password body', () {
      expect(
        isWrongCurrentPasswordError(
          dioError(400, {'error': 'Current password is incorrect'}),
        ),
        isTrue,
      );
    });

    test('does not match other failures', () {
      expect(
        isWrongCurrentPasswordError(
          dioError(400, {
            'error': 'New password must be at least 6 characters',
          }),
        ),
        isFalse,
      );
      expect(isWrongCurrentPasswordError(dioError(500, null)), isFalse);
      expect(isWrongCurrentPasswordError(Exception('boom')), isFalse);
    });
  });
}

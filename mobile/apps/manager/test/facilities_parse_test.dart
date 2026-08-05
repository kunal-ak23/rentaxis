import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/facilities/facilities_utils.dart';

/// Builds a `DioException` carrying [data] as the response body, the way a
/// failed `/v1/...` call would arrive at a caller's `catch (e)`.
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
  group('parseSpotNumbers', () {
    test('splits and trims comma/semicolon/newline-separated literals', () {
      expect(parseSpotNumbers('A1, A2; A3\nA4'), ['A1', 'A2', 'A3', 'A4']);
    });

    test('drops blanks from repeated or trailing separators', () {
      expect(parseSpotNumbers('A1,, A2 ,\n\n A3'), ['A1', 'A2', 'A3']);
    });

    test('de-duplicates while keeping order of first appearance', () {
      expect(parseSpotNumbers('A1, A2, A1, A3, A2'), ['A1', 'A2', 'A3']);
    });

    test('expands a same-prefix range in ascending order', () {
      expect(parseSpotNumbers('P10-P12'), ['P10', 'P11', 'P12']);
    });

    test('expands a bare numeric range with no prefix', () {
      expect(parseSpotNumbers('10-12'), ['10', '11', '12']);
    });

    test('zero-pads expanded entries to the start bound\'s width', () {
      expect(parseSpotNumbers('P08-P10'), ['P08', 'P09', 'P10']);
    });

    test('keeps differing prefixes around the dash literal', () {
      // "B1-05" alone parses as prefix "B" vs "" on the two sides of the
      // dash (m[1] != m[3]) so it is not a same-prefix range.
      expect(parseSpotNumbers('B1-05, B1-06'), ['B1-05', 'B1-06']);
    });

    test('keeps a range literal when it would expand past the 500 cap', () {
      // end - start = 599, not < kMaxBulkSpotNumbers (500) -> literal.
      expect(parseSpotNumbers('P1-P600'), ['P1-P600']);
    });

    test('expands a range landing exactly on the 500-entry boundary', () {
      final result = parseSpotNumbers('P1-P500');
      expect(result, hasLength(500));
      expect(result.first, 'P1');
      expect(result.last, 'P500');
    });

    test('handles mixed literals, ranges, and duplicates together', () {
      expect(
        parseSpotNumbers('A1, P10-P12; A1\nB2'),
        ['A1', 'P10', 'P11', 'P12', 'B2'],
      );
    });

    test('empty input parses to an empty list', () {
      expect(parseSpotNumbers(''), isEmpty);
      expect(parseSpotNumbers('   \n  '), isEmpty);
    });
  });

  group('errorMessage', () {
    test('prefers a String message on a Map body', () {
      final e = _dioError({'error': true, 'message': 'Spot already booked'});
      expect(errorMessage(e, 'fallback'), 'Spot already booked');
    });

    test('falls back to a String error when message is absent (409 shape)', () {
      final e = _dioError({'error': 'Spot already booked', 'nextAvailableSlot': null});
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

    test('a non-JSON String body falls back rather than surfacing raw text', () {
      final e = _dioError('<html>502 Bad Gateway</html>');
      expect(errorMessage(e, 'fallback'), 'fallback');
    });

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

  group('validateBulkSpotNumbers', () {
    test('empty parsed list is empty', () {
      expect(validateBulkSpotNumbers(const []), BulkSpotValidation.empty);
    });

    test('501 entries is tooMany', () {
      final entries = List.generate(501, (i) => 'S$i');
      expect(validateBulkSpotNumbers(entries), BulkSpotValidation.tooMany);
    });

    test('exactly 500 entries is ok (at the cap, not over it)', () {
      final entries = List.generate(500, (i) => 'S$i');
      expect(validateBulkSpotNumbers(entries), BulkSpotValidation.ok);
    });

    test('an entry longer than 32 characters is tooLong', () {
      final entries = ['A1', 'B' * 33];
      expect(validateBulkSpotNumbers(entries), BulkSpotValidation.tooLong);
    });

    test('an entry exactly 32 characters is ok', () {
      final entries = ['A' * 32];
      expect(validateBulkSpotNumbers(entries), BulkSpotValidation.ok);
    });

    test('ordinary entries are ok', () {
      expect(
        validateBulkSpotNumbers(['A1', 'A2', 'B1']),
        BulkSpotValidation.ok,
      );
    });
  });
}

import 'package:flutter_test/flutter_test.dart';
import 'package:manager/gatepass/guard_admin_service.dart';

import 'support/gatepass_harness.dart';

void main() {
  /// These mirror `OtpLoginService.normalize` + `E164` exactly. They are the
  /// only thing standing between a manager's typing and a guard who can never
  /// log in: `createUser` stores the phone verbatim, and login looks it up
  /// verbatim, so anything these let through unnormalized is a dead account
  /// that reports no error anywhere.
  group('Guard phone', () {
    test('strips the separators a human types, as the login path does', () {
      expect(normalizeGuardPhone('+971 50 123 4567'), '+971501234567');
      expect(normalizeGuardPhone('+971-50-123-4567'), '+971501234567');
      expect(normalizeGuardPhone('  +971501234567  '), '+971501234567');
    });

    test('accepts E.164, before and after normalization', () {
      expect(validateGuardPhone('+971501234567'), isNull);
      expect(validateGuardPhone('+971 50 123 4567'), isNull);
      expect(validateGuardPhone('+12345678'), isNull, reason: '8 digits: the floor');
      expect(validateGuardPhone('+123456789012345'), isNull,
          reason: '15 digits: the ceiling');
    });

    test('rejects what login would reject', () {
      expect(validateGuardPhone(null), isNotNull);
      expect(validateGuardPhone(''), isNotNull);
      expect(validateGuardPhone('0501234567'), isNotNull, reason: 'no +');
      expect(validateGuardPhone('+1234567'), isNotNull, reason: '7 digits');
      expect(validateGuardPhone('+1234567890123456'), isNotNull,
          reason: '16 digits');
      expect(validateGuardPhone('+971abc4567'), isNotNull);
    });
  });

  group('Guard email', () {
    test('is required, because createUser lowercases it unguarded', () {
      expect(validateGuardEmail(null), 'Email is required');
      expect(validateGuardEmail('  '), 'Email is required');
      expect(validateGuardEmail('not-an-email'), isNotNull);
      expect(validateGuardEmail('rakesh@example.com'), isNull);
    });
  });

  group('Unused guard password', () {
    test('is long and not repeated between guards', () {
      final a = generateUnusedGuardPassword();
      final b = generateUnusedGuardPassword();
      expect(a, hasLength(32));
      expect(a, isNot(b));
    });
  });

  group('Create failure wording', () {
    test('names both fields on a collision, since the server names the wrong one',
        () {
      final message = describeGuardCreateFailure(httpError(400,
          message: 'A user with this email already exists in this tenant.'));

      expect(message, contains('already registered'));
      expect(message, contains('only one guard'));
      // The server's own wording is dropped: a phone collision surfaces through
      // this same message, so repeating "email" would misdirect the manager.
      expect(message, isNot(contains('already exists in this tenant')));
    });

    test('explains a 403 in terms of what to do next', () {
      final message = describeGuardCreateFailure(httpError(403, message: 'nope'));
      expect(message, contains('Ask a tenant admin'));
    });

    test('passes through a specific 400 the server does word well', () {
      final message = describeGuardCreateFailure(
          httpError(400, message: 'Phone number is required'));
      expect(message, 'Phone number is required');
    });

    test('falls back rather than leaking an exception toString', () {
      expect(describeGuardCreateFailure(StateError('boom')),
          'Could not create the guard. Check the details and try again.');
      expect(describeGuardCreateFailure(httpError(500)),
          'Could not create the guard. Check the details and try again.');
    });
  });
}

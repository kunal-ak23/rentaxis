import 'package:firebase_auth/firebase_auth.dart';

String describePhoneAuthError(Object error) {
  if (error is! FirebaseAuthException) {
    return 'Could not verify this phone. Please try again.';
  }

  return switch (error.code) {
    'invalid-phone-number' =>
      'Enter a full number with country code, e.g. +971501234567',
    'too-many-requests' =>
      'Too many attempts. Please wait before trying again.',
    'quota-exceeded' =>
      'SMS login is temporarily unavailable. Please contact support.',
    'network-request-failed' =>
      'No connection. Check your network and try again.',
    'invalid-verification-code' => 'Invalid verification code',
    'session-expired' => 'This code has expired. Request a new one.',
    _ => 'Could not verify this phone. Please try again.',
  };
}

import 'package:firebase_auth/firebase_auth.dart';

/// Human-readable phone-auth error copy. [ar] selects Arabic — the auth error
/// banners sit inline with fully Arabic screens, so they must localize too.
String describePhoneAuthError(Object error, {bool ar = false}) {
  final fallback = ar
      ? 'تعذّر التحقق من هذا الرقم. حاول مرة أخرى.'
      : 'Could not verify this phone. Please try again.';
  if (error is! FirebaseAuthException) return fallback;

  return switch (error.code) {
    'invalid-phone-number' =>
      ar
          ? 'أدخل الرقم كاملاً مع رمز الدولة، مثال: 971501234567+'
          : 'Enter a full number with country code, e.g. +971501234567',
    'too-many-requests' =>
      ar
          ? 'محاولات كثيرة. يرجى الانتظار قبل المحاولة مجدداً.'
          : 'Too many attempts. Please wait before trying again.',
    'quota-exceeded' =>
      ar
          ? 'تسجيل الدخول عبر الرسائل غير متاح مؤقتاً. تواصل مع الدعم.'
          : 'SMS login is temporarily unavailable. Please contact support.',
    'network-request-failed' =>
      ar
          ? 'لا يوجد اتصال. تحقق من الشبكة وحاول مجدداً.'
          : 'No connection. Check your network and try again.',
    'invalid-verification-code' =>
      ar ? 'رمز التحقق غير صحيح' : 'Invalid verification code',
    'session-expired' =>
      ar
          ? 'انتهت صلاحية هذا الرمز. اطلب رمزاً جديداً.'
          : 'This code has expired. Request a new one.',
    _ => fallback,
  };
}

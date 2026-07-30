import 'dart:async';

import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

sealed class PhoneVerificationResult {
  const PhoneVerificationResult();
}

final class PhoneVerificationSession extends PhoneVerificationResult {
  const PhoneVerificationSession({
    required this.phone,
    required this.verificationId,
    this.resendToken,
  });

  final String phone;
  final String verificationId;
  final int? resendToken;
}

final class AutomaticallyVerified extends PhoneVerificationResult {
  const AutomaticallyVerified(this.idToken);

  final String idToken;
}

/// Testable boundary around FlutterFire's callback-based phone-auth API.
abstract class PhoneAuthService {
  Future<PhoneVerificationResult> sendCode(
    String phone, {
    int? forceResendingToken,
  });

  Future<String> verifyCode(PhoneVerificationSession session, String smsCode);

  Future<void> signOut();
}

final class FirebasePhoneAuthService implements PhoneAuthService {
  FirebasePhoneAuthService(this._auth);

  final FirebaseAuth _auth;

  @override
  Future<PhoneVerificationResult> sendCode(
    String phone, {
    int? forceResendingToken,
  }) async {
    final result = Completer<PhoneVerificationResult>();

    await _auth.verifyPhoneNumber(
      phoneNumber: phone,
      timeout: const Duration(seconds: 60),
      forceResendingToken: forceResendingToken,
      verificationCompleted: (credential) async {
        try {
          final idToken = await _signInAndReadToken(credential);
          if (!result.isCompleted) {
            result.complete(AutomaticallyVerified(idToken));
          }
        } catch (error, stackTrace) {
          if (!result.isCompleted) {
            result.completeError(error, stackTrace);
          }
        }
      },
      verificationFailed: (error) {
        if (!result.isCompleted) result.completeError(error);
      },
      codeSent: (verificationId, resendToken) {
        if (!result.isCompleted) {
          result.complete(
            PhoneVerificationSession(
              phone: phone,
              verificationId: verificationId,
              resendToken: resendToken,
            ),
          );
        }
      },
      codeAutoRetrievalTimeout: (verificationId) {
        // Android normally calls codeSent first. Keep the flow recoverable if a
        // device reaches the timeout callback without doing so.
        if (!result.isCompleted) {
          result.complete(
            PhoneVerificationSession(
              phone: phone,
              verificationId: verificationId,
              resendToken: forceResendingToken,
            ),
          );
        }
      },
    );

    return result.future;
  }

  @override
  Future<String> verifyCode(PhoneVerificationSession session, String smsCode) {
    final credential = PhoneAuthProvider.credential(
      verificationId: session.verificationId,
      smsCode: smsCode,
    );
    return _signInAndReadToken(credential);
  }

  Future<String> _signInAndReadToken(PhoneAuthCredential credential) async {
    final userCredential = await _auth.signInWithCredential(credential);
    final idToken = await userCredential.user?.getIdToken(true);
    if (idToken == null || idToken.isEmpty) {
      throw StateError('Firebase did not issue an ID token');
    }
    return idToken;
  }

  @override
  Future<void> signOut() => _auth.signOut();
}

final phoneAuthServiceProvider = Provider<PhoneAuthService>(
  (_) => FirebasePhoneAuthService(FirebaseAuth.instance),
);

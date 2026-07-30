import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:security/auth/phone_auth_service.dart';

class FakeAuthService extends AuthService {
  FakeAuthService({this.firebaseLoginError, this.latency}) : super(Dio());

  Object? firebaseLoginError;
  final Duration? latency;
  final List<String> exchangedTokens = [];

  @override
  Future<AuthResponse> loginWithFirebase(String idToken) async {
    exchangedTokens.add(idToken);
    if (latency != null) await Future<void>.delayed(latency!);
    final error = firebaseLoginError;
    if (error != null) throw error;
    return AuthResponse(
      id: 'guard-1',
      email: 'guard@example.com',
      name: 'Test Guard',
      role: 'SECURITY_GUARD',
      tenantId: 'tenant-1',
      tenantIds: const ['tenant-1'],
    );
  }

  @override
  Future<List<dynamic>> getTenants() async => [];
}

class FakePhoneAuthService implements PhoneAuthService {
  FakePhoneAuthService({
    this.sendError,
    this.verifyError,
    this.latency,
    this.automaticallyVerify = false,
  });

  Object? sendError;
  Object? verifyError;
  final Duration? latency;
  bool automaticallyVerify;

  final List<String> requestedPhones = [];
  final List<({String verificationId, String code})> verifiedCodes = [];
  var signOutCalls = 0;
  var _sendCount = 0;

  @override
  Future<PhoneVerificationResult> sendCode(
    String phone, {
    int? forceResendingToken,
  }) async {
    requestedPhones.add(phone);
    if (latency != null) await Future<void>.delayed(latency!);
    final error = sendError;
    if (error != null) throw error;
    if (automaticallyVerify) {
      return const AutomaticallyVerified('auto-firebase-token');
    }
    _sendCount++;
    return PhoneVerificationSession(
      phone: phone,
      verificationId: 'verification-$_sendCount',
      resendToken: _sendCount,
    );
  }

  @override
  Future<String> verifyCode(
    PhoneVerificationSession session,
    String smsCode,
  ) async {
    verifiedCodes.add((verificationId: session.verificationId, code: smsCode));
    if (latency != null) await Future<void>.delayed(latency!);
    final error = verifyError;
    if (error != null) throw error;
    return 'firebase-token-for-${session.phone}';
  }

  @override
  Future<void> signOut() async {
    signOutCalls++;
  }
}

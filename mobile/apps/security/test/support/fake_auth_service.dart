import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Records OTP calls and replays canned outcomes.
///
/// Subclasses the real [AuthService] rather than mocking a Dio adapter: these
/// are widget tests about what the screens send and how they render what comes
/// back, and `AuthService`'s own wire format is already pinned by
/// `rentaxis_core/test/services/gate_pass_service_test.dart`.
class FakeAuthService extends AuthService {
  FakeAuthService({
    this.requestOtpError,
    this.verifyOtpError,
    this.latency,
  }) : super(Dio());

  /// Thrown by [requestOtp] when set — used to drive the 429 / 400 paths.
  Object? requestOtpError;

  /// Thrown by [verifyOtp] when set.
  Object? verifyOtpError;

  /// Holds both calls open for a known duration so a test can observe the
  /// in-flight state. Without it the fake's future resolves in the same pump
  /// that renders the tap, and the loading state is never observable.
  final Duration? latency;

  final List<String> requestedPhones = [];
  final List<({String phone, String code})> verifiedCodes = [];

  @override
  Future<void> requestOtp(String phone) async {
    requestedPhones.add(phone);
    if (latency != null) await Future<void>.delayed(latency!);
    final error = requestOtpError;
    if (error != null) throw error;
  }

  @override
  Future<AuthResponse> verifyOtp(String phone, String code) async {
    verifiedCodes.add((phone: phone, code: code));
    if (latency != null) await Future<void>.delayed(latency!);
    final error = verifyOtpError;
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

/// Builds a [DioException] shaped like a real backend error response.
///
/// [data] is deliberately `Object?`: the two 429 sources do not agree on a body.
/// `OtpLoginService` throws `ResponseStatusException`, which
/// `GlobalExceptionHandler` renders as JSON `{"message": ...}`, while the per-IP
/// bucket in `PublicRateLimitFilter` writes plain text `Rate limit exceeded`.
DioException httpError(int status, {Object? data}) {
  final options = RequestOptions(path: '/auth/otp/request');
  return DioException(
    requestOptions: options,
    response: Response<Object?>(
      requestOptions: options,
      statusCode: status,
      data: data,
    ),
    type: DioExceptionType.badResponse,
  );
}

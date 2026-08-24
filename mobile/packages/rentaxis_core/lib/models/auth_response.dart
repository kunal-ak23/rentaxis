class AuthResponse {
  final String id;
  final String email;
  final String name;
  final String role;
  final String? tenantId;
  final List<String> tenantIds;

  /// Signed JWT issued by the backend (phase 1 of the auth hardening).
  /// Null when talking to an old backend that has not started issuing
  /// tokens yet — the app then keeps authenticating with the legacy
  /// X-User-* headers alone.
  final String? token;

  AuthResponse({
    required this.id,
    required this.email,
    required this.name,
    required this.role,
    this.tenantId,
    this.tenantIds = const [],
    this.token,
  });

  factory AuthResponse.fromJson(Map<String, dynamic> json) {
    return AuthResponse(
      id: json['id'] ?? '',
      email: json['email'] ?? '',
      name: json['name'] ?? '',
      role: json['role'] ?? '',
      tenantId: json['tenantId'],
      tenantIds: json['tenantIds'] != null
          ? List<String>.from(json['tenantIds'])
          : [],
      token: json['token'],
    );
  }
}

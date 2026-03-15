class AuthResponse {
  final String id;
  final String email;
  final String name;
  final String role;
  final String? tenantId;
  final List<String> tenantIds;

  AuthResponse({
    required this.id,
    required this.email,
    required this.name,
    required this.role,
    this.tenantId,
    this.tenantIds = const [],
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
    );
  }
}

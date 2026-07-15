import 'package:flutter/material.dart';

/// Placeholder — Task 10 replaces this with the real OTP-entry screen that
/// calls `ref.read(authProvider.notifier).loginWithOtp(phone, code)`.
class OtpScreen extends StatelessWidget {
  const OtpScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(child: Text('/otp')),
    );
  }
}

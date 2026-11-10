import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Demo-only online payments and rewards on the Wallet tab.
///
/// Compile-time, off by default, so a store build is unchanged:
///
///     flutter run --dart-define=DEMO_PAYMENTS=true
///
/// When on, due instalments get a **Pay online** button that walks a
/// Razorpay-style checkout, and the Wallet tab grows a cashback balance,
/// scratch cards and vouchers. Nothing in that flow calls the backend: the
/// paid rows, balance and rewards live in [walletDemoProvider] for the life
/// of the process (see `wallet_demo.dart`).
const bool kDemoPayments = bool.fromEnvironment('DEMO_PAYMENTS');

/// Read by the screens instead of [kDemoPayments] directly, so a widget test
/// can switch the demo on without a dart-define.
final demoPaymentsEnabledProvider = Provider<bool>((_) => kDemoPayments);

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:google_fonts/google_fonts.dart';
import 'app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Push only: a Firebase that cannot start (a build without its config
  // file) must never stop the app, including native Sign in with Apple,
  // from launching. Push registration checks Firebase.apps before using it.
  if (defaultTargetPlatform == TargetPlatform.android ||
      defaultTargetPlatform == TargetPlatform.iOS) {
    try {
      await Firebase.initializeApp();
    } catch (e) {
      debugPrint('Firebase not started; push notifications are off: $e');
    }
  }
  GoogleFonts.config.allowRuntimeFetching = false;
  runApp(const ProviderScope(child: RenterApp()));
}

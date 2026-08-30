import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:google_fonts/google_fonts.dart';
import 'app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // The production Firebase project currently has only the Resident Android
  // app registered. Do not let a missing iOS GoogleService-Info.plist prevent
  // the iOS app (including native Sign in with Apple) from starting.
  if (defaultTargetPlatform == TargetPlatform.android) {
    await Firebase.initializeApp();
  }
  GoogleFonts.config.allowRuntimeFetching = false;
  runApp(const ProviderScope(child: RenterApp()));
}

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'router.dart';
import 'push_registration.dart';

class RenterApp extends ConsumerWidget {
  const RenterApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    final themeMode = ref.watch(themeModeProvider);
    final language = ref.watch(appLanguageProvider);

    return MaterialApp.router(
      title: 'Miftah Resident',
      theme: MiftahTheme.light,
      darkTheme: MiftahTheme.dark,
      themeMode: themeMode,
      locale: language == AppLanguage.ar
          ? const Locale('ar')
          : const Locale('en'),
      supportedLocales: const [Locale('en'), Locale('ar')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      routerConfig: router,
      builder: (context, child) => PushRegistration(
        child: OfflineStatusBanner(child: child ?? const SizedBox.shrink()),
      ),
      debugShowCheckedModeBanner: false,
    );
  }
}

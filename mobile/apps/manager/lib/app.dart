import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'router.dart';

class ManagerApp extends ConsumerWidget {
  const ManagerApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    final themeMode = ref.watch(themeModeProvider);
    final language = ref.watch(appLanguageProvider);

    return MaterialApp.router(
      title: 'Miftah Manager',
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
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
      builder: (context, child) =>
          OfflineStatusBanner(child: child ?? const SizedBox.shrink()),
      debugShowCheckedModeBanner: false,
    );
  }
}

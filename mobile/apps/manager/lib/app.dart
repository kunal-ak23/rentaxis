import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'router.dart';

class ManagerApp extends ConsumerStatefulWidget {
  const ManagerApp({super.key});

  @override
  ConsumerState<ManagerApp> createState() => _ManagerAppState();
}

class _ManagerAppState extends ConsumerState<ManagerApp> {
  @override
  void initState() {
    super.initState();
    // A 401 on a session the app believed was valid means the token expired or
    // was revoked server-side; there is no refresh call to fall back on.
    // Logging out is enough — the router redirects to /login the moment auth
    // state flips, so this deliberately does no navigation of its own.
    AuthInterceptor.onUnauthorized = () {
      if (!mounted) return;
      ref.read(authProvider.notifier).logout();
    };
  }

  @override
  void dispose() {
    AuthInterceptor.onUnauthorized = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final router = ref.watch(routerProvider);
    final themeMode = ref.watch(themeModeProvider);
    final language = ref.watch(appLanguageProvider);

    return MaterialApp.router(
      title: 'Miftah Manager',
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
      builder: (context, child) =>
          OfflineStatusBanner(child: child ?? const SizedBox.shrink()),
      debugShowCheckedModeBanner: false,
    );
  }
}

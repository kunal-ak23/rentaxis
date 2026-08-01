import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

const _languageKey = 'app_language';

/// Supported app languages. Only branding follows this today; it becomes the
/// real locale switch once the apps ship translated strings.
enum AppLanguage { en, ar }

/// Persisted language preference (EN / عربي).
final appLanguageProvider =
    StateNotifierProvider<AppLanguageNotifier, AppLanguage>((ref) {
  return AppLanguageNotifier();
});

class AppLanguageNotifier extends StateNotifier<AppLanguage> {
  AppLanguageNotifier({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage(),
        super(AppLanguage.en) {
    _load();
  }

  final FlutterSecureStorage _storage;

  Future<void> _load() async {
    try {
      final saved = await _storage.read(key: _languageKey);
      if (saved != null && mounted) {
        state = AppLanguage.values.firstWhere(
          (l) => l.name == saved,
          orElse: () => AppLanguage.en,
        );
      }
    } catch (_) {}
  }

  Future<void> setLanguage(AppLanguage language) async {
    state = language;
    try {
      await _storage.write(key: _languageKey, value: language.name);
    } catch (_) {}
  }
}

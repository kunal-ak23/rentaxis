import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

const _themeModeKey = 'theme_mode';

/// Persisted appearance setting (light / dark / system).
final themeModeProvider =
    StateNotifierProvider<ThemeModeNotifier, ThemeMode>((ref) {
  return ThemeModeNotifier();
});

class ThemeModeNotifier extends StateNotifier<ThemeMode> {
  ThemeModeNotifier({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage(),
        super(ThemeMode.system) {
    _load();
  }

  final FlutterSecureStorage _storage;

  Future<void> _load() async {
    try {
      final saved = await _storage.read(key: _themeModeKey);
      if (saved != null && mounted) {
        state = ThemeMode.values.firstWhere(
          (m) => m.name == saved,
          orElse: () => ThemeMode.system,
        );
      }
    } catch (_) {
      // Unreadable storage falls back to system.
    }
  }

  Future<void> setMode(ThemeMode mode) async {
    state = mode;
    try {
      await _storage.write(key: _themeModeKey, value: mode.name);
    } catch (_) {}
  }
}

import 'package:flutter/widgets.dart';

/// Locale helpers for the lightweight per-screen translation pattern:
/// each screen declares a private `_L` strings class and instantiates it
/// with `context.isAr`. Follows the MaterialApp locale (driven by the
/// persisted [appLanguageProvider]), so RTL and translations stay in sync.
extension MiftahLocaleX on BuildContext {
  bool get isAr =>
      Localizations.maybeLocaleOf(this)?.languageCode == 'ar';
}

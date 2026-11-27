/// Prefers [arabic] when [isAr], otherwise [english], then whichever is
/// present. A blank string counts as absent — the admin form saves empty
/// fields as empty strings often enough that trusting non-null is not enough.
String? pickLang(bool isAr, String? arabic, String? english) {
  String? clean(String? s) {
    if (s == null) return null;
    final t = s.trim();
    return t.isEmpty ? null : t;
  }

  final ar = clean(arabic);
  final en = clean(english);
  return isAr ? (ar ?? en) : (en ?? ar);
}

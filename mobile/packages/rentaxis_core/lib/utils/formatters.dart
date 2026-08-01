import 'package:intl/intl.dart';

class Formatters {
  static final _currencyFormat = NumberFormat.currency(
    locale: 'en_AE',
    symbol: 'AED ',
    decimalDigits: 2,
  );

  static final _compactCurrencyFormat = NumberFormat.compactCurrency(
    locale: 'en_AE',
    symbol: 'AED ',
    decimalDigits: 0,
  );

  /// Wraps a Latin-script fragment in Unicode directional isolates so it keeps
  /// its internal order when embedded in RTL text. No-op visually in LTR.
  static String _isolate(String s) => '\u2066$s\u2069';

  static String currency(num amount) =>
      _isolate(_currencyFormat.format(amount));
  static String currencyCompact(num amount) =>
      _isolate(_compactCurrencyFormat.format(amount));

  /// `ar` renders Arabic month names (Western digits, per the design).
  static String date(String? dateStr, {bool ar = false}) {
    if (dateStr == null) return '-';
    try {
      final date = DateTime.parse(dateStr);
      if (ar) return DateFormat('dd MMMM yyyy', 'ar').format(date);
      return _isolate(DateFormat('dd MMM yyyy').format(date));
    } catch (_) {
      return dateStr;
    }
  }

  static String dateShort(String? dateStr) {
    if (dateStr == null) return '-';
    try {
      final date = DateTime.parse(dateStr);
      return _isolate(DateFormat('dd/MM/yy').format(date));
    } catch (_) {
      return dateStr;
    }
  }

  static String timeAgo(String? dateStr, {bool ar = false}) {
    if (dateStr == null) return '';
    try {
      final date = DateTime.parse(dateStr);
      final now = DateTime.now();
      final diff = now.difference(date);

      if (ar) {
        if (diff.inMinutes < 1) return 'الآن';
        if (diff.inMinutes < 60) return 'قبل ${diff.inMinutes} دقيقة';
        if (diff.inHours < 24) return 'قبل ${diff.inHours} ساعة';
        if (diff.inDays < 7) return 'قبل ${diff.inDays} يوم';
        if (diff.inDays < 30) return 'قبل ${(diff.inDays / 7).floor()} أسبوع';
        return DateFormat('dd MMMM', 'ar').format(date);
      }
      if (diff.inMinutes < 1) return 'Just now';
      if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
      if (diff.inHours < 24) return '${diff.inHours}h ago';
      if (diff.inDays < 7) return '${diff.inDays}d ago';
      if (diff.inDays < 30) return '${(diff.inDays / 7).floor()}w ago';
      return DateFormat('dd MMM').format(date);
    } catch (_) {
      return '';
    }
  }
}

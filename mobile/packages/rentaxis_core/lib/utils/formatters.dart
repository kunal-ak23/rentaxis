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

  static String currency(num amount) => _currencyFormat.format(amount);
  static String currencyCompact(num amount) =>
      _compactCurrencyFormat.format(amount);

  static String date(String? dateStr) {
    if (dateStr == null) return '-';
    try {
      final date = DateTime.parse(dateStr);
      return DateFormat('dd MMM yyyy').format(date);
    } catch (_) {
      return dateStr;
    }
  }

  static String dateShort(String? dateStr) {
    if (dateStr == null) return '-';
    try {
      final date = DateTime.parse(dateStr);
      return DateFormat('dd/MM/yy').format(date);
    } catch (_) {
      return dateStr;
    }
  }

  static String timeAgo(String? dateStr) {
    if (dateStr == null) return '';
    try {
      final date = DateTime.parse(dateStr);
      final now = DateTime.now();
      final diff = now.difference(date);

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

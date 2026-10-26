import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';

/// One fictional cheque to draw.
class ChequeArt {
  ChequeArt({
    required this.number,
    required this.date,
    required this.amount,
    required this.payee,
    required this.payer,
  });
  final String number;
  final DateTime date;
  final num amount;
  final String payee;
  final String payer;
}

const _w = 1600.0;
const _h = 720.0;
const _gap = 80.0;

/// Draws [cheques] stacked on a dark desk (one photo, as an operator would
/// lay them out) and writes the PNG to the app's temp dir.
Future<File> drawChequePhoto(List<ChequeArt> cheques, String name) async {
  final width = _w + 2 * _gap;
  final height = cheques.length * (_h + _gap) + _gap;
  final recorder = ui.PictureRecorder();
  final canvas = ui.Canvas(recorder);
  canvas.drawRect(
    ui.Rect.fromLTWH(0, 0, width, height),
    ui.Paint()..color = const ui.Color(0xFF28282C),
  );
  for (var i = 0; i < cheques.length; i++) {
    canvas.save();
    canvas.translate(_gap, _gap + i * (_h + _gap));
    _drawOne(canvas, cheques[i]);
    canvas.restore();
  }
  final image = await recorder.endRecording().toImage(
    width.round(),
    height.round(),
  );
  final png = await image.toByteData(format: ui.ImageByteFormat.png);
  final dir = await getTemporaryDirectory();
  final file = File('${dir.path}/$name.png');
  await file.writeAsBytes(Uint8List.view(png!.buffer));
  return file;
}

void _text(
  ui.Canvas c,
  String text,
  double x,
  double y, {
  double size = 36,
  bool bold = false,
  ui.Color color = const ui.Color(0xFF0A0A0A),
  double maxWidth = 1400,
}) {
  final b =
      ui.ParagraphBuilder(
          ui.ParagraphStyle(textDirection: ui.TextDirection.ltr),
        )
        ..pushStyle(
          ui.TextStyle(
            color: color,
            fontSize: size,
            fontWeight: bold ? ui.FontWeight.w700 : ui.FontWeight.w400,
          ),
        )
        ..addText(text);
  final p = b.build()..layout(ui.ParagraphConstraints(width: maxWidth));
  c.drawParagraph(p, ui.Offset(x, y));
}

void _line(ui.Canvas c, double x1, double y, double x2) => c.drawLine(
  ui.Offset(x1, y),
  ui.Offset(x2, y),
  ui.Paint()
    ..color = const ui.Color(0xFF787878)
    ..strokeWidth = 2,
);

void _drawOne(ui.Canvas c, ChequeArt q) {
  c.drawRect(
    const ui.Rect.fromLTWH(0, 0, _w, _h),
    ui.Paint()..color = const ui.Color(0xFFF0ECDE),
  );
  c.drawRect(
    const ui.Rect.fromLTWH(20, 20, _w - 40, _h - 40),
    ui.Paint()
      ..color = const ui.Color(0xFF465A6E)
      ..style = ui.PaintingStyle.stroke
      ..strokeWidth = 4,
  );
  final amount = NumberFormat('#,##0.00').format(q.amount);
  // Day and month name, so the date cannot be read month-first.
  final date = DateFormat('dd MMM yyyy').format(q.date).toUpperCase();
  _text(
    c,
    'GULF CRESCENT BANK',
    60,
    48,
    size: 46,
    bold: true,
    color: const ui.Color(0xFF143C64),
  );
  _text(
    c,
    'Fictional bank for demonstration only',
    60,
    106,
    size: 22,
    color: const ui.Color(0xFF5A5A5A),
  );
  _text(c, 'Date', 1080, 60, size: 28, color: const ui.Color(0xFF464646));
  _text(c, date, 1165, 52, size: 40);
  _text(c, 'Pay', 60, 200, size: 30, color: const ui.Color(0xFF464646));
  _text(c, q.payee, 140, 190, size: 44);
  _line(c, 140, 246, 1100);
  _text(c, 'Dirhams', 60, 292, size: 30, color: const ui.Color(0xFF464646));
  _text(
    c,
    '${amountInWords(q.amount)} only',
    190,
    284,
    size: 34,
    maxWidth: 940,
  );
  _line(c, 190, 336, 1100);
  c.drawRect(
    const ui.Rect.fromLTWH(1150, 270, 400, 74),
    ui.Paint()
      ..color = const ui.Color(0xFF3C3C3C)
      ..style = ui.PaintingStyle.stroke
      ..strokeWidth = 3,
  );
  _text(c, 'AED $amount', 1170, 284, size: 40, bold: true);
  _text(c, q.payer, 60, 420, size: 32);
  _text(c, q.payer, 1130, 512, size: 42, color: const ui.Color(0xFF1E1E78));
  _line(c, 1090, 570, 1550);
  _text(c, 'Signature', 1180, 580, size: 22, color: const ui.Color(0xFF5A5A5A));
  _text(c, 'Cheque No. ${q.number}', 60, 625, size: 36, bold: true);
}

const _ones = [
  '',
  'One',
  'Two',
  'Three',
  'Four',
  'Five',
  'Six',
  'Seven',
  'Eight',
  'Nine',
  'Ten',
  'Eleven',
  'Twelve',
  'Thirteen',
  'Fourteen',
  'Fifteen',
  'Sixteen',
  'Seventeen',
  'Eighteen',
  'Nineteen',
];
const _tens = [
  '',
  '',
  'Twenty',
  'Thirty',
  'Forty',
  'Fifty',
  'Sixty',
  'Seventy',
  'Eighty',
  'Ninety',
];

String _under1000(int n) {
  final parts = <String>[];
  if (n >= 100) {
    parts.add('${_ones[n ~/ 100]} hundred');
    n %= 100;
  }
  if (n >= 20) {
    parts.add(_tens[n ~/ 10] + (n % 10 > 0 ? '-${_ones[n % 10]}' : ''));
  } else if (n > 0) {
    parts.add(_ones[n]);
  }
  return parts.join(' and ');
}

/// "Twelve thousand five hundred" — whole dirhams, fils added when present.
String amountInWords(num amount) {
  var n = amount.floor();
  final fils = ((amount - n) * 100).round();
  if (n == 0) return 'Zero';
  final parts = <String>[];
  for (final (size, word) in [(1000000, 'million'), (1000, 'thousand')]) {
    if (n >= size) {
      parts.add('${_under1000(n ~/ size)} $word');
      n %= size;
    }
  }
  if (n > 0) parts.add(_under1000(n));
  var words = parts.join(' ').toLowerCase();
  words = words[0].toUpperCase() + words.substring(1);
  return fils > 0 ? '$words and $fils/100' : words;
}

import 'package:flutter/material.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Bar above the payment list: pick a whole year, see the tick count and
/// download the ticked receipts as one PDF.
class ReceiptPickerBar extends StatelessWidget {
  const ReceiptPickerBar({
    super.key,
    required this.years,
    required this.selected,
    required this.busy,
    required this.onYear,
    required this.onDownload,
  });
  final List<int> years;
  final int selected;
  final bool busy;
  final ValueChanged<int> onYear;
  final ValueChanged<Rect> onDownload;

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    final over = selected > maxCombinedReceipts;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 4,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            PopupMenuButton<int>(
              key: const Key('receipt-year'),
              onSelected: onYear,
              itemBuilder: (_) => [
                for (final y in years)
                  PopupMenuItem(value: y, child: Text('$y')),
              ],
              child: Padding(
                padding: const EdgeInsets.all(8),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(ar ? 'تحديد سنة' : 'Select year'),
                    const Icon(Icons.arrow_drop_down),
                  ],
                ),
              ),
            ),
            Builder(
              builder: (btn) => FilledButton.icon(
                key: const Key('receipt-download'),
                onPressed: selected == 0 || over || busy
                    ? null
                    : () {
                        final box = btn.findRenderObject() as RenderBox;
                        onDownload(box.localToGlobal(Offset.zero) & box.size);
                      },
                icon: busy
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.download_outlined),
                label: Text(
                  ar
                      ? 'تحميل الإيصالات ($selected)'
                      : 'Download receipts ($selected)',
                ),
              ),
            ),
          ],
        ),
        if (over)
          Text(
            ar
                ? 'الحد الأقصى $maxCombinedReceipts إيصال في الملف الواحد. ألغِ تحديد بعضها.'
                : 'At most $maxCombinedReceipts receipts per file. Untick some to continue.',
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
      ],
    );
  }
}

import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:share_plus/share_plus.dart';
import '../providers/renter_data.dart';

final shareRenterDocumentProvider =
    Provider<Future<void> Function(List<int>, String, Rect)>((ref) {
      return (bytes, name, origin) async {
        await SharePlus.instance.share(
          ShareParams(
            files: [
              XFile.fromData(
                Uint8List.fromList(bytes),
                mimeType: 'application/pdf',
                name: name,
              ),
            ],
            fileNameOverrides: [name],
            sharePositionOrigin: origin,
          ),
        );
      };
    });

class RenterDocumentButton extends ConsumerStatefulWidget {
  const RenterDocumentButton({
    super.key,
    required this.path,
    required this.filename,
    required this.label,
    this.onDownloaded,
  });
  final String path;
  final String filename;
  final String label;
  final VoidCallback? onDownloaded;
  @override
  ConsumerState<RenterDocumentButton> createState() =>
      _RenterDocumentButtonState();
}

class _RenterDocumentButtonState extends ConsumerState<RenterDocumentButton> {
  bool _busy = false;
  @override
  Widget build(BuildContext context) => TextButton.icon(
    onPressed: _busy
        ? null
        : () async {
            setState(() => _busy = true);
            try {
              final download = ref.read(renterDocumentProvider);
              final share = ref.read(shareRenterDocumentProvider);
              final box = context.findRenderObject() as RenderBox;
              final origin = box.localToGlobal(Offset.zero) & box.size;
              final bytes = await download(widget.path);
              if (!mounted) return;
              await share(bytes, widget.filename, origin);
              if (mounted) widget.onDownloaded?.call();
            } catch (e) {
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text(
                      errorMessage(
                        e,
                        context.isAr
                            ? 'تعذر فتح المستند. حاول مجدداً.'
                            : 'Could not open the document. Please retry.',
                      ),
                    ),
                  ),
                );
              }
            } finally {
              if (mounted) setState(() => _busy = false);
            }
          },
    icon: _busy
        ? const SizedBox(
            width: 18,
            height: 18,
            child: CircularProgressIndicator(strokeWidth: 2),
          )
        : const Icon(Icons.download_outlined),
    label: Text(widget.label),
  );
}

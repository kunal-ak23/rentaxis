import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:share_plus/share_plus.dart';
import '../providers/renter_data.dart';

/// Ticket files are private. Always use the scoped attachment download route.
class TicketAttachmentTile extends ConsumerStatefulWidget {
  const TicketAttachmentTile({super.key, required this.attachment});
  final Map<String, dynamic> attachment;
  @override
  ConsumerState<TicketAttachmentTile> createState() =>
      _TicketAttachmentTileState();
}

class _TicketAttachmentTileState extends ConsumerState<TicketAttachmentTile> {
  bool _busy = false;
  @override
  Widget build(BuildContext context) {
    final a = widget.attachment;
    final name = (a['downloadName'] ?? 'attachment').toString();
    final type = (a['fileType'] ?? 'application/octet-stream').toString();
    return SizedBox(
      width: 100,
      child: OutlinedButton(
        onPressed: _busy
            ? null
            : () async {
                setState(() => _busy = true);
                try {
                  final box = context.findRenderObject() as RenderBox;
                  final origin = box.localToGlobal(Offset.zero) & box.size;
                  final bytes = await ref.read(renterDocumentProvider)(
                    '/v1/tickets/attachments/${a['id']}/download',
                  );
                  if (!context.mounted) return;
                  if (type.startsWith('image/')) {
                    await showDialog<void>(
                      context: context,
                      builder: (context) => Dialog(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Flexible(
                              child: InteractiveViewer(
                                child: Image.memory(Uint8List.fromList(bytes)),
                              ),
                            ),
                            TextButton(
                              onPressed: () => Navigator.pop(context),
                              child: Text(context.isAr ? 'إغلاق' : 'Close'),
                            ),
                          ],
                        ),
                      ),
                    );
                  } else {
                    await SharePlus.instance.share(
                      ShareParams(
                        files: [
                          XFile.fromData(
                            Uint8List.fromList(bytes),
                            mimeType: type,
                            name: name,
                          ),
                        ],
                        fileNameOverrides: [name],
                        sharePositionOrigin: origin,
                      ),
                    );
                  }
                } catch (e) {
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      SnackBar(
                        content: Text(
                          errorMessage(
                            e,
                            context.isAr
                                ? 'تعذر تحميل المرفق'
                                : 'Could not load attachment',
                          ),
                        ),
                      ),
                    );
                  }
                } finally {
                  if (mounted) setState(() => _busy = false);
                }
              },
        child: _busy
            ? const CircularProgressIndicator()
            : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    type.startsWith('image/')
                        ? Icons.image_outlined
                        : Icons.attach_file,
                  ),
                  Text(
                    name,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 10),
                  ),
                ],
              ),
      ),
    );
  }
}

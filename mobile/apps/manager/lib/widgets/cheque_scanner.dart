import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class ChequeScannerWidget extends StatefulWidget {
  final ChequeExtractionService service;
  final ValueChanged<ChequeExtractionResult> onExtracted;
  final Future<File?> Function()? imagePicker;

  const ChequeScannerWidget({
    super.key,
    required this.service,
    required this.onExtracted,
    this.imagePicker,
  });

  @override
  State<ChequeScannerWidget> createState() => _ChequeScannerWidgetState();
}

class _ChequeScannerWidgetState extends State<ChequeScannerWidget> {
  bool _loading = false;
  String? _error;
  ChequeExtractionResult? _result;

  Future<File?> _pickImage() async {
    if (widget.imagePicker != null) return widget.imagePicker!();
    final picker = ImagePicker();
    final image = await picker.pickImage(
      source: ImageSource.camera,
      imageQuality: 80,
    );
    if (image == null) return null;
    return File(image.path);
  }

  Future<void> _scan() async {
    setState(() {
      _loading = true;
      _error = null;
      _result = null;
    });

    try {
      final file = await _pickImage();
      if (file == null) {
        setState(() => _loading = false);
        return;
      }

      final result = await widget.service.extract(file);
      if (!mounted) return;
      setState(() {
        _loading = false;
        _result = result;
      });

      widget.onExtracted(result);

      if (result.extracted != null &&
          result.extracted?['confidence']?.toString().toUpperCase() == 'LOW') {
        ScaffoldMessenger.of(context).showMaterialBanner(
          const MaterialBanner(
            content: Text('AI confidence is low. Please verify extracted values.'),
            leading: Icon(Icons.warning_amber_rounded),
            actions: [SizedBox.shrink()],
          ),
        );
      }
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = 'Could not read this cheque automatically.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        OutlinedButton.icon(
          onPressed: _loading ? null : _scan,
          icon: _loading
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.camera_alt_outlined, size: 18),
          label: Text(_loading ? 'Reading cheque...' : 'Scan Cheque'),
        ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(
              _error!,
              style: const TextStyle(color: Colors.red, fontSize: 12),
            ),
          ),
        if (_result != null && _result!.extracted == null)
          const Padding(
            padding: EdgeInsets.only(top: 8),
            child: Text(
              'Could not extract values. Photo attached, please fill manually.',
              style: TextStyle(fontSize: 12),
            ),
          ),
      ],
    );
  }
}

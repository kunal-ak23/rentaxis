import 'package:flutter/material.dart';

import '../utils/media_url.dart';

/// An image named by the API: resolves a backend media route against the API
/// host and, for a staff-only route, sends the signed-in user's identity
/// (bug 26/27 — listing photos are no longer bare, private blob URLs).
class ApiImage extends StatefulWidget {
  const ApiImage(
    this.url, {
    super.key,
    this.width,
    this.height,
    this.fit,
    this.errorBuilder,
  });

  final String? url;
  final double? width;
  final double? height;
  final BoxFit? fit;
  final ImageErrorWidgetBuilder? errorBuilder;

  @override
  State<ApiImage> createState() => _ApiImageState();
}

class _ApiImageState extends State<ApiImage> {
  Future<Map<String, String>>? _headers;

  @override
  void initState() {
    super.initState();
    if (isStaffMediaRoute(widget.url)) _headers = mediaAuthHeaders();
  }

  @override
  void didUpdateWidget(covariant ApiImage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url) {
      _headers = isStaffMediaRoute(widget.url) ? mediaAuthHeaders() : null;
    }
  }

  Widget _image(Map<String, String>? headers) {
    final resolved = resolveMediaUrl(widget.url);
    if (resolved == null) {
      return widget.errorBuilder?.call(context, 'no url', null) ??
          SizedBox(width: widget.width, height: widget.height);
    }
    return Image.network(
      resolved,
      headers: headers,
      width: widget.width,
      height: widget.height,
      fit: widget.fit,
      errorBuilder: widget.errorBuilder,
    );
  }

  @override
  Widget build(BuildContext context) {
    final pending = _headers;
    if (pending == null) return _image(null);
    return FutureBuilder<Map<String, String>>(
      future: pending,
      builder: (context, snap) {
        if (!snap.hasData) {
          return SizedBox(width: widget.width, height: widget.height);
        }
        return _image(snap.data);
      },
    );
  }
}

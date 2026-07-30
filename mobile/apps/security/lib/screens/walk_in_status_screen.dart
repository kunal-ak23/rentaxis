import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

class WalkInStatusScreen extends ConsumerStatefulWidget {
  const WalkInStatusScreen({super.key, required this.passId});
  final String passId;

  @override
  ConsumerState<WalkInStatusScreen> createState() => _WalkInStatusScreenState();
}

class _WalkInStatusScreenState extends ConsumerState<WalkInStatusScreen> {
  Map<String, dynamic>? _pass;
  Timer? _timer;
  bool _loading = true;
  bool _admitting = false;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (_pass?['status'] == 'PENDING_APPROVAL') {
        _refresh(silent: true);
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh({bool silent = false}) async {
    try {
      final pass = await ref
          .read(gatePassServiceProvider)
          .walkInStatus(widget.passId);
      if (mounted) {
        setState(() {
          _pass = pass;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted && !silent) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _admit() async {
    if (_admitting) return;
    setState(() => _admitting = true);
    try {
      final pass = await ref
          .read(gatePassServiceProvider)
          .admitWalkIn(widget.passId);
      if (!mounted) return;
      setState(() => _pass = pass);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Entry recorded. The visitor may enter.'),
          backgroundColor: Colors.green,
        ),
      );
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Could not admit the visitor. Refresh and try again.',
            ),
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _admitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final status = _pass?['status']?.toString();
    final pending = status == 'PENDING_APPROVAL';
    final active = status == 'ACTIVE';
    final admitted = status == 'USED';
    return Scaffold(
      appBar: AppBar(
        title: const Text('Visitor entry'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => context.go('/'),
        ),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _pass == null
          ? Center(
              child: FilledButton(
                onPressed: _refresh,
                child: const Text('Try again'),
              ),
            )
          : Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Icon(
                    pending
                        ? Icons.hourglass_top
                        : active
                        ? Icons.verified
                        : admitted
                        ? Icons.login
                        : Icons.cancel,
                    size: 72,
                    color: pending
                        ? Colors.orange
                        : (active || admitted)
                        ? Colors.green
                        : Colors.red,
                  ),
                  const SizedBox(height: 18),
                  Text(
                    _pass!['guestName']?.toString() ?? 'Visitor',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 24,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Unit ${_pass!['unitNumber'] ?? ''}',
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 17,
                      color: AppColors.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 24),
                  Text(
                    pending
                        ? 'Waiting for the resident. This screen updates automatically.'
                        : active
                        ? 'Approved. Tap Admit visitor to record entry.'
                        : admitted
                        ? 'Entry has been recorded.'
                        : 'The request was rejected or expired.',
                    textAlign: TextAlign.center,
                  ),
                  const Spacer(),
                  if (active)
                    FilledButton.icon(
                      onPressed: _admitting ? null : _admit,
                      icon: const Icon(Icons.login),
                      label: Text(_admitting ? 'Recording…' : 'Admit visitor'),
                    ),
                  if (pending)
                    OutlinedButton.icon(
                      onPressed: _refresh,
                      icon: const Icon(Icons.refresh),
                      label: const Text('Check now'),
                    ),
                  if (!pending && !active)
                    FilledButton(
                      onPressed: () => context.go('/'),
                      child: const Text('Done'),
                    ),
                ],
              ),
            ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/gate_pass_provider.dart';

class ResidentApprovalsScreen extends ConsumerWidget {
  const ResidentApprovalsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final requests = ref.watch(residentGateApprovalsProvider);
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(title: const Text('Visitors waiting')),
      body: RefreshIndicator(
        onRefresh: () => ref.refresh(residentGateApprovalsProvider.future),
        child: requests.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (error, stack) => ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            children: const [
              SizedBox(height: 180),
              Center(child: Text('Could not load visitor requests.')),
            ],
          ),
          data: (rows) {
            if (rows.isEmpty) {
              return ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                children: const [
                  SizedBox(height: 180),
                  Icon(
                    Icons.verified_user_outlined,
                    size: 52,
                    color: AppColors.textMuted,
                  ),
                  SizedBox(height: 12),
                  Center(child: Text('No visitors are waiting for approval.')),
                ],
              );
            }
            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.all(16),
              itemCount: rows.length,
              itemBuilder: (context, index) =>
                  _ApprovalCard(request: rows[index]),
            );
          },
        ),
      ),
    );
  }
}

class _ApprovalCard extends ConsumerStatefulWidget {
  const _ApprovalCard({required this.request});
  final Map<String, dynamic> request;

  @override
  ConsumerState<_ApprovalCard> createState() => _ApprovalCardState();
}

class _ApprovalCardState extends ConsumerState<_ApprovalCard> {
  bool _busy = false;

  Future<void> _decide(bool approved) async {
    final id = widget.request['id']?.toString();
    if (id == null || _busy) return;
    setState(() => _busy = true);
    try {
      await ref.read(gatePassServiceProvider).decideAsResident(id, approved);
      ref.invalidate(residentGateApprovalsProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(approved ? 'Visitor approved.' : 'Visitor rejected.'),
          ),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not update this request.')),
        );
        setState(() => _busy = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final request = widget.request;
    final id = request['id']?.toString();
    final name = request['guestName']?.toString() ?? 'Visitor';
    final type =
        request['visitorType']?.toString().replaceAll('_', ' ') ?? 'Visitor';
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _VisitorPhoto(passId: id),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        name,
                        style: const TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        '$type · Unit ${request['unitNumber'] ?? ''}',
                        style: const TextStyle(color: AppColors.textSecondary),
                      ),
                      if (request['purpose'] != null)
                        Text(request['purpose'].toString()),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _busy ? null : () => _decide(false),
                    child: const Text('Reject'),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: FilledButton(
                    onPressed: _busy ? null : () => _decide(true),
                    child: Text(_busy ? 'Saving…' : 'Approve'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _VisitorPhoto extends ConsumerWidget {
  const _VisitorPhoto({required this.passId});
  final String? passId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (passId == null) {
      return const CircleAvatar(radius: 30, child: Icon(Icons.person));
    }
    return FutureBuilder(
      future: ref.read(gatePassServiceProvider).walkInPhoto(passId!),
      builder: (context, snapshot) => CircleAvatar(
        radius: 30,
        backgroundImage: snapshot.hasData ? MemoryImage(snapshot.data!) : null,
        child: snapshot.hasData ? null : const Icon(Icons.person),
      ),
    );
  }
}

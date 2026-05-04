import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Design surface for the cheque scan flow.
///
/// TODO: this screen is currently a UI mock. It does not integrate with a
/// camera plugin, MLKit/OCR, or the payments API. Wire up `mobile_scanner` +
/// a server-side OCR endpoint before exposing to QA / production. Until then
/// the "Confirm & log" action just dismisses the route.
class ScanChequeScreen extends StatelessWidget {
  const ScanChequeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Capture', style: GoogleFonts.inter(fontSize: 11.5, color: AppColors.textMuted)),
                      Text('Scan cheque', style: GoogleFonts.sourceSerif4(fontSize: 22, fontWeight: FontWeight.w600)),
                    ],
                  ),
                  IconButton(onPressed: () => context.pop(), icon: const Icon(Icons.close)),
                ],
              ),
            ),
            const SizedBox(height: 14),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: AspectRatio(
                aspectRatio: 1.7,
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(18),
                  child: Stack(
                    children: [
                      Container(
                        decoration: const BoxDecoration(
                          gradient: LinearGradient(
                            colors: [Color(0xFF1A2B45), Color(0xFF0B1F3A)],
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                          ),
                        ),
                      ),
                      Center(
                        child: Text('Camera preview', style: GoogleFonts.inter(color: Colors.white70)),
                      ),
                      Positioned(
                        left: 10,
                        right: 10,
                        bottom: 10,
                        child: Container(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                          decoration: BoxDecoration(
                            color: Colors.black54,
                            borderRadius: BorderRadius.circular(999),
                          ),
                          child: Row(
                            children: [
                              Container(width: 6, height: 6, decoration: const BoxDecoration(shape: BoxShape.circle, color: AppColors.success)),
                              const SizedBox(width: 6),
                              Text('Detecting cheque · hold steady', style: GoogleFonts.inter(fontSize: 11, color: Colors.white)),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Container(
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  border: Border.all(color: AppColors.border),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Column(
                  children: const [
                    _KvRow(k: 'Bank', v: 'Emirates NBD'),
                    _KvRow(k: 'Cheque #', v: '44182'),
                    _KvRow(k: 'Date', v: '04/05/2026'),
                    _KvRow(k: 'Amount', v: 'AED 23,750.00'),
                    _KvRow(k: 'Payee', v: 'RentAxis Property Mgmt LLC', last: true),
                  ],
                ),
              ),
            ),
            const Spacer(),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton(onPressed: () {}, child: const Text('Retake')),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    flex: 2,
                    child: ElevatedButton(
                      onPressed: () {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Cheque OCR is not wired up yet — coming soon'),
                            backgroundColor: AppColors.primary,
                          ),
                        );
                        context.pop();
                      },
                      child: const Text('Confirm & log'),
                    ),
                  ),
                ],
              ),
            )
          ],
        ),
      ),
    );
  }
}

class _KvRow extends StatelessWidget {
  final String k;
  final String v;
  final bool last;
  const _KvRow({required this.k, required this.v, this.last = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        border: last ? null : const Border(bottom: BorderSide(color: AppColors.border)),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(k, style: GoogleFonts.inter(fontSize: 12, color: AppColors.textMuted)),
          Text(v, style: GoogleFonts.jetBrainsMono(fontSize: 13, color: AppColors.textPrimary)),
        ],
      ),
    );
  }
}

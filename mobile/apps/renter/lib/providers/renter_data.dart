import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'gate_pass_provider.dart';

final renterLeasesProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      ref.watch(authProvider.select((auth) => (auth.userId, auth.tenantId)));
      return (await ref.watch(leaseServiceProvider).getMyLeases())
          .map((r) => Map<String, dynamic>.from(r as Map))
          .toList();
    });

final renterPaymentsProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      ref.watch(authProvider.select((auth) => (auth.userId, auth.tenantId)));
      final rows = await PaymentService(
        ref.watch(apiClientProvider).dio,
      ).getMyPayments();
      return rows.map((r) => Map<String, dynamic>.from(r as Map)).toList();
    });

final renterPenaltiesProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      ref.watch(authProvider.select((auth) => (auth.userId, auth.tenantId)));
      final response = await ref
          .watch(apiClientProvider)
          .dio
          .get('/v1/penalties/mine');
      return (response.data as List)
          .map((r) => Map<String, dynamic>.from(r as Map))
          .toList();
    });

final renterInvoicesProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      ref.watch(authProvider.select((auth) => (auth.userId, auth.tenantId)));
      final response = await ref
          .watch(apiClientProvider)
          .dio
          .get('/v1/tax-invoices/mine');
      return (response.data as List)
          .map((r) => Map<String, dynamic>.from(r as Map))
          .toList();
    });

final renterRenewalsProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
      ref.watch(authProvider.select((auth) => (auth.userId, auth.tenantId)));
      final response = await ref
          .watch(apiClientProvider)
          .dio
          .get('/v1/me/renewals');
      return (response.data['leases'] as List)
          .map((r) => Map<String, dynamic>.from(r as Map))
          .toList();
    });

/// Keep authenticated document downloads on the API client, including receipts.
final renterDocumentProvider = Provider<Future<List<int>> Function(String)>((
  ref,
) {
  final dio = ref.watch(apiClientProvider).dio;
  return (path) async {
    final response = await dio.get<List<int>>(
      path,
      options: Options(responseType: ResponseType.bytes),
    );
    return response.data!;
  };
});

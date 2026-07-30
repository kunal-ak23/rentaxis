import 'package:dio/dio.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Replays the renter's leases.
///
/// Only `getMyLeases` is faked — it is the only one the gate-pass screens call,
/// and stubbing the rest of a 20-method service would be inventing a contract
/// nothing under test uses.
class FakeLeaseService extends LeaseService {
  FakeLeaseService({this.leases = const [], this.error}) : super(Dio());

  List<dynamic> leases;
  Object? error;
  int calls = 0;

  @override
  Future<List<dynamic>> getMyLeases() async {
    calls++;
    final e = error;
    if (e != null) throw e;
    return leases;
  }
}

/// A `LeaseDTO` row as `/v1/leases/my-leases` returns it.
///
/// Carries the three fields the gate-pass screens read — `unitId` (what the pass
/// is raised against), plus `propertyName` and `unitIdentifier` (the names the
/// pass payload lacks, used to address the guest's message).
Map<String, dynamic> leaseFixture({
  String unitId = 'unit-1',
  String status = 'ACTIVE',
  String? propertyName = 'Marina Heights',
  String? unitIdentifier = '1204',
}) {
  return {
    'id': 'lease-$unitId',
    'unitId': unitId,
    'unitIdentifier': unitIdentifier,
    'propertyName': propertyName,
    'status': status,
  };
}

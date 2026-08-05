import 'package:rentaxis_core/rentaxis_core.dart';

/// Canned [FacilityApiService] for widget tests: serves fixed rows and records
/// mutating calls. Defaults are benign empties so a screen can touch any
/// endpoint without blowing up the test.
class FakeFacilityService implements FacilityApiService {
  FakeFacilityService({
    this.bookings = const [],
    Map<String, dynamic>? detail,
    this.amenities = const [],
    this.parkingSpots = const [],
    this.approveError,
    this.rejectError,
    this.releaseError,
    this.createError,
  }) : detail =
           detail ??
           {'request': <String, dynamic>{}, 'otherRequests': <dynamic>[]};

  final List<Map<String, dynamic>> bookings;
  final Map<String, dynamic> detail;
  final List<Map<String, dynamic>> amenities;
  final List<Map<String, dynamic>> parkingSpots;

  /// Thrown by the respective decision call instead of returning normally —
  /// lets a test drive the screen's 409/400/other-failure branches without a
  /// real Dio error arriving over the wire.
  Object? approveError;
  Object? rejectError;
  Object? releaseError;
  Object? createError;

  int bookingsReads = 0;
  final List<(String, String?)> approveCalls = [];
  final List<(String, String?)> rejectCalls = [];
  final List<String> releaseCalls = [];
  final List<Map<String, dynamic>> createCalls = [];

  Map<String, dynamic> _page(List<Map<String, dynamic>> rows) => {
    'content': rows,
    'totalElements': rows.length,
  };

  @override
  Future<Map<String, dynamic>> getAmenities({
    required String propertyId,
    int page = 0,
    int size = 20,
  }) async => _page(amenities);

  @override
  Future<Map<String, dynamic>> createAmenity(Map<String, dynamic> body) async =>
      {'id': 'am-new', ...body};

  @override
  Future<Map<String, dynamic>> updateAmenity(
    String id,
    Map<String, dynamic> body,
  ) async => {'id': id, ...body};

  @override
  Future<void> deactivateAmenity(String id) async {}

  @override
  Future<Map<String, dynamic>> getParkingSpots({
    required String propertyId,
    int page = 0,
    int size = 20,
  }) async => _page(parkingSpots);

  @override
  Future<Map<String, dynamic>> createParkingSpot(
    Map<String, dynamic> body,
  ) async => {'id': 'spot-new', ...body};

  @override
  Future<List<dynamic>> bulkCreateParkingSpots(
    Map<String, dynamic> body,
  ) async => const [];

  @override
  Future<Map<String, dynamic>> updateParkingSpot(
    String id,
    Map<String, dynamic> body,
  ) async => {'id': id, ...body};

  @override
  Future<void> deactivateParkingSpot(String id) async {}

  @override
  Future<Map<String, dynamic>> getBookings({
    String? propertyId,
    String? status,
    String? resourceType,
    int page = 0,
    int size = 20,
  }) async {
    bookingsReads++;
    final rows = status == null
        ? bookings
        : bookings.where((b) => b['status'] == status).toList();
    return _page(rows);
  }

  @override
  Future<Map<String, dynamic>> getBooking(String id) async => detail;

  @override
  Future<Map<String, dynamic>> approveBooking(
    String id, {
    String? adminNote,
  }) async {
    approveCalls.add((id, adminNote));
    final error = approveError;
    if (error != null) throw error;
    return {'id': id, 'status': 'APPROVED'};
  }

  @override
  Future<Map<String, dynamic>> rejectBooking(
    String id, {
    String? adminNote,
  }) async {
    rejectCalls.add((id, adminNote));
    final error = rejectError;
    if (error != null) throw error;
    return {'id': id, 'status': 'REJECTED'};
  }

  @override
  Future<Map<String, dynamic>> releaseBooking(String id) async {
    releaseCalls.add(id);
    final error = releaseError;
    if (error != null) throw error;
    return {'id': id, 'status': 'RELEASED'};
  }

  @override
  Future<Map<String, dynamic>> myFacilities() async => {
    'amenities': amenities,
    'parkingSpots': parkingSpots,
  };

  @override
  Future<Map<String, dynamic>> createBooking(Map<String, dynamic> body) async {
    createCalls.add(body);
    final error = createError;
    if (error != null) throw error;
    return {'id': 'bk-new', 'status': 'PENDING', ...body};
  }

  @override
  Future<List<dynamic>> myBookings() async => bookings;

  @override
  Future<Map<String, dynamic>> cancelBooking(String id) async => {
    'id': id,
    'status': 'CANCELLED',
  };
}

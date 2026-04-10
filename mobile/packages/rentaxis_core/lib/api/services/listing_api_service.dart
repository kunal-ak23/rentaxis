import 'package:dio/dio.dart';

class ListingApiService {
  final Dio _dio;
  ListingApiService(this._dio);

  // ── Renter / Marketplace API ─────────────────────────────────────────────

  Future<Map<String, dynamic>> getMarketplaceListings(
    String tenantSlug, {
    int? minBedrooms,
    double? minRent,
    double? maxRent,
    String? furnishing,
    bool? availableNow,
    double? nearLat,
    double? nearLng,
    double? radiusKm,
    int page = 0,
    int size = 20,
    String sort = 'createdAt,asc',
  }) async {
    final response = await _dio.get(
      '/marketplace/$tenantSlug/listings',
      queryParameters: {
        if (minBedrooms != null) 'minBedrooms': minBedrooms,
        if (minRent != null) 'minRent': minRent,
        if (maxRent != null) 'maxRent': maxRent,
        if (furnishing != null) 'furnishing': furnishing,
        if (availableNow != null) 'availableNow': availableNow,
        if (nearLat != null) 'nearLat': nearLat,
        if (nearLng != null) 'nearLng': nearLng,
        if (radiusKm != null) 'radiusKm': radiusKm,
        'page': page,
        'size': size,
        'sort': sort,
      },
    );
    return response.data as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> getMarketplaceListing(
    String tenantSlug,
    String slug,
  ) async {
    final response =
        await _dio.get('/marketplace/$tenantSlug/listings/$slug');
    return response.data as Map<String, dynamic>;
  }

  Future<void> addInterest(String listingId, {String? note}) async {
    await _dio.post(
      '/marketplace/listings/$listingId/interest',
      data: {'note': note},
    );
  }

  Future<void> removeInterest(String listingId) async {
    await _dio.delete('/marketplace/listings/$listingId/interest');
  }

  Future<List<dynamic>> getWishlist() async {
    final response = await _dio.get('/marketplace/me/wishlist');
    return response.data as List<dynamic>;
  }

  // ── Landlord / Manager API ───────────────────────────────────────────────

  Future<Map<String, dynamic>> getListings({
    int page = 0,
    int size = 20,
    String sort = 'createdAt,asc',
  }) async {
    final response = await _dio.get(
      '/listings',
      queryParameters: {'page': page, 'size': size, 'sort': sort},
    );
    return response.data as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> getListing(String id) async {
    final response = await _dio.get('/listings/$id');
    return response.data as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> createListing(
      Map<String, dynamic> data) async {
    final response = await _dio.post('/listings', data: data);
    return response.data as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> updateListing(
    String id,
    Map<String, dynamic> data,
  ) async {
    final response = await _dio.put('/listings/$id', data: data);
    return response.data as Map<String, dynamic>;
  }

  Future<void> publishListing(String id) async {
    await _dio.post('/listings/$id/publish');
  }

  Future<void> unlistListing(String id) async {
    await _dio.post('/listings/$id/unlist');
  }

  Future<void> deleteListing(String id) async {
    await _dio.delete('/listings/$id');
  }

  Future<Map<String, dynamic>> uploadMedia(
    String listingId,
    List<int> bytes,
    String filename, {
    String? caption,
    bool isCover = false,
  }) async {
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(bytes, filename: filename),
      if (caption != null) 'caption': caption,
      'isCover': isCover.toString(),
    });
    final response =
        await _dio.post('/listings/$listingId/media', data: formData);
    return response.data as Map<String, dynamic>;
  }

  Future<void> deleteMedia(String listingId, String mediaId) async {
    await _dio.delete('/listings/$listingId/media/$mediaId');
  }

  Future<void> reorderMedia(
      String listingId, List<String> mediaIds) async {
    await _dio.put(
      '/listings/$listingId/media/reorder',
      data: {'mediaIds': mediaIds},
    );
  }

  Future<Map<String, dynamic>> getInterests(
    String listingId, {
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dio.get(
      '/listings/$listingId/interests',
      queryParameters: {'page': page, 'size': size},
    );
    return response.data as Map<String, dynamic>;
  }
}

import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Bug 26/27: listing photos and uploaded promotion images are backend routes
/// relative to the API host; the app must load them from that host.
void main() {
  group('resolveMediaUrl', () {
    test('a backend route resolves against the API host, not under /api twice', () {
      expect(
        resolveMediaUrl('/api/v1/public/listing-media/m1',
            apiBase: 'https://rentaxis.example.com/api'),
        'https://rentaxis.example.com/api/v1/public/listing-media/m1',
      );
      expect(
        resolveMediaUrl('/api/v1/public/promo-images/a.png',
            apiBase: 'http://10.0.2.2:8080/api'),
        'http://10.0.2.2:8080/api/v1/public/promo-images/a.png',
      );
    });

    test('an absolute link is unchanged; blank is null', () {
      expect(resolveMediaUrl('https://cdn.example.com/a.jpg'), 'https://cdn.example.com/a.jpg');
      expect(resolveMediaUrl(null), isNull);
      expect(resolveMediaUrl('   '), isNull);
    });

    test('the default base is the build\'s API host', () {
      final resolved = resolveMediaUrl('/api/v1/public/listing-media/m1')!;
      expect(Uri.parse(resolved).host, Uri.parse(ApiClient.defaultBaseUrl).host);
      expect(Uri.parse(resolved).path, '/api/v1/public/listing-media/m1');
    });
  });

  test('only the listing editor route is staff-only', () {
    expect(isStaffMediaRoute('/api/listings/l1/media/m1/file'), isTrue);
    expect(isStaffMediaRoute('/api/v1/public/listing-media/m1'), isFalse);
    expect(isStaffMediaRoute('https://cdn.example.com/a.jpg'), isFalse);
    expect(isStaffMediaRoute(null), isFalse);
  });

  test('a promotion model resolves its uploaded images', () {
    final ad = PromoAd.fromJson({
      'id': 'ad1',
      'business': {'id': 'b1', 'logoUrl': '/api/v1/public/promo-images/l.png'},
      'ctaType': 'NONE',
      'backgroundImageUrl': '/api/v1/public/promo-images/a.png',
    });
    expect(ad.backgroundImageUrl, startsWith('http'));
    expect(ad.backgroundImageUrl, endsWith('/api/v1/public/promo-images/a.png'));
    expect(ad.business.logoUrl, endsWith('/api/v1/public/promo-images/l.png'));
  });
}

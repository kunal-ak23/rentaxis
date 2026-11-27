/// The one hard-coded list of schemes an app link may never use, for the
/// renter app's tests. Written out by hand on purpose: a scheme dropped from
/// `refusedAppSchemes` by mistake must still be refused here. Keep in step
/// with the backend
/// (`backend/src/test/java/com/datagami/rentaxis/core/service/RefusedAppSchemes.java`)
/// and the web fixture
/// (`web/src/app/[locale]/dashboard/promotions/__tests__/refusedAppSchemes.fixture.ts`),
/// entry for entry; `PromotionUrlValidatorTest` checks the three match.
const refusedNamedFixture = [
  'http',
  'https',
  'javascript',
  'vbscript',
  'data',
  'file',
  'blob',
  'about',
  'content',
  'ftp',
  'ws',
  'wss',
  'intent',
  'itms-services',
  'chrome',
  'chrome-extension',
  'mailto',
  'tel',
  'sms',
  'facetime',
  'facetime-audio',
  'googlechrome',
  'googlechromes',
  'firefox',
  'focus',
  'brave',
  'opera',
  'opera-http',
  'microsoft-edge',
  'duckduckgo',
  'edge',
  'telprompt',
  'facetime-prompt',
  'facetime-audio-prompt',
  'smsto',
  'mms',
  'mmsto',
  'com-apple-mobilesafari-tab',
  'samsunginternet',
  'googlechrome-x-callback',
  'firefox-focus',
  'ucbrowser',
  'opera-mini',
  'webcal',
  'callto',
  'sip',
  'sips',
  'wtai',
];

/// Refused by pattern: ends in -http / -https, or starts with x-safari-.
const refusedByPatternFixture = [
  'microsoft-edge-https',
  'microsoft-edge-http',
  'opera-https',
  'x-safari-https',
  'x-safari-http',
  'x-safari-anything',
  'somebrowser-https',
  'somebrowser-http',
];

const refusedAllFixture = [...refusedNamedFixture, ...refusedByPatternFixture];

/// Legitimate app schemes that must keep working.
const allowedAppSchemesFixture = [
  'glidewash',
  'myapp',
  'fb-messenger',
  'careem',
  'talabat',
  'noon',
];

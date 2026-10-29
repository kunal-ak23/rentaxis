/**
 * The one hard-coded list of schemes an app link may never use, for the web tests.
 * Written out by hand on purpose: a scheme dropped from REFUSED_APP_SCHEMES by
 * mistake must still be refused here. Keep in step with the backend
 * (RefusedAppSchemes.java) and the renter app (test/support/refused_app_schemes.dart).
 */
export const REFUSED_NAMED = [
    'http', 'https', 'javascript', 'vbscript', 'data', 'file', 'blob', 'about',
    'content', 'ftp', 'ws', 'wss', 'intent', 'itms-services', 'chrome',
    'chrome-extension', 'mailto', 'tel', 'sms', 'facetime', 'facetime-audio',
    'googlechrome', 'googlechromes', 'firefox', 'focus', 'brave', 'opera', 'opera-http',
    'microsoft-edge', 'duckduckgo', 'edge',
    'telprompt', 'facetime-prompt', 'facetime-audio-prompt', 'smsto', 'mms', 'mmsto',
]

/** Refused by pattern: ends in -http / -https, or starts with x-safari-. */
export const REFUSED_BY_PATTERN = [
    'microsoft-edge-https', 'microsoft-edge-http', 'opera-https', 'x-safari-https',
    'x-safari-http', 'x-safari-anything', 'somebrowser-https', 'somebrowser-http',
]

export const REFUSED_ALL = [...REFUSED_NAMED, ...REFUSED_BY_PATTERN]

/** Legitimate app schemes that must keep working. */
export const ALLOWED_APP_SCHEMES = ['glidewash', 'myapp', 'fb-messenger']

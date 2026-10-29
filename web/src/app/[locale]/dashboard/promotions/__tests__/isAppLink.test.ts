import { describe, expect, it } from 'vitest'
import { REFUSED_APP_SCHEMES, isAppLink } from '../_components/RedirectLinkFields'
import { ALLOWED_APP_SCHEMES, REFUSED_ALL, REFUSED_NAMED } from './refusedAppSchemes.fixture'

describe('isAppLink (mirrors PromotionUrlValidator.isAppLink)', () => {
    it('accepts custom schemes', () => {
        for (const ok of ['glidewash://redeem', 'glidewash://redeem?x=1', 'myapp:open', 'GlideWash://x',
            'GlideWash://Redeem/a/../b?x=%7e', 'glidewash://!~#%7e']) {
            expect(isAppLink(ok), ok).toBe(true)
        }
        for (const s of ALLOWED_APP_SCHEMES) expect(isAppLink(`${s}://x`), s).toBe(true)
    })

    it('refuses every scheme in the shared fixture, in any case', () => {
        for (const s of REFUSED_ALL) {
            for (const v of [s, s.toUpperCase()]) {
                expect(isAppLink(`${v}://x`), v).toBe(false)
                expect(isAppLink(`${v}:x`), v).toBe(false)
            }
        }
        // The fixture covers everything the code names.
        expect([...REFUSED_APP_SCHEMES].filter(s => !REFUSED_NAMED.includes(s))).toEqual([])
    })

    it('refuses browser bridges that would get around the allowlist', () => {
        for (const bridge of ['googlechromes://evil.example/r', 'googlechrome://navigate?url=https://evil.example',
            'x-safari-https://evil.example/r', 'microsoft-edge-https://evil.example/r', 'opera-https://evil.example/r',
            'firefox://open-url?url=https://evil.example', 'brave://open-url?url=https://evil.example']) {
            expect(isAppLink(bridge), bridge).toBe(false)
        }
    })

    it('is printable ASCII only', () => {
        for (const bad of ['glidewash://x\uFE00', 'glidewash://\u3164', 'glidewash://x\u00A0y', 'glidewash://x\u200Ey',
            'glidewash://caf\u00E9', 'glidewash://x\u007F', 'glidewash://re deem', 'glidewash://x\n']) {
            expect(isAppLink(bad), JSON.stringify(bad)).toBe(false)
        }
    })

    it('refuses malformed links', () => {
        for (const bad of ['', 'glidewash:', 'glidewash', '://x', '1app://x', 'a://x', `${'a'.repeat(33)}://x`]) {
            expect(isAppLink(bad), bad).toBe(false)
        }
    })
})

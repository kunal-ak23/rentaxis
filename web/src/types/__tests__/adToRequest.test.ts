import { describe, expect, it } from 'vitest'
import { adToRequest, type PromoAdDTO } from '../promotion'

// adToRequest exists so a full-replace PUT that changes one field keeps the rest.
// Review of addendum C: the per-platform coupon links were left out, so the first
// "toggle one flag" caller would have wiped them.
const ad: PromoAdDTO = {
    id: 'ad-1', businessId: 'b-1', businessNameEn: 'QuickWash',
    titleEn: 'Free wash', titleAr: null, subtitleEn: null, subtitleAr: null,
    backgroundImageUrl: null, accentColor: null, ctaType: 'COUPON', ctaLabelEn: null, ctaLabelAr: null,
    ctaUrl: null, couponCode: 'SPRING20', couponTermsEn: null, couponTermsAr: null,
    redirectUrl: null,
    redirectUrlAndroid: 'https://app.quickwash.example.test/android',
    redirectUrlIos: 'https://app.quickwash.example.test/ios',
    redirectParams: [{ name: 'code', source: 'COUPON_CODE', value: null }],
    startsAt: null, endsAt: null, priority: 3, placement: 'OFFERS_ONLY', active: true, propertyIds: ['p-1'],
    impressions: 0, clicks: 0,
    approvalStatus: 'APPROVED', rejectionReason: null, submittedAt: null, reviewedAt: null, submittedByName: null,
    createdAt: '2026-10-27T00:00:00Z', updatedAt: '2026-10-27T00:00:00Z',
}

describe('adToRequest', () => {
    it('keeps the per-platform coupon links', () => {
        const body = adToRequest(ad)
        expect(body.redirectUrlAndroid).toBe('https://app.quickwash.example.test/android')
        expect(body.redirectUrlIos).toBe('https://app.quickwash.example.test/ios')
        expect(body.redirectParams).toEqual(ad.redirectParams)
    })

    it('sends null for links an older backend did not return', () => {
        const older: Partial<PromoAdDTO> = { ...ad }
        delete older.redirectUrlAndroid
        delete older.redirectUrlIos
        const body = adToRequest(older as PromoAdDTO)
        expect(body.redirectUrlAndroid).toBeNull()
        expect(body.redirectUrlIos).toBeNull()
    })
})

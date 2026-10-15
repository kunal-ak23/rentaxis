import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, fireEvent } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { AdEditor, gulfDate } from '../_components/AdEditor'
import type { PromoAdDTO, PromoBusinessDTO } from '@/types/promotion'

/**
 * Bug 28: an ad saved as Starts 05/10/2026 reopened as 04/10/2026 (the editor
 * read the UTC date of Dubai midnight), and every save moved the start back a
 * day. The editor must show the Gulf dates that were entered and save them back
 * unchanged — in English and Arabic, and whatever the browser's time zone.
 */

const originalTz = process.env.TZ

afterEach(() => {
    cleanup()
    process.env.TZ = originalTz
})

const business: PromoBusinessDTO = {
    id: 'b-1', nameEn: 'Spice Bazaar', nameAr: null, logoUrl: null, category: 'DINING',
    phoneE164: '+971501234567', whatsappE164: null, allowedDomains: ['spice-bazaar.ae'],
    active: true, adCount: 1, createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z',
}

// What the backend stores for "05/10/2026 – 04/11/2026" entered in the editor.
const ad: PromoAdDTO = {
    id: 'ad-1', businessId: 'b-1', businessNameEn: 'Spice Bazaar',
    titleEn: 'Friday brunch', titleAr: 'برانش الجمعة', subtitleEn: null, subtitleAr: null,
    backgroundImageUrl: null, accentColor: null, ctaType: 'NONE', ctaLabelEn: null, ctaLabelAr: null,
    ctaUrl: null, couponCode: null, couponTermsEn: null, couponTermsAr: null,
    startsAt: '2026-10-04T20:00:00Z', endsAt: '2026-11-04T19:59:59Z',
    priority: 1, placement: 'HOME_AND_OFFERS', active: true, propertyIds: [],
    impressions: 0, clicks: 0, createdAt: '2026-10-05T08:00:00Z', updatedAt: '2026-10-05T08:00:00Z',
}

function renderEditor(locale: 'en' | 'ar', onSave = vi.fn()) {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <AdEditor businesses={[business]} properties={[]} ad={ad} onSave={onSave} onCancel={vi.fn()} />
        </NextIntlClientProvider>,
    )
    return onSave
}

const ZONES = ['UTC', 'Asia/Dubai', 'America/Los_Angeles', 'Asia/Kolkata', 'Pacific/Kiritimati']

describe('AdEditor dates (bug 28)', () => {
    it('reads a stored instant as its Gulf date', () => {
        for (const tz of ZONES) {
            process.env.TZ = tz
            expect(gulfDate('2026-10-04T20:00:00Z'), tz).toBe('2026-10-05')
            expect(gulfDate('2026-11-04T19:59:59Z'), tz).toBe('2026-11-04')
            expect(gulfDate('2026-10-05T19:59:59Z'), tz).toBe('2026-10-05')
            expect(gulfDate(null), tz).toBe('')
            expect(gulfDate('not a date'), tz).toBe('')
        }
    })

    for (const locale of ['en', 'ar'] as const) {
        for (const tz of ZONES) {
            it(`shows the entered dates and saves them back unchanged (${locale}, ${tz})`, () => {
                process.env.TZ = tz
                const onSave = renderEditor(locale)
                const t = (locale === 'en' ? en : ar).Promotions
                const starts = screen.getByLabelText(t.startsAt) as HTMLInputElement
                const ends = screen.getByLabelText(t.endsAt) as HTMLInputElement
                expect(starts.value).toBe('2026-10-05')
                expect(ends.value).toBe('2026-11-04')

                // Pause it: the only change. The window must come back exactly as stored.
                fireEvent.click(screen.getByLabelText(t.active))
                fireEvent.click(screen.getByRole('button', { name: t.save }))
                expect(onSave).toHaveBeenCalledTimes(1)
                const body = onSave.mock.calls[0][0]
                expect(body.startsAt).toBe('2026-10-04T20:00:00.000Z')
                expect(body.endsAt).toBe('2026-11-04T19:59:59.000Z')
                expect(body.active).toBe(false)
            })
        }
    }
})

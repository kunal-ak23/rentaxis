import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { AdsTab } from '../_components/AdsTab'
import { adDisplayStatus, isPromoterAd, promoterRemoved } from '@/types/promotion'
import { fetchRouter } from '@/test/fetchRouter'
import type { PromoAdDTO, PromoBusinessDTO } from '@/types/promotion'

/**
 * An ad a promoter wrote stays a promoter ad after the promoter is removed, when
 * `submittedByName` goes null. The feed never shows it, so the admin list must not
 * say Live, Scheduled or Pending, and must not offer Approve.
 */

const business: PromoBusinessDTO = {
    id: 'b-1', nameEn: 'Sparkle Laundry', nameAr: null, logoUrl: null, category: 'SERVICES',
    phoneE164: null, whatsappE164: null, allowedDomains: [], active: true, adCount: 3,
    createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
}

function ad(id: string, over: Partial<PromoAdDTO> = {}): PromoAdDTO {
    return {
        id, businessId: 'b-1', businessNameEn: 'Sparkle Laundry',
        titleEn: `Ad ${id}`, titleAr: null, subtitleEn: null, subtitleAr: null,
        backgroundImageUrl: null, accentColor: null, ctaType: 'NONE', ctaLabelEn: null, ctaLabelAr: null,
        ctaUrl: null, couponCode: null, couponTermsEn: null, couponTermsAr: null,
        startsAt: null, endsAt: null, priority: 1, placement: 'HOME_AND_OFFERS', active: true, propertyIds: ['p-1'],
        impressions: 0, clicks: 0,
        approvalStatus: 'APPROVED', rejectionReason: null, submittedAt: null, reviewedAt: null, submittedByName: null,
        createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z', ...over,
    }
}
const page = (...content: PromoAdDTO[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 10 })

const REMOVED = { writtenByPromoter: true, submittedByName: null }
const GONE_PENDING = ad('gone-pending', { ...REMOVED, approvalStatus: 'PENDING', submittedAt: '2026-10-05T00:00:00Z' })
const GONE_LIVE = ad('gone-live', REMOVED)
const GONE_SCHEDULED = ad('gone-scheduled', { ...REMOVED, startsAt: '2099-01-01T00:00:00Z' })
const GONE_REJECTED = ad('gone-rejected', { ...REMOVED, approvalStatus: 'REJECTED', rejectionReason: 'Image is blurry' })
const PRESENT_PENDING = ad('present-pending', { writtenByPromoter: true, submittedByName: 'Pat Promoter', approvalStatus: 'PENDING', submittedAt: '2026-10-05T00:00:00Z' })
const ADMIN_LIVE = ad('admin-live', { writtenByPromoter: false, submittedByName: null })
const OLD_BACKEND_LIVE = ad('old-live') // no writtenByPromoter at all

let api: ReturnType<typeof fetchRouter>

function renderTab(locale: 'en' | 'ar' = 'en') {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <AdsTab businesses={[business]} properties={[{ id: 'p-1', nameEn: 'Palm Tower' }]} />
        </NextIntlClientProvider>,
    )
}
const rowOf = (title: string) => screen.getByText(title).closest('tr') as HTMLElement
const pill = (title: string) => within(rowOf(title)).getByTestId('ad-status')

beforeEach(() => {
    api = fetchRouter()
    api.on('GET', '/v1/promotions/ads?', c => ({
        body: page(...(c.url.includes('archived=true') ? [GONE_LIVE] : [
            GONE_PENDING, GONE_LIVE, GONE_SCHEDULED, GONE_REJECTED, PRESENT_PENDING, ADMIN_LIVE, OLD_BACKEND_LIVE,
        ])),
    }))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

describe('admin Ads tab — an ad whose promoter was removed', () => {
    it('says it is not shown, instead of Pending, Live or Scheduled', async () => {
        renderTab()
        await screen.findByText('Ad gone-pending')
        for (const title of ['Ad gone-pending', 'Ad gone-live', 'Ad gone-scheduled']) {
            expect(pill(title), title).toHaveTextContent('Not shown — promoter removed')
            expect(pill(title), title).toHaveAttribute('data-status', 'PROMOTER_REMOVED')
        }
    })

    it('keeps a rejection a rejection, with its reason', async () => {
        renderTab()
        await screen.findByText('Ad gone-rejected')
        expect(pill('Ad gone-rejected')).toHaveTextContent('Rejected')
        expect(within(rowOf('Ad gone-rejected')).getByText('Reason: Image is blurry')).toBeInTheDocument()
    })

    it('offers no Approve or Reject on its pending ad, and keeps Edit and Delete', async () => {
        renderTab()
        await screen.findByText('Ad gone-pending')
        const row = within(rowOf('Ad gone-pending'))
        expect(row.queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument()
        expect(row.queryByRole('button', { name: /^Reject/ })).not.toBeInTheDocument()
        expect(row.getByRole('button', { name: 'Edit' })).toBeInTheDocument()
        expect(row.getByRole('button', { name: 'Delete' })).toBeInTheDocument()
    })

    it('can still be deleted', async () => {
        api.on('DELETE', '/promotions/ads/gone-pending', { body: { outcome: 'DELETED' } })
        renderTab()
        await screen.findByText('Ad gone-pending')
        fireEvent.click(within(rowOf('Ad gone-pending')).getByRole('button', { name: 'Delete' }))
        await waitFor(() => expect(api.callsTo('DELETE', '/promotions/ads/gone-pending')).toHaveLength(1))
    })

    it('opens in the editor, which asks for a property like any promoter ad', async () => {
        renderTab()
        await screen.findByText('Ad gone-pending')
        fireEvent.click(within(rowOf('Ad gone-pending')).getByRole('button', { name: 'Edit' }))
        expect(await screen.findByText("Promoter ads must target at least one of the promoter's properties.")).toBeInTheDocument()
    })

    it('does not touch the other ads: a present promoter, an admin, an older backend', async () => {
        renderTab()
        await screen.findByText('Ad present-pending')
        expect(pill('Ad present-pending')).toHaveTextContent('Pending approval')
        expect(within(rowOf('Ad present-pending')).getByRole('button', { name: /^Approve/ })).toBeInTheDocument()
        expect(pill('Ad admin-live')).toHaveTextContent('Live')
        expect(pill('Ad old-live')).toHaveTextContent('Live')
    })

    it('says so even before the business lookup arrives, when an approved ad would otherwise show a dash', async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <AdsTab businesses={[]} properties={[]} />
            </NextIntlClientProvider>,
        )
        await screen.findByText('Ad gone-live')
        expect(pill('Ad gone-live')).toHaveTextContent('Not shown — promoter removed')
        // An ordinary approved ad cannot say Live yet (it needs its business), and shows nothing rather than a guess.
        expect(within(rowOf('Ad admin-live')).queryByTestId('ad-status')).not.toBeInTheDocument()
    })

    it('says Archived in the archived list, whatever else is true of the ad', async () => {
        renderTab()
        await screen.findByText('Ad gone-pending')
        fireEvent.click(screen.getByRole('checkbox', { name: en.Promotions.showArchived }))
        const row = await screen.findByText('Ad gone-live').then(el => el.closest('tr') as HTMLElement)
        expect(within(row).getByTestId('ad-status')).toHaveTextContent('Archived')
    })

    it('reads in Arabic', async () => {
        renderTab('ar')
        await screen.findByText('Ad gone-live')
        expect(pill('Ad gone-live')).toHaveTextContent('غير معروض — تمت إزالة المروّج')
    })
})

describe('promoterRemoved / isPromoterAd / adDisplayStatus', () => {
    const base = { writtenByPromoter: undefined as boolean | undefined, submittedByName: null as string | null }

    it('promoterRemoved needs a promoter ad with no name left', () => {
        expect(promoterRemoved({ ...base, writtenByPromoter: true })).toBe(true)
        expect(promoterRemoved({ writtenByPromoter: true, submittedByName: 'Pat' })).toBe(false)
        expect(promoterRemoved({ writtenByPromoter: false, submittedByName: null })).toBe(false)
        // An older backend sends no flag: a missing name alone means nothing.
        expect(promoterRemoved(base)).toBe(false)
    })

    it('isPromoterAd follows the flag, and falls back to the name only without it', () => {
        expect(isPromoterAd({ writtenByPromoter: true, submittedByName: null })).toBe(true)
        expect(isPromoterAd({ writtenByPromoter: false, submittedByName: 'Pat' })).toBe(false)
        expect(isPromoterAd({ submittedByName: 'Pat' })).toBe(true)
        expect(isPromoterAd({ submittedByName: null })).toBe(false)
    })

    it('adDisplayStatus puts the removed promoter before approval and the window, but not before a rejection', () => {
        const now = new Date('2026-10-27T00:00:00Z')
        expect(adDisplayStatus(GONE_PENDING, now)).toBe('PROMOTER_REMOVED')
        expect(adDisplayStatus(GONE_LIVE, now)).toBe('PROMOTER_REMOVED')
        expect(adDisplayStatus(ad('x', { ...REMOVED, active: false }), now)).toBe('PROMOTER_REMOVED')
        expect(adDisplayStatus(GONE_REJECTED, now)).toBe('REJECTED')
        expect(adDisplayStatus(PRESENT_PENDING, now)).toBe('PENDING')
        expect(adDisplayStatus(ADMIN_LIVE, now)).toBe('LIVE')
    })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { AdsTab } from '../_components/AdsTab'
import { fetchRouter } from '@/test/fetchRouter'
import type { PromoAdDTO, PromoBusinessDTO } from '@/types/promotion'

/** Addendum B: a delete always succeeds (hard delete or archive), and archived ads are listed and restored by an admin. */

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
        impressions: 120, clicks: 7,
        approvalStatus: 'APPROVED', rejectionReason: null, submittedAt: null, reviewedAt: null, submittedByName: null,
        createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z', ...over,
    }
}

const page = (...content: PromoAdDTO[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 10 })

const LIVE = ad('live')
const PENDING = ad('pending', { approvalStatus: 'PENDING', submittedByName: 'Pat Promoter' })
const OLD = ad('old', { active: false, approvalStatus: 'PENDING' })

let api: ReturnType<typeof fetchRouter>
let confirm: ReturnType<typeof vi.spyOn>

function renderTab(locale: 'en' | 'ar' = 'en') {
    const onReviewed = vi.fn()
    render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <AdsTab businesses={[business]} properties={[{ id: 'p-1', nameEn: 'Palm Tower' }]} onReviewed={onReviewed} />
        </NextIntlClientProvider>,
    )
    return onReviewed
}

const rowOf = (title: string) => screen.getByText(title).closest('tr') as HTMLElement
const L = en.Promotions
const archivedToggle = () => screen.getByRole('checkbox', { name: L.showArchived })
/** Live list by default; the archived list for `?archived=true`. */
function listBy(live: PromoAdDTO[], archived: PromoAdDTO[]) {
    api.on('GET', '/v1/promotions/ads?', c => ({ body: page(...(c.url.includes('archived=true') ? archived : live)) }))
}

beforeEach(() => {
    api = fetchRouter()
    confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

describe('admin Ads tab — delete', () => {
    it('asks with the sentence that says an ad people have seen is archived instead', async () => {
        listBy([LIVE], [])
        api.on('DELETE', '/promotions/ads/live', { body: { outcome: 'DELETED' } })
        renderTab()
        await screen.findByText('Ad live')
        fireEvent.click(within(rowOf('Ad live')).getByRole('button', { name: 'Delete' }))
        await waitFor(() => expect(api.callsTo('DELETE', '/promotions/ads/live')).toHaveLength(1))
        expect(confirm).toHaveBeenCalledWith(
            'Delete this ad? If tenants have already seen it, it is archived instead so its view history is kept.')
    })

    it.each([
        ['DELETED', 'Deleted'],
        ['ARCHIVED', 'Archived'],
    ])('toasts "%s" from the outcome, reloads the list and refreshes the pending count', async (outcome, text) => {
        listBy([LIVE, PENDING], [])
        api.on('DELETE', '/promotions/ads/pending', { body: { outcome } })
        const onReviewed = renderTab()
        await screen.findByText('Ad pending')
        const loads = api.callsTo('GET', '/v1/promotions/ads?').length

        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: 'Delete' }))

        expect(await screen.findByTestId('toast')).toHaveTextContent(text)
        expect(screen.getByTestId('toast')).toHaveAttribute('data-tone', 'success')
        await waitFor(() => expect(onReviewed).toHaveBeenCalledTimes(1))
        expect(api.callsTo('GET', '/v1/promotions/ads?').length).toBeGreaterThan(loads)
    })

    it('treats a delete that answers with no body as a hard delete', async () => {
        listBy([LIVE], [])
        api.on('DELETE', '/promotions/ads/live', { status: 200 })
        renderTab()
        await screen.findByText('Ad live')
        fireEvent.click(within(rowOf('Ad live')).getByRole('button', { name: 'Delete' }))
        expect(await screen.findByTestId('toast')).toHaveTextContent('Deleted')
    })

    it('sends nothing when the admin cancels', async () => {
        confirm.mockReturnValue(false)
        listBy([LIVE], [])
        renderTab()
        await screen.findByText('Ad live')
        fireEvent.click(within(rowOf('Ad live')).getByRole('button', { name: 'Delete' }))
        expect(api.callsTo('DELETE', '/promotions/ads')).toHaveLength(0)
        expect(screen.queryByTestId('toast')).not.toBeInTheDocument()
    })

    it('shows the server\'s message when a delete fails, with no toast', async () => {
        listBy([LIVE], [])
        api.on('DELETE', '/promotions/ads/live', { status: 404, body: { message: 'Ad not found' } })
        renderTab()
        await screen.findByText('Ad live')
        fireEvent.click(within(rowOf('Ad live')).getByRole('button', { name: 'Delete' }))
        expect(await screen.findByRole('alert')).toHaveTextContent('Ad not found')
        expect(screen.queryByTestId('toast')).not.toBeInTheDocument()
    })
})

describe('admin Ads tab — archived', () => {
    it('lists archived ads through ?archived=true, and back to the live list when switched off', async () => {
        listBy([LIVE], [OLD])
        renderTab()
        await screen.findByText('Ad live')
        expect(api.calls.at(-1)!.url).not.toContain('archived')

        fireEvent.click(archivedToggle())
        await screen.findByText('Ad old')
        expect(api.calls.at(-1)!.url).toContain('archived=true')
        expect(screen.queryByText('Ad live')).not.toBeInTheDocument()
        expect(screen.getByText(L.archivedNote)).toBeInTheDocument()

        fireEvent.click(archivedToggle())
        await screen.findByText('Ad live')
        expect(api.calls.at(-1)!.url).not.toContain('archived')
    })

    it('shows an Archived pill and Restore only: no Edit, Approve, Reject or Delete', async () => {
        listBy([LIVE], [OLD])
        renderTab()
        await screen.findByText('Ad live')
        fireEvent.click(archivedToggle())
        const row = within(await screen.findByText('Ad old').then(el => el.closest('tr') as HTMLElement))

        expect(row.getByTestId('ad-status')).toHaveTextContent('Archived')
        expect(row.getByRole('button', { name: /^Restore/ })).toBeInTheDocument()
        for (const name of ['Edit', 'Delete']) expect(row.queryByRole('button', { name })).not.toBeInTheDocument()
        for (const name of [/^Approve/, /^Reject/]) expect(row.queryByRole('button', { name })).not.toBeInTheDocument()
        // ...and nothing on the toolbar creates or filters by approval here.
        expect(screen.queryByRole('button', { name: 'Add ad' })).not.toBeInTheDocument()
        expect(screen.queryByLabelText(L.approvalFilter)).not.toBeInTheDocument()
    })

    it('restores an ad, toasts, reloads and refreshes the pending count', async () => {
        listBy([LIVE], [OLD])
        api.on('POST', '/promotions/ads/old/restore', { body: {} })
        const onReviewed = renderTab()
        await screen.findByText('Ad live')
        fireEvent.click(archivedToggle())
        const row = await screen.findByText('Ad old').then(el => el.closest('tr') as HTMLElement)
        const loads = api.callsTo('GET', '/v1/promotions/ads?').length

        fireEvent.click(within(row).getByRole('button', { name: /^Restore/ }))

        await waitFor(() => expect(api.callsTo('POST', '/promotions/ads/old/restore')).toHaveLength(1))
        expect(await screen.findByTestId('toast')).toHaveTextContent('Restored')
        await waitFor(() => expect(onReviewed).toHaveBeenCalledTimes(1))
        expect(api.callsTo('GET', '/v1/promotions/ads?').length).toBeGreaterThan(loads)
    })

    it('shows "Restore the business first" and keeps the row when the restore is refused', async () => {
        listBy([LIVE], [OLD])
        api.on('POST', '/promotions/ads/old/restore', { status: 400, body: { message: 'Restore the business first' } })
        const onReviewed = renderTab()
        await screen.findByText('Ad live')
        fireEvent.click(archivedToggle())
        const row = await screen.findByText('Ad old').then(el => el.closest('tr') as HTMLElement)

        fireEvent.click(within(row).getByRole('button', { name: /^Restore/ }))

        expect(await screen.findByRole('alert')).toHaveTextContent('Restore the business first')
        expect(screen.getByText('Ad old')).toBeInTheDocument()
        expect(screen.queryByTestId('toast')).not.toBeInTheDocument()
        expect(onReviewed).not.toHaveBeenCalled()
    })

    it('says so when nothing is archived', async () => {
        listBy([LIVE], [])
        renderTab()
        await screen.findByText('Ad live')
        fireEvent.click(archivedToggle())
        expect(await screen.findByText(L.emptyArchivedAds)).toBeInTheDocument()
    })

    it('reads in Arabic', async () => {
        listBy([LIVE], [OLD])
        renderTab('ar')
        fireEvent.click(await screen.findByRole('checkbox', { name: ar.Promotions.showArchived }))
        const row = await screen.findByText('Ad old').then(el => el.closest('tr') as HTMLElement)
        expect(within(row).getByTestId('ad-status')).toHaveTextContent(ar.Promotions.statusARCHIVED)
        expect(within(row).getByRole('button', { name: new RegExp(`^${ar.Promotions.restore}`) })).toBeInTheDocument()
    })
})

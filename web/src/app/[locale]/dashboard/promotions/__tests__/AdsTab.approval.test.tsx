import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { AdsTab } from '../_components/AdsTab'
import { fetchRouter } from '@/test/fetchRouter'
import type { PromoAdDTO, PromoBusinessDTO } from '@/types/promotion'

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

const PENDING = ad('pending', {
    approvalStatus: 'PENDING', submittedByName: 'Pat Promoter', submittedAt: '2026-10-05T00:00:00Z',
})
const REJECTED = ad('rejected', { approvalStatus: 'REJECTED', rejectionReason: 'Artwork is blurry', submittedByName: 'Pat Promoter' })
const LIVE = ad('live')
const REDIRECTING = ad('redirecting', {
    approvalStatus: 'PENDING', ctaType: 'COUPON', couponCode: 'SPRING20', submittedByName: 'Pat Promoter',
    redirectUrl: 'https://app.quickwash.example.test/open',
    redirectParams: [
        { name: 'phone', source: 'RENTER_PHONE' },
        { name: 'code', source: 'COUPON_CODE' },
        { name: 'promo', source: 'STATIC', value: 'SPRING' },
    ],
})
const COUPON_ONLY = ad('coupon-only', { ctaType: 'COUPON', couponCode: 'SPRING20', redirectUrl: 'https://app.quickwash.example.test/open', redirectParams: [{ name: 'code', source: 'COUPON_CODE' }] })

let api: ReturnType<typeof fetchRouter>

function renderTab(onReviewed = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <AdsTab businesses={[business]} properties={[{ id: 'p-1', nameEn: 'Palm Tower' }]} onReviewed={onReviewed} />
        </NextIntlClientProvider>,
    )
    return onReviewed
}

const rowOf = (title: string) => screen.getByText(title).closest('tr') as HTMLElement

beforeEach(() => {
    api = fetchRouter()
    api.on('GET', '/v1/promotions/ads', { body: page(PENDING, REJECTED, LIVE) })
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

describe('admin Ads tab — approval', () => {
    it('shows each ad\'s approval pill and who submitted it', async () => {
        renderTab()
        await screen.findByText('Ad pending')

        expect(within(rowOf('Ad pending')).getByTestId('ad-status')).toHaveTextContent('Pending approval')
        expect(within(rowOf('Ad rejected')).getByTestId('ad-status')).toHaveTextContent('Rejected')
        expect(within(rowOf('Ad live')).getByTestId('ad-status')).toHaveTextContent('Live')
        expect(within(rowOf('Ad pending')).getByText('Submitted by Pat Promoter')).toBeInTheDocument()
        expect(within(rowOf('Ad live')).queryByText(/Submitted by/)).not.toBeInTheDocument()
        // The reason a rejected ad was sent back stays visible to the next admin.
        expect(within(rowOf('Ad rejected')).getByText('Reason: Artwork is blurry')).toBeInTheDocument()
    })

    it('offers Approve and Reject on a pending ad only', async () => {
        renderTab()
        await screen.findByText('Ad pending')

        expect(within(rowOf('Ad pending')).getByRole('button', { name: /^Approve/ })).toBeInTheDocument()
        expect(within(rowOf('Ad pending')).getByRole('button', { name: /^Reject/ })).toBeInTheDocument()
        for (const title of ['Ad rejected', 'Ad live']) {
            expect(within(rowOf(title)).queryByRole('button', { name: /^Approve/ })).not.toBeInTheDocument()
            expect(within(rowOf(title)).queryByRole('button', { name: /^Reject/ })).not.toBeInTheDocument()
        }
    })

    it('approves with one click, reloads, and tells the shell to refresh its count', async () => {
        api.on('POST', '/ads/pending/approve', { status: 200, body: {} })
        const onReviewed = renderTab()
        await screen.findByText('Ad pending')
        const loads = api.callsTo('GET', '/v1/promotions/ads').length

        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Approve/ }))

        await waitFor(() => expect(onReviewed).toHaveBeenCalledTimes(1))
        expect(api.callsTo('POST', '/promotions/ads/pending/approve')).toHaveLength(1)
        expect(api.callsTo('GET', '/v1/promotions/ads').length).toBeGreaterThan(loads)
    })

    it('opens a dialog to reject, and will not send until a reason is typed', async () => {
        api.on('POST', '/ads/pending/reject', { status: 200, body: {} })
        const onReviewed = renderTab()
        await screen.findByText('Ad pending')

        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Reject/ }))
        const dialog = await screen.findByRole('dialog')
        const confirm = within(dialog).getByTestId('reject-ad-confirm')

        expect(confirm).toBeDisabled()
        fireEvent.click(confirm)
        // Whitespace is not a reason either.
        fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: '   ' } })
        expect(confirm).toBeDisabled()
        fireEvent.click(confirm)
        expect(api.callsTo('POST', '/reject')).toHaveLength(0)

        fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: '  Wrong phone number  ' } })
        expect(confirm).toBeEnabled()
        fireEvent.click(confirm)

        await waitFor(() => expect(onReviewed).toHaveBeenCalledTimes(1))
        const sent = api.callsTo('POST', '/promotions/ads/pending/reject')
        expect(sent).toHaveLength(1)
        expect(sent[0].body).toEqual({ reason: 'Wrong phone number' })
    })

    it('caps the reason at the 500 characters the server accepts', async () => {
        renderTab()
        await screen.findByText('Ad pending')
        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Reject/ }))
        const dialog = await screen.findByRole('dialog')
        expect(within(dialog).getByLabelText('Reason')).toHaveAttribute('maxlength', '500')
    })

    it('cancelling the dialog sends nothing', async () => {
        renderTab()
        await screen.findByText('Ad pending')
        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Reject/ }))
        const dialog = await screen.findByRole('dialog')
        fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'Nope' } })
        fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
        expect(api.callsTo('POST', '/reject')).toHaveLength(0)
    })

    it('shows the server\'s message when an approval is refused', async () => {
        api.on('POST', '/ads/pending/approve', { status: 400, body: { message: 'Only a pending ad can be approved' } })
        const onReviewed = renderTab()
        await screen.findByText('Ad pending')
        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Approve/ }))
        expect(await screen.findByRole('alert')).toHaveTextContent('Only a pending ad can be approved')
        expect(onReviewed).not.toHaveBeenCalled()
    })

    it('filters by approval status through the server (All / Pending approval / Rejected)', async () => {
        renderTab()
        await screen.findByText('Ad pending')
        const filter = screen.getByLabelText(en.Promotions.approvalFilter) as HTMLSelectElement

        expect(Array.from(filter.options).map(o => o.textContent)).toEqual(['All ads', 'Pending approval', 'Rejected'])
        expect(api.calls.at(-1)!.url).not.toContain('approvalStatus')

        api.on('GET', 'approvalStatus=PENDING', { body: page(PENDING) })
        fireEvent.change(filter, { target: { value: 'PENDING' } })
        await waitFor(() => expect(screen.queryByText('Ad live')).not.toBeInTheDocument())
        expect(api.calls.at(-1)!.url).toContain('approvalStatus=PENDING')

        api.on('GET', 'approvalStatus=REJECTED', { body: page(REJECTED) })
        fireEvent.change(filter, { target: { value: 'REJECTED' } })
        await screen.findByText('Ad rejected')
        expect(api.calls.at(-1)!.url).toContain('approvalStatus=REJECTED')
    })

    it('shows an approver the redirect link, each parameter and its source, and whether tenant details are shared', async () => {
        api.on('GET', '/v1/promotions/ads', { body: page(REDIRECTING, COUPON_ONLY, LIVE) })
        renderTab()
        await screen.findByText('Ad redirecting')

        const summary = within(rowOf('Ad redirecting')).getByTestId('redirect-summary')
        expect(summary).toHaveTextContent('After copy: https://app.quickwash.example.test/open')
        expect(summary).toHaveTextContent("Parameters: phone (Tenant's phone), code (Coupon code), promo (Fixed value)")
        expect(summary).toHaveTextContent('Shares tenant details')
        // The values of a fixed parameter are the ad's own text, not renter data: nothing is hidden by the summary.
        // A link whose parameters carry no tenant details says nothing about sharing.
        const plain = within(rowOf('Ad coupon-only')).getByTestId('redirect-summary')
        expect(plain).toHaveTextContent('Parameters: code (Coupon code)')
        expect(plain).not.toHaveTextContent('Shares tenant details')
        // An ad without a redirect has no such line.
        expect(within(rowOf('Ad live')).queryByTestId('redirect-summary')).not.toBeInTheDocument()
        // ...and the Approve button is still beside it.
        expect(within(rowOf('Ad redirecting')).getByRole('button', { name: /^Approve/ })).toBeInTheDocument()
    })
})

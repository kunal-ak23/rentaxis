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
        expect(sent[0].body).toEqual({ reason: 'Wrong phone number', expectedSubmittedAt: PENDING.submittedAt })
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

/**
 * Review round 1: approve and reject send the ad's submittedAt exactly as loaded; a 409 promo.changed
 * means the promoter edited the ad since, and nothing is approved or rejected on a stale view.
 */
describe('admin Ads tab — reviewing the version that was loaded', () => {
    const CHANGED = {
        status: 409,
        body: { code: 'promo.changed', message: 'This ad was changed after you opened it. Reload to review the latest version.' },
    }
    const NEWER = ad('pending', {
        approvalStatus: 'PENDING', submittedByName: 'Pat Promoter', submittedAt: '2026-10-06T09:30:00.654321Z', titleEn: 'Ad pending',
    })
    let shown: PromoAdDTO[]

    beforeEach(() => {
        shown = [PENDING]
        api.on('GET', '/v1/promotions/ads?', () => ({ body: page(...shown) }))
    })

    it('approves with the submittedAt it loaded, byte for byte', async () => {
        shown = [ad('pending', { approvalStatus: 'PENDING', submittedAt: '2026-10-05T14:11:12.123456+04:00' })]
        api.on('POST', '/ads/pending/approve', { body: {} })
        renderTab()
        await screen.findByText('Ad pending')
        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Approve/ }))
        await waitFor(() => expect(api.callsTo('POST', '/ads/pending/approve')).toHaveLength(1))
        expect(api.callsTo('POST', '/ads/pending/approve')[0].body).toEqual({ expectedSubmittedAt: '2026-10-05T14:11:12.123456+04:00' })
    })

    it('says the ad changed, reloads the latest version and the count, and approves nothing, when approve answers promo.changed', async () => {
        api.on('POST', '/ads/pending/approve', CHANGED)
        const onReviewed = renderTab()
        await screen.findByText('Ad pending')
        const loads = api.callsTo('GET', '/v1/promotions/ads?').length
        shown = [NEWER]

        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Approve/ }))

        // The banner survives the reload that it follows.
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'This ad was changed after you opened it. Reload to review the latest version.')
        expect(api.callsTo('GET', '/v1/promotions/ads?').length).toBeGreaterThan(loads)
        expect(onReviewed).toHaveBeenCalledTimes(1)
        expect(screen.queryByTestId('toast')).not.toBeInTheDocument()

        // Approving again reviews what is now on screen: the newer version's submittedAt.
        api.on('POST', '/ads/pending/approve', { body: {} })
        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Approve/ }))
        await waitFor(() => expect(api.callsTo('POST', '/ads/pending/approve')).toHaveLength(2))
        expect(api.callsTo('POST', '/ads/pending/approve')[1].body).toEqual({ expectedSubmittedAt: '2026-10-06T09:30:00.654321Z' })
    })

    it('treats a 409 with some other code as an ordinary refusal: no reload', async () => {
        api.on('POST', '/ads/pending/approve', { status: 409, body: { code: 'other', message: 'Conflict' } })
        renderTab()
        await screen.findByText('Ad pending')
        const loads = api.callsTo('GET', '/v1/promotions/ads?').length
        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Approve/ }))
        expect(await screen.findByRole('alert')).toHaveTextContent('Conflict')
        expect(api.callsTo('GET', '/v1/promotions/ads?')).toHaveLength(loads)
    })

    async function openReject(reasonText = 'Wrong phone number') {
        await screen.findByText('Ad pending')
        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Reject/ }))
        const dialog = await screen.findByRole('dialog')
        fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: reasonText } })
        return dialog
    }

    it('keeps the reject dialog open, with the reason, and shows a refusal inside it', async () => {
        api.on('POST', '/ads/pending/reject', { status: 500, body: { message: 'Something went wrong' } })
        renderTab()
        const dialog = await openReject()

        fireEvent.click(within(dialog).getByTestId('reject-ad-confirm'))

        expect(await within(dialog).findByTestId('reject-error')).toHaveTextContent('Something went wrong')
        expect(screen.getByRole('dialog')).toBe(dialog)
        expect(within(dialog).getByLabelText('Reason')).toHaveValue('Wrong phone number')
        // The only alert is the dialog's own; nothing is hidden behind the backdrop.
        expect(screen.getAllByRole('alert')).toHaveLength(1)
        expect(dialog).toContainElement(screen.getByRole('alert'))
        // ...and the admin can try again.
        expect(within(dialog).getByTestId('reject-ad-confirm')).toBeEnabled()
    })

    it('sends the reason again, against the latest version, after a reject answers promo.changed', async () => {
        let calls = 0
        api.on('POST', '/ads/pending/reject', () => (++calls === 1 ? CHANGED : { body: {} }))
        renderTab()
        const dialog = await openReject('Wrong phone number')
        shown = [NEWER]

        fireEvent.click(within(dialog).getByTestId('reject-ad-confirm'))

        expect(await within(dialog).findByTestId('reject-error')).toHaveTextContent(
            'This ad was changed after you opened it. Reload to review the latest version.')
        // Still open, reason kept, list reloaded behind it.
        expect(screen.getByRole('dialog')).toBe(dialog)
        expect(within(dialog).getByLabelText('Reason')).toHaveValue('Wrong phone number')
        expect(api.callsTo('GET', '/v1/promotions/ads?').length).toBeGreaterThan(1)

        fireEvent.click(within(dialog).getByTestId('reject-ad-confirm'))
        await waitFor(() => expect(api.callsTo('POST', '/ads/pending/reject')).toHaveLength(2))
        expect(api.callsTo('POST', '/ads/pending/reject')[0].body).toEqual({ reason: 'Wrong phone number', expectedSubmittedAt: PENDING.submittedAt })
        expect(api.callsTo('POST', '/ads/pending/reject')[1].body).toEqual({ reason: 'Wrong phone number', expectedSubmittedAt: '2026-10-06T09:30:00.654321Z' })
        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })

    it('closes the dialog and says why when the ad is no longer waiting for a decision', async () => {
        api.on('POST', '/ads/pending/reject', CHANGED)
        renderTab()
        const dialog = await openReject()
        shown = [ad('pending', { approvalStatus: 'APPROVED' })]

        fireEvent.click(within(dialog).getByTestId('reject-ad-confirm'))

        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
        expect(await screen.findByRole('alert')).toHaveTextContent('This ad was changed after you opened it.')
    })

    it('does not carry a refusal into the next reject dialog', async () => {
        api.on('POST', '/ads/pending/reject', { status: 500, body: { message: 'Something went wrong' } })
        renderTab()
        const dialog = await openReject()
        fireEvent.click(within(dialog).getByTestId('reject-ad-confirm'))
        await within(dialog).findByTestId('reject-error')
        fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
        await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

        fireEvent.click(within(rowOf('Ad pending')).getByRole('button', { name: /^Reject/ }))
        const again = await screen.findByRole('dialog')
        expect(within(again).queryByTestId('reject-error')).not.toBeInTheDocument()
        expect(within(again).getByLabelText('Reason')).toHaveValue('')
    })
})


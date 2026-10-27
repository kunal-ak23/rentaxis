import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { fetchRouter } from '@/test/fetchRouter'

const session = vi.hoisted(() => ({ role: 'TENANT_ADMIN' as string }))
vi.mock('next-auth/react', () => ({
    useSession: () => ({ data: { user: { id: 'u-admin', role: session.role } }, status: 'authenticated' }),
}))

import PromotionsPage from '../page'

const pendingAd = {
    id: 'ad-1', businessId: 'b-1', businessNameEn: 'Sparkle Laundry',
    titleEn: 'Wash and fold', titleAr: null, subtitleEn: null, subtitleAr: null,
    backgroundImageUrl: null, accentColor: null, ctaType: 'NONE', ctaLabelEn: null, ctaLabelAr: null,
    ctaUrl: null, couponCode: null, couponTermsEn: null, couponTermsAr: null,
    startsAt: null, endsAt: null, priority: 1, placement: 'HOME_AND_OFFERS', active: true, propertyIds: ['p-1'],
    impressions: 0, clicks: 0,
    approvalStatus: 'PENDING', rejectionReason: null, submittedAt: '2026-10-05T00:00:00Z', reviewedAt: null,
    submittedByName: 'Pat Promoter', createdAt: '2026-10-05T00:00:00Z', updatedAt: '2026-10-05T00:00:00Z',
}
const sparkle = {
    id: 'b-1', nameEn: 'Sparkle Laundry', nameAr: null, logoUrl: null, category: 'SERVICES', phoneE164: null,
    whatsappE164: null, allowedDomains: [], active: true, adCount: 1,
    createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
}
const pageOf = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 10 })

let api: ReturnType<typeof fetchRouter>
let pending = 1

function renderPage() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <PromotionsPage />
        </NextIntlClientProvider>,
    )
}

beforeEach(() => {
    session.role = 'TENANT_ADMIN'
    pending = 1
    api = fetchRouter()
    api.on('GET', '/v1/promotions/businesses', { body: pageOf([sparkle]) })
    api.on('GET', '/v1/properties', { body: [{ property: { id: 'p-1', nameEn: 'Palm Tower' } }] })
    api.on('GET', '/v1/promotions/ads/pending-count', () => ({ body: { count: pending } }))
    api.on('GET', /\/v1\/promotions\/ads\?/, () => ({ body: pageOf(pending > 0 ? [pendingAd] : []) }))
    api.on('GET', '/v1/promotions/promoters', { body: [] })
    api.on('POST', '/ads/ad-1/approve', () => { pending = 0; return { body: {} } })
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

describe('admin Promotions page — approvals and promoters', () => {
    it('badges the Ads tab with the number of ads waiting for a decision', async () => {
        renderPage()
        const badge = await screen.findByTestId('pending-count-badge')
        expect(badge).toHaveTextContent('1')
        expect(within(screen.getByRole('tab', { name: /^Ads/ })).getByText('1 ad awaiting approval')).toBeInTheDocument()
    })

    it('shows no badge when nothing is waiting', async () => {
        pending = 0
        renderPage()
        await waitFor(() => expect(api.callsTo('GET', '/ads/pending-count')).toHaveLength(1))
        await screen.findByRole('tab', { name: 'Ads' })
        expect(screen.queryByTestId('pending-count-badge')).not.toBeInTheDocument()
    })

    it('drops the badge after the last pending ad is approved', async () => {
        renderPage()
        await screen.findByTestId('pending-count-badge')
        fireEvent.click(await screen.findByRole('button', { name: /^Approve/ }))
        await waitFor(() => expect(screen.queryByTestId('pending-count-badge')).not.toBeInTheDocument())
        expect(api.callsTo('GET', '/ads/pending-count').length).toBeGreaterThanOrEqual(2)
    })

    it('has a Promoters tab that loads the organisation\'s promoters', async () => {
        renderPage()
        fireEvent.click(await screen.findByRole('tab', { name: 'Promoters' }))
        expect(await screen.findByRole('button', { name: 'Invite promoter' })).toBeInTheDocument()
        expect(api.callsTo('GET', '/v1/promotions/promoters')).toHaveLength(1)
    })

    it('is closed to a PROMOTER, who is refused every /promotions route by the server anyway', async () => {
        session.role = 'PROMOTER'
        renderPage()
        expect(await screen.findByText(en.Promotions.noAccess)).toBeInTheDocument()
        expect(api.calls).toHaveLength(0)
    })
})

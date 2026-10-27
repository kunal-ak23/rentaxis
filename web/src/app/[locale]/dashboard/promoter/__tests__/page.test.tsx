import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { fetchRouter } from '@/test/fetchRouter'
import type { PromoAdDTO, PromoBusinessDTO } from '@/types/promotion'

const session = vi.hoisted(() => ({ role: 'PROMOTER' as string }))
vi.mock('next-auth/react', () => ({
    useSession: () => ({ data: { user: { id: 'u-promoter', role: session.role } }, status: 'authenticated' }),
}))

import PromoterPortalPage from '../page'

const PROMOTER_API = '/api/proxy/v1/promoter/'

const business = (over: Partial<PromoBusinessDTO> = {}): PromoBusinessDTO => ({
    id: 'b-1', nameEn: 'Sparkle Laundry', nameAr: null, logoUrl: null, category: 'SERVICES',
    phoneE164: '+971501234567', whatsappE164: null, allowedDomains: ['sparkle.example.test'],
    active: true, adCount: 2, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', ...over,
})

const ad = (id: string, over: Partial<PromoAdDTO> = {}): PromoAdDTO => ({
    id, businessId: 'b-1', businessNameEn: 'Sparkle Laundry',
    titleEn: `Ad ${id}`, titleAr: null, subtitleEn: null, subtitleAr: null,
    backgroundImageUrl: null, accentColor: null, ctaType: 'NONE', ctaLabelEn: null, ctaLabelAr: null,
    ctaUrl: null, couponCode: null, couponTermsEn: null, couponTermsAr: null,
    startsAt: null, endsAt: null, priority: 1, placement: 'HOME_AND_OFFERS', active: true, propertyIds: ['p-1'],
    impressions: 1200, clicks: 34,
    approvalStatus: 'APPROVED', rejectionReason: null, submittedAt: '2026-10-02T00:00:00Z', reviewedAt: null,
    submittedByName: 'Pat Promoter', createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z', ...over,
})

const pageOf = (content: unknown[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 10 })

const ADS = [
    ad('live'),
    ad('pending', { approvalStatus: 'PENDING' }),
    ad('rejected', { approvalStatus: 'REJECTED', rejectionReason: 'Image is blurry' }),
    ad('scheduled', { startsAt: '2099-01-01T00:00:00Z' }),
    ad('ended', { endsAt: '2020-01-01T00:00:00Z' }),
    ad('paused', { active: false }),
]

let api: ReturnType<typeof fetchRouter>

function renderPortal(locale: 'en' | 'ar' = 'en') {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <PromoterPortalPage />
        </NextIntlClientProvider>,
    )
}

function plan({ canCreate = false, businesses = [business()], ads = ADS }:
    { canCreate?: boolean; businesses?: PromoBusinessDTO[]; ads?: PromoAdDTO[] } = {}) {
    api.on('GET', `${PROMOTER_API}me`, { body: { canCreateBusinesses: canCreate, organisationName: 'Al Ashram Properties' } })
    api.on('GET', `${PROMOTER_API}properties`, { body: [{ id: 'p-1', nameEn: 'Palm Tower', nameAr: 'برج النخيل' }, { id: 'p-2', nameEn: 'Marina Heights', nameAr: null }] })
    api.on('GET', `${PROMOTER_API}businesses`, { body: businesses })
    api.on('GET', `${PROMOTER_API}ads`, { body: pageOf(ads) })
}

const rowOf = (title: string) => screen.getByText(title).closest('tr') as HTMLElement
const pill = (title: string) => within(rowOf(title)).getByTestId('ad-status')

beforeEach(() => {
    session.role = 'PROMOTER'
    api = fetchRouter()
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

describe('promoter portal', () => {
    it('shows the organisation, and the ads with a status pill each', async () => {
        plan()
        renderPortal()
        await screen.findByText('Ad live')

        expect(screen.getByText('Your ads for Al Ashram Properties. Nothing reaches tenants until an administrator approves it.')).toBeInTheDocument()
        expect(pill('Ad live')).toHaveTextContent('Live')
        expect(pill('Ad pending')).toHaveTextContent('Pending approval')
        expect(pill('Ad rejected')).toHaveTextContent('Rejected')
        expect(pill('Ad scheduled')).toHaveTextContent('Scheduled')
        expect(pill('Ad ended')).toHaveTextContent('Expired')
        expect(pill('Ad paused')).toHaveTextContent('Paused')
        // Impressions and taps of their own ads.
        expect(within(rowOf('Ad live')).getByText('1,200')).toBeInTheDocument()
        expect(within(rowOf('Ad live')).getByText('34')).toBeInTheDocument()
    })

    it('shows the reason a rejected ad was sent back, and only on that ad', async () => {
        plan()
        renderPortal()
        await screen.findByText('Ad rejected')

        expect(within(rowOf('Ad rejected')).getByTestId('rejection-reason')).toHaveTextContent('Reason: Image is blurry')
        expect(within(rowOf('Ad pending')).queryByTestId('rejection-reason')).not.toBeInTheDocument()
    })

    it('shows a coupon ad\'s redirect link and parameters in the list', async () => {
        plan({ ads: [ad('coupon', {
            ctaType: 'COUPON', couponCode: 'SPRING20', redirectUrl: 'https://app.sparkle.example.test/open',
            redirectParams: [{ name: 'email', source: 'RENTER_EMAIL' }],
        })] })
        renderPortal()
        await screen.findByText('Ad coupon')
        const summary = within(rowOf('Ad coupon')).getByTestId('redirect-summary')
        expect(summary).toHaveTextContent('After copy: https://app.sparkle.example.test/open')
        expect(summary).toHaveTextContent("Parameters: email (Tenant's email)")
    })

    it('only ever calls the promoter API, never an admin route', async () => {
        plan({ canCreate: true })
        api.on('POST', `${PROMOTER_API}ads`, { status: 201, body: ad('new', { approvalStatus: 'PENDING' }) })
        api.on('PUT', `${PROMOTER_API}businesses/b-1`, { body: business() })
        api.on('DELETE', `${PROMOTER_API}ads/live`, { status: 200 })
        vi.spyOn(window, 'confirm').mockReturnValue(true)
        renderPortal()
        await screen.findByText('Ad live')

        // Exercise every action the portal has: tab switch, create, edit a business, delete.
        fireEvent.click(within(rowOf('Ad live')).getByRole('button', { name: 'Delete' }))
        await waitFor(() => expect(api.callsTo('DELETE', '/promoter/ads/live')).toHaveLength(1))
        fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }))
        fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        await waitFor(() => expect(api.callsTo('PUT', '/promoter/businesses/b-1')).toHaveLength(1))
        fireEvent.click(screen.getByRole('tab', { name: 'Ads' }))
        fireEvent.click(await screen.findByRole('button', { name: 'Add ad' }))
        fireEvent.change(screen.getByLabelText('Title (English)'), { target: { value: 'Brand new' } })
        fireEvent.click(screen.getByLabelText('Palm Tower'))
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        await waitFor(() => expect(api.callsTo('POST', '/promoter/ads')).toHaveLength(1))

        expect(api.calls.length).toBeGreaterThan(5)
        const strays = api.calls.filter(c => !c.url.startsWith(PROMOTER_API))
        expect(strays.map(c => `${c.method} ${c.url}`)).toEqual([])
    })

    it('creates an ad with only its own properties and without priority or placement', async () => {
        plan()
        api.on('POST', `${PROMOTER_API}ads`, { status: 201, body: ad('new', { approvalStatus: 'PENDING' }) })
        renderPortal()
        await screen.findByText('Ad live')

        fireEvent.click(screen.getByRole('button', { name: 'Add ad' }))
        // The editor offers the promoter's two properties and nothing from the admin's list.
        const group = screen.getByRole('group', { name: 'Show to' })
        expect(within(group).getAllByRole('checkbox').map(c => c.closest('label')?.textContent))
            .toEqual(['Palm Tower', 'Marina Heights'])
        expect(screen.queryByLabelText('Priority')).not.toBeInTheDocument()
        expect(screen.getByTestId('ad-approval-notice')).toBeInTheDocument()

        fireEvent.change(screen.getByLabelText('Title (English)'), { target: { value: 'Wash and fold' } })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        // No property chosen: nothing is sent.
        expect(await screen.findByText('Choose at least one property.')).toBeInTheDocument()
        expect(api.callsTo('POST', '/promoter/ads')).toHaveLength(0)

        fireEvent.click(screen.getByLabelText('Marina Heights'))
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        await waitFor(() => expect(api.callsTo('POST', '/promoter/ads')).toHaveLength(1))
        const body = api.callsTo('POST', '/promoter/ads')[0].body as Record<string, unknown>
        expect(body).toMatchObject({ businessId: 'b-1', titleEn: 'Wash and fold', propertyIds: ['p-2'] })
        expect('priority' in body).toBe(false)
        expect('placement' in body).toBe(false)
        // Back on the list, saying where the ad now stands.
        expect(await screen.findByRole('status')).toHaveTextContent('waiting for an administrator\'s approval')
    })

    it('shows the server\'s message when an ad is refused', async () => {
        plan()
        api.on('POST', `${PROMOTER_API}ads`, { status: 400, body: { message: 'Property is outside your access' } })
        renderPortal()
        await screen.findByText('Ad live')
        fireEvent.click(screen.getByRole('button', { name: 'Add ad' }))
        fireEvent.change(screen.getByLabelText('Title (English)'), { target: { value: 'Wash and fold' } })
        fireEvent.click(screen.getByLabelText('Palm Tower'))
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        expect(await screen.findByText('Property is outside your access')).toBeInTheDocument()
    })

    it('does not offer to add an ad when no business has been shared', async () => {
        plan({ businesses: [], ads: [] })
        renderPortal()
        await screen.findByText(en.Promoter.emptyAds)
        expect(screen.getByRole('button', { name: 'Add ad' })).toBeDisabled()
    })

    describe('businesses', () => {
        it('are read-only without the can-create switch', async () => {
            plan({ canCreate: false })
            renderPortal()
            await screen.findByText('Ad live')
            fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }))

            const row = (await screen.findByText('Sparkle Laundry')).closest('tr') as HTMLElement
            expect(within(row).getByText('Services')).toBeInTheDocument()
            expect(screen.getByText(en.Promoter.businessesReadOnlyHint)).toBeInTheDocument()
            expect(screen.queryByRole('button', { name: 'Add business' })).not.toBeInTheDocument()
            expect(within(row).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
        })

        it('can be added and edited with the can-create switch, and editing warns that live ads go back for approval', async () => {
            plan({ canCreate: true })
            api.on('POST', `${PROMOTER_API}businesses`, { status: 201, body: business({ id: 'b-2', nameEn: 'Shine Car Wash' }) })
            api.on('PUT', `${PROMOTER_API}businesses/b-1`, { body: business() })
            renderPortal()
            await screen.findByText('Ad live')
            fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }))

            // New business: no ads yet, so no warning.
            fireEvent.click(await screen.findByRole('button', { name: 'Add business' }))
            expect(screen.queryByTestId('business-approval-notice')).not.toBeInTheDocument()
            fireEvent.change(screen.getByLabelText('Name (English)'), { target: { value: 'Shine Car Wash' } })
            fireEvent.click(screen.getByRole('button', { name: 'Save' }))
            await waitFor(() => expect(api.callsTo('POST', '/promoter/businesses')).toHaveLength(1))
            expect(api.callsTo('POST', '/promoter/businesses')[0].body).toMatchObject({ nameEn: 'Shine Car Wash' })

            // Existing business: the warning is there before saving.
            fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
            expect(screen.getByTestId('business-approval-notice')).toHaveTextContent(en.Promoter.businessEditSendsForApproval)
            fireEvent.click(screen.getByRole('button', { name: 'Save' }))
            await waitFor(() => expect(api.callsTo('PUT', '/promoter/businesses/b-1')).toHaveLength(1))
            expect(await screen.findByRole('status')).toHaveTextContent(en.Promoter.businessSavedAdsPending)
        })

        it('shows the server\'s message when a business is refused, while the editor is still open', async () => {
            plan({ canCreate: true })
            api.on('PUT', `${PROMOTER_API}businesses/b-1`, { status: 400, body: { message: 'A business with this name already exists' } })
            renderPortal()
            await screen.findByText('Ad live')
            fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }))
            fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
            fireEvent.click(screen.getByRole('button', { name: 'Save' }))
            expect(await screen.findByText('A business with this name already exists')).toBeInTheDocument()
            expect(screen.getByTestId('business-approval-notice')).toBeInTheDocument()
        })

        it('says so when an administrator has shared none', async () => {
            plan({ businesses: [], ads: [] })
            renderPortal()
            await screen.findByText(en.Promoter.emptyAds)
            fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }))
            expect(await screen.findByText(en.Promoter.emptyBusinesses)).toBeInTheDocument()
        })
    })

    it('renders in Arabic', async () => {
        plan()
        renderPortal('ar')
        expect(await screen.findByRole('heading', { name: ar.Promoter.title })).toBeInTheDocument()
        await screen.findByText('Ad live')
        expect(pill('Ad pending')).toHaveTextContent(ar.Promotions.statusPENDING)
    })

    it.each(['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'RENTER'])('is closed to %s, who fetch nothing', async role => {
        session.role = role
        plan()
        renderPortal()
        expect(await screen.findByText(en.Promoter.noAccess)).toBeInTheDocument()
        expect(api.calls).toHaveLength(0)
    })
})

/** Addendum B: delete always works for a promoter too (hard delete or archive), through their own endpoints. */
describe('promoter portal — delete', () => {
    let confirm: ReturnType<typeof vi.spyOn>
    beforeEach(() => {
        confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    })

    it.each([
        ['DELETED', 'Deleted'],
        ['ARCHIVED', 'Archived'],
    ])('deletes an ad and toasts "%s" from the outcome', async (outcome, text) => {
        plan()
        api.on('DELETE', `${PROMOTER_API}ads/live`, { body: { outcome } })
        renderPortal()
        await screen.findByText('Ad live')
        const loads = api.callsTo('GET', `${PROMOTER_API}ads`).length

        fireEvent.click(within(rowOf('Ad live')).getByRole('button', { name: 'Delete' }))

        expect(await screen.findByTestId('toast')).toHaveTextContent(text)
        expect(confirm).toHaveBeenCalledWith(
            'Delete this ad? If tenants have already seen it, it is archived instead so its view history is kept.')
        expect(api.callsTo('DELETE', `${PROMOTER_API}ads/live`)).toHaveLength(1)
        await waitFor(() => expect(api.callsTo('GET', `${PROMOTER_API}ads`).length).toBeGreaterThan(loads))
    })

    it('sends nothing when the promoter cancels, and shows the server\'s message when a delete fails', async () => {
        plan()
        renderPortal()
        await screen.findByText('Ad live')
        confirm.mockReturnValueOnce(false)
        fireEvent.click(within(rowOf('Ad live')).getByRole('button', { name: 'Delete' }))
        expect(api.callsTo('DELETE', PROMOTER_API)).toHaveLength(0)

        api.on('DELETE', `${PROMOTER_API}ads/live`, { status: 404, body: { message: 'Ad not found' } })
        fireEvent.click(within(rowOf('Ad live')).getByRole('button', { name: 'Delete' }))
        expect(await screen.findByRole('alert')).toHaveTextContent('Ad not found')
        expect(screen.queryByTestId('toast')).not.toBeInTheDocument()
    })

    it('has no archived list and no Restore: restoring is the administrator\'s', async () => {
        plan({ canCreate: true })
        renderPortal()
        await screen.findByText('Ad live')
        expect(screen.queryByRole('checkbox', { name: 'Show archived' })).not.toBeInTheDocument()
        expect(screen.queryByRole('button', { name: /Restore/ })).not.toBeInTheDocument()
        fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }))
        await screen.findByText('Sparkle Laundry')
        expect(screen.queryByRole('checkbox', { name: 'Show archived' })).not.toBeInTheDocument()
        expect(screen.queryByRole('button', { name: /Restore/ })).not.toBeInTheDocument()
    })

    describe('businesses', () => {
        async function openBusinesses(canCreate: boolean) {
            plan({ canCreate })
            renderPortal()
            await screen.findByText('Ad live')
            fireEvent.click(screen.getByRole('tab', { name: 'Businesses' }))
            return (await screen.findByText('Sparkle Laundry')).closest('tr') as HTMLElement
        }

        it('cannot be deleted without the can-create switch', async () => {
            const row = await openBusinesses(false)
            expect(within(row).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()
            expect(api.callsTo('DELETE', `${PROMOTER_API}businesses`)).toHaveLength(0)
        })

        it.each([
            ['DELETED', 'Deleted'],
            ['ARCHIVED', 'Archived'],
        ])('can be deleted with it, toasting "%s" from the outcome and reloading the list', async (outcome, text) => {
            const row = await openBusinesses(true)
            api.on('DELETE', `${PROMOTER_API}businesses/b-1`, { body: { outcome } })
            const loads = api.callsTo('GET', `${PROMOTER_API}businesses`).length

            fireEvent.click(within(row).getByRole('button', { name: 'Delete' }))

            expect(await screen.findByTestId('toast')).toHaveTextContent(text)
            expect(confirm).toHaveBeenCalledWith(
                'Delete this business? If it has ads, it is archived instead so its history is kept, and its ads are archived too.')
            expect(api.callsTo('DELETE', `${PROMOTER_API}businesses/b-1`)).toHaveLength(1)
            await waitFor(() => expect(api.callsTo('GET', `${PROMOTER_API}businesses`).length).toBeGreaterThan(loads))
        })

        it('sends nothing when cancelled, and shows the server\'s refusal', async () => {
            const row = await openBusinesses(true)
            confirm.mockReturnValueOnce(false)
            fireEvent.click(within(row).getByRole('button', { name: 'Delete' }))
            expect(api.callsTo('DELETE', `${PROMOTER_API}businesses`)).toHaveLength(0)

            api.on('DELETE', `${PROMOTER_API}businesses/b-1`, { status: 403, body: { message: 'You cannot create businesses' } })
            fireEvent.click(within(row).getByRole('button', { name: 'Delete' }))
            expect(await screen.findByRole('alert')).toHaveTextContent('You cannot create businesses')
            expect(screen.queryByTestId('toast')).not.toBeInTheDocument()
        })
    })
})


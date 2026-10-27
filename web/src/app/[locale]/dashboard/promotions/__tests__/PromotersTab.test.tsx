import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { PromotersTab } from '../_components/PromotersTab'
import { fetchRouter } from '@/test/fetchRouter'
import type { PromoBusinessDTO, PromoterDTO } from '@/types/promotion'

const business = (id: string, nameEn: string): PromoBusinessDTO => ({
    id, nameEn, nameAr: null, logoUrl: null, category: 'SERVICES', phoneE164: null, whatsappE164: null,
    allowedDomains: [], active: true, adCount: 0, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
})
const businesses = [business('b-1', 'Sparkle Laundry'), business('b-2', 'Shine Car Wash')]
const properties = [
    { id: 'p-1', nameEn: 'Palm Tower' },
    { id: 'p-2', nameEn: 'Marina Heights' },
    { id: 'p-3', nameEn: 'Harbour Gate' },
]

const PAT: PromoterDTO = {
    userId: 'u-1', name: 'Pat Promoter', email: 'promoter@example.test', phoneNumber: null, status: 'ACTIVE',
    canCreateBusinesses: true, businessIds: ['b-1'], propertyIds: ['p-1', 'p-2'],
}
const SAM: PromoterDTO = {
    userId: 'u-2', name: 'Sam Seller', email: 'seller@example.test', phoneNumber: '+971501234567', status: 'INACTIVE',
    canCreateBusinesses: false, businessIds: [], propertyIds: [],
}

let api: ReturnType<typeof fetchRouter>

function renderTab() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <PromotersTab businesses={businesses} properties={properties} />
        </NextIntlClientProvider>,
    )
}

const drawer = () => screen.getByTestId('promoter-access-drawer')

beforeEach(() => {
    api = fetchRouter()
    api.on('GET', '/v1/promotions/promoters', { body: [PAT, SAM] })
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

describe('Promoters tab', () => {
    it('lists each promoter with status, counts and the can-create badge', async () => {
        renderTab()
        const pat = (await screen.findByText('Pat Promoter')).closest('tr') as HTMLElement
        const sam = screen.getByText('Sam Seller').closest('tr') as HTMLElement

        expect(within(pat).getByText('promoter@example.test')).toBeInTheDocument()
        expect(within(pat).getByText('Active')).toBeInTheDocument()
        expect(within(pat).getAllByRole('cell').map(c => c.textContent)).toEqual(
            expect.arrayContaining(['1', '2', 'Can create businesses']))
        expect(within(sam).getByText('Inactive')).toBeInTheDocument()
        expect(within(sam).queryByText('Can create businesses')).not.toBeInTheDocument()
    })

    it('invites a promoter with exactly the access the admin picked', async () => {
        api.on('POST', '/v1/promotions/promoters', { status: 201, body: { userId: 'u-3' } })
        renderTab()
        await screen.findByText('Pat Promoter')

        fireEvent.click(screen.getByRole('button', { name: 'Invite promoter' }))
        const d = within(drawer())
        fireEvent.change(d.getByLabelText('Full name'), { target: { value: '  New Promoter  ' } })
        fireEvent.change(d.getByLabelText('Email'), { target: { value: 'new.promoter@example.test' } })
        fireEvent.change(d.getByLabelText('Phone (optional)'), { target: { value: '+971500000000' } })
        fireEvent.click(d.getByRole('switch', { name: 'Can create businesses' }))
        fireEvent.click(d.getByLabelText('Shine Car Wash'))
        fireEvent.click(d.getByLabelText('Palm Tower'))
        fireEvent.click(d.getByLabelText('Harbour Gate'))
        fireEvent.click(d.getByRole('button', { name: 'Send invitation' }))

        await waitFor(() => expect(api.callsTo('POST', '/v1/promotions/promoters')).toHaveLength(1))
        expect(api.callsTo('POST', '/v1/promotions/promoters')[0].body).toEqual({
            name: 'New Promoter',
            email: 'new.promoter@example.test',
            phoneNumber: '+971500000000',
            canCreateBusinesses: true,
            businessIds: ['b-2'],
            propertyIds: ['p-1', 'p-3'],
        })
        // Closes, confirms, and reloads the list.
        await waitFor(() => expect(screen.queryByTestId('promoter-access-drawer')).not.toBeInTheDocument())
        expect(screen.getByRole('status')).toHaveTextContent('Invitation sent to new.promoter@example.test.')
        expect(api.callsTo('GET', '/v1/promotions/promoters').length).toBeGreaterThan(1)
    })

    it('defaults to ads-only access with no phone', async () => {
        api.on('POST', '/v1/promotions/promoters', { status: 201, body: {} })
        renderTab()
        await screen.findByText('Pat Promoter')
        fireEvent.click(screen.getByRole('button', { name: 'Invite promoter' }))
        const d = within(drawer())
        expect(d.getByRole('switch', { name: 'Can create businesses' })).not.toBeChecked()
        fireEvent.change(d.getByLabelText('Full name'), { target: { value: 'Ads Only' } })
        fireEvent.change(d.getByLabelText('Email'), { target: { value: 'ads.only@example.test' } })
        fireEvent.click(d.getByRole('button', { name: 'Send invitation' }))

        await waitFor(() => expect(api.callsTo('POST', '/v1/promotions/promoters')).toHaveLength(1))
        expect(api.callsTo('POST', '/v1/promotions/promoters')[0].body).toEqual({
            name: 'Ads Only', email: 'ads.only@example.test', phoneNumber: null,
            canCreateBusinesses: false, businessIds: [], propertyIds: [],
        })
    })

    it('asks for a name and a valid email before sending anything', async () => {
        renderTab()
        await screen.findByText('Pat Promoter')
        fireEvent.click(screen.getByRole('button', { name: 'Invite promoter' }))
        const d = within(drawer())

        fireEvent.click(d.getByRole('button', { name: 'Send invitation' }))
        expect(d.getByText("Enter the promoter's name.")).toBeInTheDocument()
        expect(d.getByText('Enter a valid email address.')).toBeInTheDocument()

        fireEvent.change(d.getByLabelText('Full name'), { target: { value: 'Someone' } })
        fireEvent.change(d.getByLabelText('Email'), { target: { value: 'not-an-email' } })
        fireEvent.click(d.getByRole('button', { name: 'Send invitation' }))
        expect(api.callsTo('POST', '/v1/promotions/promoters')).toHaveLength(0)
    })

    it('keeps the drawer open and shows the server\'s refusal for an email that already has a login', async () => {
        api.on('POST', '/v1/promotions/promoters', { status: 400, body: { message: 'This email already has a login' } })
        renderTab()
        await screen.findByText('Pat Promoter')
        fireEvent.click(screen.getByRole('button', { name: 'Invite promoter' }))
        const d = within(drawer())
        fireEvent.change(d.getByLabelText('Full name'), { target: { value: 'Taken' } })
        fireEvent.change(d.getByLabelText('Email'), { target: { value: 'taken@example.test' } })
        fireEvent.click(d.getByRole('button', { name: 'Send invitation' }))

        expect(await d.findByText('This email already has a login')).toBeInTheDocument()
        expect(screen.getByTestId('promoter-access-drawer')).toBeInTheDocument()
    })

    it('edits access as a full replace, starting from what the promoter already has', async () => {
        api.on('PUT', '/v1/promotions/promoters/u-1', { status: 200, body: {} })
        renderTab()
        fireEvent.click(await screen.findByRole('button', { name: 'Edit access: Pat Promoter' }))
        const d = within(drawer())

        // Identity is shown, not editable.
        expect(d.queryByLabelText('Full name')).not.toBeInTheDocument()
        expect(d.getByText('promoter@example.test')).toBeInTheDocument()
        expect(d.getByRole('switch', { name: 'Can create businesses' })).toBeChecked()
        expect(d.getByLabelText('Sparkle Laundry')).toBeChecked()
        expect(d.getByLabelText('Shine Car Wash')).not.toBeChecked()

        fireEvent.click(d.getByRole('switch', { name: 'Can create businesses' }))
        fireEvent.click(d.getByLabelText('Shine Car Wash'))
        fireEvent.click(d.getByLabelText('Palm Tower'))
        fireEvent.click(d.getByRole('button', { name: 'Save' }))

        await waitFor(() => expect(api.callsTo('PUT', '/v1/promotions/promoters/u-1')).toHaveLength(1))
        expect(api.callsTo('PUT', '/v1/promotions/promoters/u-1')[0].body).toEqual({
            canCreateBusinesses: false, businessIds: ['b-1', 'b-2'], propertyIds: ['p-2'],
        })
        await waitFor(() => expect(screen.queryByTestId('promoter-access-drawer')).not.toBeInTheDocument())
        expect(screen.getByRole('status')).toHaveTextContent('Access updated.')
    })

    it('has no deactivate or delete: that stays on Users', async () => {
        renderTab()
        await screen.findByText('Pat Promoter')
        expect(screen.queryByRole('button', { name: /deactivate|delete/i })).not.toBeInTheDocument()
    })
})

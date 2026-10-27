import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { BusinessesTab } from '../_components/BusinessesTab'
import { fetchRouter } from '@/test/fetchRouter'
import type { PromoBusinessDTO } from '@/types/promotion'

/** Addendum B for businesses: delete always works (a business with ads is archived with them); admins restore. */

const business = (id: string, nameEn: string, over: Partial<PromoBusinessDTO> = {}): PromoBusinessDTO => ({
    id, nameEn, nameAr: null, logoUrl: null, category: 'SERVICES', phoneE164: null, whatsappE164: null,
    allowedDomains: [], active: true, adCount: 2, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', ...over,
})
const page = (...content: PromoBusinessDTO[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 10 })

const SPARKLE = business('b-1', 'Sparkle Laundry')
const SHINE = business('b-2', 'Shine Car Wash')
const GONE = business('b-9', 'Old Dry Cleaners', { active: false })

const L = en.Promotions
let api: ReturnType<typeof fetchRouter>
let confirm: ReturnType<typeof vi.spyOn>

function renderTab() {
    const onChanged = vi.fn()
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <BusinessesTab onChanged={onChanged} />
        </NextIntlClientProvider>,
    )
    return onChanged
}
const rowOf = (name: string) => screen.getByText(name).closest('tr') as HTMLElement
const archivedToggle = () => screen.getByRole('checkbox', { name: L.showArchived })

beforeEach(() => {
    api = fetchRouter()
    api.on('GET', '/v1/promotions/businesses?', c => ({ body: c.url.includes('archived=true') ? page(GONE) : page(SPARKLE, SHINE) }))
    confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

describe('admin Businesses tab — delete', () => {
    it('asks with the sentence that says a business with ads is archived, with them', async () => {
        api.on('DELETE', '/promotions/businesses/b-1', { body: { outcome: 'DELETED' } })
        renderTab()
        await screen.findByText('Sparkle Laundry')
        fireEvent.click(within(rowOf('Sparkle Laundry')).getByRole('button', { name: 'Delete' }))
        await waitFor(() => expect(api.callsTo('DELETE', '/promotions/businesses/b-1')).toHaveLength(1))
        expect(confirm).toHaveBeenCalledWith(
            'Delete this business? If it has ads, it is archived instead so its history is kept, and its ads are archived too.')
    })

    it.each([
        ['DELETED', 'Deleted'],
        ['ARCHIVED', 'Archived'],
    ])('toasts "%s" from the outcome, reloads and refreshes the shell\'s business list', async (outcome, text) => {
        api.on('DELETE', '/promotions/businesses/b-1', { body: { outcome } })
        const onChanged = renderTab()
        await screen.findByText('Sparkle Laundry')
        const loads = api.callsTo('GET', '/v1/promotions/businesses?').length

        fireEvent.click(within(rowOf('Sparkle Laundry')).getByRole('button', { name: 'Delete' }))

        expect(await screen.findByTestId('toast')).toHaveTextContent(text)
        await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1))
        expect(api.callsTo('GET', '/v1/promotions/businesses?').length).toBeGreaterThan(loads)
    })

    it('sends nothing when cancelled, and shows the server\'s message when a delete fails', async () => {
        confirm.mockReturnValueOnce(false)
        renderTab()
        await screen.findByText('Shine Car Wash')
        fireEvent.click(within(rowOf('Shine Car Wash')).getByRole('button', { name: 'Delete' }))
        expect(api.callsTo('DELETE', '/promotions/businesses')).toHaveLength(0)

        api.on('DELETE', '/promotions/businesses/b-2', { status: 404, body: { message: 'Business not found' } })
        fireEvent.click(within(rowOf('Shine Car Wash')).getByRole('button', { name: 'Delete' }))
        expect(await screen.findByRole('alert')).toHaveTextContent('Business not found')
        expect(screen.queryByTestId('toast')).not.toBeInTheDocument()
    })
})

describe('admin Businesses tab — archived', () => {
    it('lists archived businesses through ?archived=true and back', async () => {
        renderTab()
        await screen.findByText('Sparkle Laundry')
        expect(api.calls.at(-1)!.url).not.toContain('archived')

        fireEvent.click(archivedToggle())
        await screen.findByText('Old Dry Cleaners')
        expect(api.calls.at(-1)!.url).toContain('archived=true')
        expect(screen.queryByText('Sparkle Laundry')).not.toBeInTheDocument()

        fireEvent.click(archivedToggle())
        await screen.findByText('Sparkle Laundry')
        expect(api.calls.at(-1)!.url).not.toContain('archived')
    })

    it('shows an Archived pill and Restore only: no Edit, Delete or Add business', async () => {
        renderTab()
        await screen.findByText('Sparkle Laundry')
        fireEvent.click(archivedToggle())
        const row = within(await screen.findByText('Old Dry Cleaners').then(el => el.closest('tr') as HTMLElement))

        expect(row.getByTestId('ad-status')).toHaveTextContent('Archived')
        expect(row.getByRole('button', { name: /^Restore/ })).toBeInTheDocument()
        for (const name of ['Edit', 'Delete']) expect(row.queryByRole('button', { name })).not.toBeInTheDocument()
        expect(screen.queryByRole('button', { name: 'Add business' })).not.toBeInTheDocument()
    })

    it('restores a business, toasts, reloads and refreshes the shell\'s business list', async () => {
        api.on('POST', '/promotions/businesses/b-9/restore', { body: {} })
        const onChanged = renderTab()
        await screen.findByText('Sparkle Laundry')
        fireEvent.click(archivedToggle())
        const row = await screen.findByText('Old Dry Cleaners').then(el => el.closest('tr') as HTMLElement)

        fireEvent.click(within(row).getByRole('button', { name: /^Restore/ }))

        await waitFor(() => expect(api.callsTo('POST', '/promotions/businesses/b-9/restore')).toHaveLength(1))
        expect(await screen.findByTestId('toast')).toHaveTextContent('Restored')
        await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1))
    })

    it('shows the server\'s message when the name is now taken, and keeps the row', async () => {
        api.on('POST', '/promotions/businesses/b-9/restore', {
            status: 400, body: { message: 'A business named "Old Dry Cleaners" already exists' },
        })
        const onChanged = renderTab()
        await screen.findByText('Sparkle Laundry')
        fireEvent.click(archivedToggle())
        const row = await screen.findByText('Old Dry Cleaners').then(el => el.closest('tr') as HTMLElement)

        fireEvent.click(within(row).getByRole('button', { name: /^Restore/ }))

        expect(await screen.findByRole('alert')).toHaveTextContent('A business named "Old Dry Cleaners" already exists')
        expect(screen.getByText('Old Dry Cleaners')).toBeInTheDocument()
        expect(screen.queryByTestId('toast')).not.toBeInTheDocument()
        expect(onChanged).not.toHaveBeenCalled()
    })

    it('says so when nothing is archived', async () => {
        api.on('GET', 'archived=true', { body: page() })
        renderTab()
        await screen.findByText('Sparkle Laundry')
        fireEvent.click(archivedToggle())
        expect(await screen.findByText(L.emptyArchivedBusinesses)).toBeInTheDocument()
    })
})

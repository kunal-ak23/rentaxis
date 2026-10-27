import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { AdsTab } from '../_components/AdsTab'
import { BusinessesTab } from '../_components/BusinessesTab'
import { fetchRouter } from '@/test/fetchRouter'
import type { PromoBusinessDTO } from '@/types/promotion'

/**
 * Review round 1 (I2, I3) on the admin tabs: a refused save is shown while the editor is still
 * open, and a double-click on Save sends one request, not two.
 */

const business: PromoBusinessDTO = {
    id: 'b-1', nameEn: 'Sparkle Laundry', nameAr: null, logoUrl: null, category: 'SERVICES', phoneE164: null,
    whatsappE164: null, allowedDomains: [], active: true, adCount: 0,
    createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
}
const empty = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 }

let api: ReturnType<typeof fetchRouter>

beforeEach(() => {
    api = fetchRouter()
    api.on('GET', '/v1/promotions/ads?', { body: empty })
    api.on('GET', '/v1/promotions/businesses?', { body: { ...empty, content: [business], totalElements: 1 } })
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

const save = () => screen.getByRole('button', { name: 'Save' })
const clickTwiceInOneTick = () => act(() => { save().click(); save().click() })

function renderAds() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <AdsTab businesses={[business]} properties={[{ id: 'p-1', nameEn: 'Palm Tower' }]} />
        </NextIntlClientProvider>,
    )
}
function renderBusinesses() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <BusinessesTab />
        </NextIntlClientProvider>,
    )
}

describe('admin Ads tab — saving', () => {
    async function openNewAd() {
        renderAds()
        fireEvent.click(await screen.findByRole('button', { name: 'Add ad' }))
        fireEvent.change(screen.getByLabelText('Title (English)'), { target: { value: 'Friday brunch' } })
    }

    it('shows the server\'s refusal above the editor, which stays open', async () => {
        api.on('POST', '/v1/promotions/ads', { status: 400, body: { message: 'Link is not on an allowed domain' } })
        await openNewAd()
        fireEvent.click(save())

        expect(await screen.findByRole('alert')).toHaveTextContent('Link is not on an allowed domain')
        // Still editing: the form and what was typed are intact.
        expect(screen.getByLabelText('Title (English)')).toHaveValue('Friday brunch')
        await waitFor(() => expect(save()).toBeEnabled())
    })

    it('does not carry a refusal into the next editor', async () => {
        api.on('POST', '/v1/promotions/ads', { status: 400, body: { message: 'Link is not on an allowed domain' } })
        await openNewAd()
        fireEvent.click(save())
        await screen.findByRole('alert')
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
        fireEvent.click(await screen.findByRole('button', { name: 'Add ad' }))
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('creates the ad once for a double-click on Save', async () => {
        api.on('POST', '/v1/promotions/ads', { status: 201, body: {} })
        await openNewAd()
        clickTwiceInOneTick()
        await waitFor(() => expect(api.callsTo('GET', '/v1/promotions/ads?').length).toBeGreaterThan(1))
        expect(api.callsTo('POST', '/v1/promotions/ads')).toHaveLength(1)
    })
})

describe('admin Businesses tab — saving', () => {
    async function openNewBusiness() {
        renderBusinesses()
        fireEvent.click(await screen.findByRole('button', { name: 'Add business' }))
        fireEvent.change(screen.getByLabelText('Name (English)'), { target: { value: 'Shine Car Wash' } })
    }

    it('shows the server\'s refusal above the editor, which stays open', async () => {
        api.on('POST', '/v1/promotions/businesses', { status: 400, body: { message: 'A business with this name already exists' } })
        await openNewBusiness()
        fireEvent.click(save())

        expect(await screen.findByRole('alert')).toHaveTextContent('A business with this name already exists')
        expect(screen.getByLabelText('Name (English)')).toHaveValue('Shine Car Wash')
        await waitFor(() => expect(save()).toBeEnabled())
    })

    it('does not carry a refusal into the next editor', async () => {
        api.on('POST', '/v1/promotions/businesses', { status: 400, body: { message: 'A business with this name already exists' } })
        await openNewBusiness()
        fireEvent.click(save())
        await screen.findByRole('alert')
        fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
        fireEvent.click(await screen.findByRole('button', { name: 'Add business' }))
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })

    it('creates the business once for a double-click on Save', async () => {
        api.on('POST', '/v1/promotions/businesses', { status: 201, body: {} })
        await openNewBusiness()
        clickTwiceInOneTick()
        await waitFor(() => expect(api.callsTo('GET', '/v1/promotions/businesses?').length).toBeGreaterThan(1))
        expect(api.callsTo('POST', '/v1/promotions/businesses')).toHaveLength(1)
    })
})

/** Makes every write wait for `release()`, and records the writes that were started. */
function slowWrites() {
    const inner = global.fetch as typeof fetch
    let release!: () => void
    const gate = new Promise<void>(r => { release = r })
    const started: string[] = []
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const method = (init?.method ?? 'GET').toUpperCase()
        if (method === 'POST' || method === 'PUT') {
            started.push(`${method} ${String(input)}`)
            await gate
        }
        return inner(input, init)
    }) as unknown as typeof fetch
    return { release, started }
}
const nextMacrotask = () => new Promise<void>(r => setTimeout(r, 0))

describe('admin tabs — a second click while the server is still answering', () => {
    it('sends one ad write, however long the first takes', async () => {
        api.on('POST', '/v1/promotions/ads', { status: 201, body: {} })
        renderAds()
        fireEvent.click(await screen.findByRole('button', { name: 'Add ad' }))
        fireEvent.change(screen.getByLabelText('Title (English)'), { target: { value: 'Friday brunch' } })
        const { release, started } = slowWrites()

        fireEvent.click(save())
        await act(async () => { await nextMacrotask() })
        fireEvent.click(save())
        await act(async () => { await nextMacrotask() })
        expect(started).toHaveLength(1)
        await act(async () => { release() })
        await waitFor(() => expect(screen.queryByLabelText('Title (English)')).not.toBeInTheDocument())
        expect(started).toHaveLength(1)
    })

    it('sends one business write, however long the first takes', async () => {
        api.on('POST', '/v1/promotions/businesses', { status: 201, body: {} })
        renderBusinesses()
        fireEvent.click(await screen.findByRole('button', { name: 'Add business' }))
        fireEvent.change(screen.getByLabelText('Name (English)'), { target: { value: 'Shine Car Wash' } })
        const { release, started } = slowWrites()

        fireEvent.click(save())
        await act(async () => { await nextMacrotask() })
        fireEvent.click(save())
        await act(async () => { await nextMacrotask() })
        expect(started).toHaveLength(1)
        await act(async () => { release() })
        await waitFor(() => expect(screen.queryByLabelText('Name (English)')).not.toBeInTheDocument())
        expect(started).toHaveLength(1)
    })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { DirectoryTab } from '../_components/DirectoryTab'
import { fetchRouter } from '@/test/fetchRouter'
import type { DirectoryCategory, PromoBusinessDTO } from '@/types/promotion'

const L = en.Promotions
const cat = (id: string, nameEn: string, over: Partial<DirectoryCategory> = {}): DirectoryCategory => ({
    id, nameEn, nameAr: null, iconUrl: null, sortOrder: 0, active: true, businessIds: ['b-1'],
    businessCount: 3, createdAt: '', updatedAt: '', ...over,
})
const page = (...content: DirectoryCategory[]) => ({ content, totalElements: content.length, totalPages: 1, number: 0, size: 10 })
const BUSINESSES = [{ id: 'b-1', nameEn: 'Sparkle Laundry', active: false } as PromoBusinessDTO]
let api: ReturnType<typeof fetchRouter>

function renderTab() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <DirectoryTab businesses={BUSINESSES} />
        </NextIntlClientProvider>,
    )
}
beforeEach(() => {
    api = fetchRouter()
    api.on('GET', '/directory-categories?', c =>
        ({ body: c.url.includes('archived=true') ? page(cat('c-9', 'Old group', { active: false })) : page(cat('c-1', 'Sample Group')) }))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('DirectoryTab', () => {
    it('lists categories in a table with the business count and active mark', async () => {
        renderTab()
        const row = (await screen.findByText('Sample Group')).closest('tr') as HTMLElement
        expect(within(row).getByText('3 (0 live)')).toBeInTheDocument()
        expect(within(row).getByText('✓')).toBeInTheDocument()
        expect(screen.getByRole('columnheader', { name: L.categoryIcon })).toBeInTheDocument()
    })

    it('counts only the live linked businesses next to the total', async () => {
        api.on('GET', '/directory-categories?', { body: page(
            cat('c-1', 'Car wash', { businessIds: ['b-1'], businessCount: 1 }),
            cat('c-2', 'Laundry', { businessIds: ['b-1', 'b-2'], businessCount: 2 })) })
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <DirectoryTab businesses={[
                    { id: 'b-1', nameEn: 'Paused Co', active: false } as PromoBusinessDTO,
                    { id: 'b-2', nameEn: 'Live Co', active: true } as PromoBusinessDTO]} />
            </NextIntlClientProvider>,
        )
        const paused = (await screen.findByText('Car wash')).closest('tr') as HTMLElement
        expect(within(paused).getByText('1 (0 live)')).toBeInTheDocument()
        const mixed = screen.getByText('Laundry').closest('tr') as HTMLElement
        expect(within(mixed).getByText('2 (1 live)')).toBeInTheDocument()
    })

    it('shows the empty state', async () => {
        api.on('GET', '/directory-categories?', { body: page() })
        renderTab()
        expect(await screen.findByText(L.emptyDirectory)).toBeInTheDocument()
    })

    it('the archived toggle asks for archived rows and offers Restore', async () => {
        api.on('POST', '/directory-categories/c-9/restore', { body: null })
        renderTab()
        await screen.findByText('Sample Group')
        fireEvent.click(screen.getByRole('checkbox', { name: L.showArchived }))
        expect(await screen.findByText('Old group')).toBeInTheDocument()
        expect(api.callsTo('GET', 'archived=true').length).toBeGreaterThan(0)
        fireEvent.click(screen.getByRole('button', { name: `${L.restore}: Old group` }))
        await waitFor(() => expect(api.callsTo('POST', '/c-9/restore')).toHaveLength(1))
    })

    it('archives after a confirmation', async () => {
        api.on('DELETE', '/directory-categories/c-1', { body: null })
        renderTab()
        const row = (await screen.findByText('Sample Group')).closest('tr') as HTMLElement
        fireEvent.click(within(row).getByRole('button', { name: L.delete }))
        await waitFor(() => expect(api.callsTo('DELETE', '/c-1')).toHaveLength(1))
    })

    it('creates a category through the editor', async () => {
        api.on('POST', '/directory-categories', { body: cat('c-2', 'Car wash') })
        renderTab()
        await screen.findByText('Sample Group')
        fireEvent.click(screen.getByRole('button', { name: L.addCategory }))
        fireEvent.change(screen.getByLabelText(L.nameEn), { target: { value: 'Car wash' } })
        fireEvent.click(screen.getByRole('button', { name: L.save }))
        await waitFor(() => expect(api.callsTo('POST', '/directory-categories')).toHaveLength(1))
        expect(api.callsTo('POST', '/directory-categories')[0].body).toMatchObject({ nameEn: 'Car wash', businessIds: [] })
    })
})

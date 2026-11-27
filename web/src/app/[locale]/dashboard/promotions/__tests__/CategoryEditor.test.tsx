import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { CategoryEditor } from '../_components/CategoryEditor'
import type { DirectoryCategory, PromoBusinessDTO } from '@/types/promotion'

const L = en.Promotions
const biz = (id: string, nameEn: string) => ({ id, nameEn } as PromoBusinessDTO)
const BUSINESSES = [biz('b-1', 'Sparkle Laundry'), biz('b-2', 'Shine Car Wash')]
const category = (over: Partial<DirectoryCategory> = {}): DirectoryCategory => ({
    id: 'c-1', nameEn: 'Car wash', nameAr: null, iconUrl: null, sortOrder: 0, active: true,
    businessIds: ['b-2'], businessCount: 1, createdAt: '', updatedAt: '', ...over,
})

function renderEditor(c: DirectoryCategory | null, onSave = vi.fn(), locale: 'en' | 'ar' = 'en') {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <CategoryEditor category={c} businesses={BUSINESSES} onSave={onSave} onCancel={vi.fn()} />
        </NextIntlClientProvider>,
    )
    return onSave
}
afterEach(() => cleanup())

describe('CategoryEditor', () => {
    it('marks a paused business in the checkbox list, in both languages', () => {
        const paused = [{ id: 'b-1', nameEn: 'Sparkle Laundry', active: false } as PromoBusinessDTO, biz('b-2', 'Shine Car Wash')]
        for (const [locale, msgs] of [['en', en], ['ar', ar]] as const) {
            render(
                <NextIntlClientProvider locale={locale} messages={msgs}>
                    <CategoryEditor category={null} businesses={paused} onSave={vi.fn()} onCancel={vi.fn()} />
                </NextIntlClientProvider>,
            )
            const label = screen.getByRole('checkbox', { name: new RegExp('Sparkle Laundry') }).closest('label') as HTMLElement
            expect(label).toHaveTextContent(msgs.Promotions.categoryBusinessPaused)
            const live = screen.getByRole('checkbox', { name: /Shine Car Wash/ }).closest('label') as HTMLElement
            expect(live).not.toHaveTextContent(msgs.Promotions.categoryBusinessPaused)
            cleanup()
        }
    })

    it('requires the English name', async () => {
        const onSave = renderEditor(null)
        fireEvent.click(screen.getByRole('button', { name: L.save }))
        expect(await screen.findByRole('alert')).toHaveTextContent(L.categoryNameRequired)
        expect(onSave).not.toHaveBeenCalled()
    })

    it('sends the chosen businessIds', async () => {
        const onSave = renderEditor(null)
        fireEvent.change(screen.getByLabelText(L.nameEn), { target: { value: ' Laundry ' } })
        fireEvent.click(screen.getByRole('checkbox', { name: 'Sparkle Laundry' }))
        fireEvent.click(screen.getByRole('button', { name: L.save }))
        await waitFor(() => expect(onSave).toHaveBeenCalled())
        expect(onSave.mock.calls[0][0]).toMatchObject({ nameEn: 'Laundry', businessIds: ['b-1'], active: true })
    })

    it('always sends the full businessIds, even an empty list (a PUT replaces the links)', async () => {
        const onSave = renderEditor(category())
        fireEvent.click(screen.getByRole('checkbox', { name: 'Shine Car Wash' }))
        fireEvent.click(screen.getByRole('button', { name: L.save }))
        await waitFor(() => expect(onSave).toHaveBeenCalled())
        expect(onSave.mock.calls[0][0].businessIds).toEqual([])
    })

    it('uses the image field for the icon', () => {
        renderEditor(null)
        expect(screen.getByLabelText(`${L.categoryIcon}: ${L.uploadImage}`)).toBeInTheDocument()
    })

    it('shows the server UNKNOWN_BUSINESS refusal inline and stays open', async () => {
        const onSave = vi.fn().mockRejectedValue(
            Object.assign(new Error('x'), { status: 400, body: '{"code":"UNKNOWN_BUSINESS"}' }))
        // a real ApiError, so instanceof holds
        const { ApiError } = await import('@/lib/api/promotions')
        onSave.mockRejectedValue(new ApiError(400, 'x', '{"code":"UNKNOWN_BUSINESS"}'))
        renderEditor(category(), onSave)
        fireEvent.click(screen.getByRole('button', { name: L.save }))
        expect(await screen.findByRole('alert')).toHaveTextContent(L.categoryUnknownBusiness)
    })

    it('renders in Arabic', () => {
        renderEditor(null, vi.fn(), 'ar')
        expect(screen.getByLabelText(ar.Promotions.nameEn)).toBeInTheDocument()
    })
})

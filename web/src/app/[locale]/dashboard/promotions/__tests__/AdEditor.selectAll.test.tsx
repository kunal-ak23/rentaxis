import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { AdEditor } from '../_components/AdEditor'
import type { PromoAdDTO, PromoAdRequest, PromoBusinessDTO } from '@/types/promotion'

afterEach(() => {
    cleanup()
})

const business: PromoBusinessDTO = {
    id: 'b-1', nameEn: 'Sparkle Laundry', nameAr: null, logoUrl: null, category: 'SERVICES',
    phoneE164: '+971501234567', whatsappE164: null, allowedDomains: ['sparkle.example.test'],
    active: true, adCount: 0, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
}

const properties = [
    { id: 'p-1', nameEn: 'Palm Tower', nameAr: null },
    { id: 'p-2', nameEn: 'Marina Heights', nameAr: null },
    { id: 'p-3', nameEn: 'Creek View', nameAr: null },
]

const saved: PromoAdDTO = {
    id: 'ad-1', businessId: 'b-1', businessNameEn: 'Sparkle Laundry',
    titleEn: 'Wash and fold', titleAr: null, subtitleEn: null, subtitleAr: null,
    backgroundImageUrl: null, accentColor: null, ctaType: 'NONE', ctaLabelEn: null, ctaLabelAr: null,
    ctaUrl: null, couponCode: null, couponTermsEn: null, couponTermsAr: null,
    startsAt: null, endsAt: null, priority: 1, placement: 'HOME_AND_OFFERS', active: true, propertyIds: ['p-2'],
    impressions: 0, clicks: 0,
    approvalStatus: 'APPROVED', rejectionReason: null, submittedAt: null, reviewedAt: null, submittedByName: null,
    createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z',
}

type Save = ReturnType<typeof vi.fn<(body: PromoAdRequest) => void>>

function editor(mode: 'admin' | 'promoter', props: typeof properties, ad: PromoAdDTO | null, onSave: Save) {
    return (
        <NextIntlClientProvider locale="en" messages={en}>
            <AdEditor mode={mode} businesses={[business]} properties={props} ad={ad}
                onSave={onSave} onCancel={vi.fn()} />
        </NextIntlClientProvider>
    )
}

function renderEditor(mode: 'admin' | 'promoter', { ad = null, props = properties }: { ad?: PromoAdDTO | null; props?: typeof properties } = {}) {
    const onSave: Save = vi.fn()
    const view = render(editor(mode, props, ad, onSave))
    return { onSave, rerender: (next: typeof properties) => view.rerender(editor(mode, next, ad, onSave)) }
}

const selectAll = () => screen.getByRole('checkbox', { name: en.Promotions.selectAllProperties }) as HTMLInputElement
const box = (name: string) => screen.getByRole('checkbox', { name }) as HTMLInputElement
const save = () => {
    fireEvent.change(screen.getByLabelText('Title (English)'), { target: { value: 'Wash and fold' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
}

describe('AdEditor property targeting — Select all', () => {
    it('starts a new admin ad on every property, and saves it as "every property"', () => {
        const { onSave } = renderEditor('admin')
        expect(selectAll().checked).toBe(true)
        for (const p of properties) expect(box(p.nameEn).checked).toBe(true)
        save()
        // Empty targeting is the server's "every property", future ones included.
        expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ propertyIds: [] }))
    })

    it('narrows an admin ad to the rest when one property is unticked', () => {
        const { onSave } = renderEditor('admin')
        fireEvent.click(box('Marina Heights'))
        expect(selectAll().checked).toBe(false)
        save()
        expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ propertyIds: ['p-1', 'p-3'] }))
    })

    it('refuses an admin ad with Select all cleared and nothing ticked', () => {
        const { onSave } = renderEditor('admin')
        fireEvent.click(selectAll())
        for (const p of properties) expect(box(p.nameEn).checked).toBe(false)
        save()
        expect(screen.getByRole('alert')).toHaveTextContent(en.Promotions.choosePropertyOrAll)
        expect(onSave).not.toHaveBeenCalled()
    })

    it('ticking Select all again returns an admin ad to every property', () => {
        const { onSave } = renderEditor('admin')
        fireEvent.click(box('Palm Tower'))
        fireEvent.click(selectAll())
        expect(selectAll().checked).toBe(true)
        save()
        expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ propertyIds: [] }))
    })

    it('starts a new promoter ad on every property the promoter was given, listed by id', () => {
        const { onSave } = renderEditor('promoter')
        expect(selectAll().checked).toBe(true)
        save()
        // A promoter ad never means "every property": the list is explicit.
        expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ propertyIds: ['p-1', 'p-2', 'p-3'] }))
    })

    it('preselects a promoter\'s properties that arrive after the editor opened', () => {
        const { onSave, rerender } = renderEditor('promoter', { props: [] })
        rerender(properties)
        expect(selectAll().checked).toBe(true)
        save()
        expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ propertyIds: ['p-1', 'p-2', 'p-3'] }))
    })

    it('clearing Select all on a promoter ad clears the list, and saving then says why not', () => {
        const { onSave } = renderEditor('promoter')
        fireEvent.click(selectAll())
        for (const p of properties) expect(box(p.nameEn).checked).toBe(false)
        save()
        expect(screen.getByRole('alert')).toHaveTextContent(en.Promoter.propertyRequired)
        expect(onSave).not.toHaveBeenCalled()
    })

    it('keeps an existing ad\'s saved targeting instead of selecting everything', () => {
        const { onSave } = renderEditor('admin', { ad: saved })
        expect(selectAll().checked).toBe(false)
        expect(box('Marina Heights').checked).toBe(true)
        expect(box('Palm Tower').checked).toBe(false)
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ propertyIds: ['p-2'] }))
    })

    it('does not reselect a promoter\'s properties once the promoter cleared them', () => {
        const { rerender } = renderEditor('promoter')
        fireEvent.click(selectAll())
        rerender([...properties])
        expect(selectAll().checked).toBe(false)
    })
})

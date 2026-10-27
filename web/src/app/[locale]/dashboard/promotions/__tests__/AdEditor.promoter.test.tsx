import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
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

// The properties this promoter was given, not the organisation's whole portfolio.
const properties = [
    { id: 'p-1', nameEn: 'Palm Tower', nameAr: 'برج النخيل' },
    { id: 'p-2', nameEn: 'Marina Heights', nameAr: null },
]

const rejected: PromoAdDTO = {
    id: 'ad-1', businessId: 'b-1', businessNameEn: 'Sparkle Laundry',
    titleEn: 'Wash and fold', titleAr: null, subtitleEn: null, subtitleAr: null,
    backgroundImageUrl: null, accentColor: null, ctaType: 'NONE', ctaLabelEn: null, ctaLabelAr: null,
    ctaUrl: null, couponCode: null, couponTermsEn: null, couponTermsAr: null,
    startsAt: null, endsAt: null, priority: 4, placement: 'OFFERS_ONLY', active: true, propertyIds: ['p-1'],
    impressions: 0, clicks: 0,
    approvalStatus: 'REJECTED', rejectionReason: 'Image is blurry', submittedAt: '2026-10-02T00:00:00Z',
    reviewedAt: '2026-10-03T00:00:00Z', submittedByName: 'Pat Promoter',
    createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z',
}

function renderEditor(
    mode: 'admin' | 'promoter',
    { onSave = vi.fn<(body: PromoAdRequest) => void>(), ad = null, props = properties, locale = 'en' as 'en' | 'ar' }:
        { onSave?: ReturnType<typeof vi.fn<(body: PromoAdRequest) => void>>; ad?: PromoAdDTO | null; props?: typeof properties; locale?: 'en' | 'ar' } = {},
) {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <AdEditor mode={mode} businesses={[business]} properties={props} ad={ad}
                onSave={onSave} onCancel={vi.fn()} />
        </NextIntlClientProvider>,
    )
    return onSave
}

const title = () => screen.getByLabelText('Title (English)')

describe('AdEditor in promoter mode', () => {
    it('hides priority and placement, which are the organisation\'s to set', () => {
        renderEditor('promoter')
        expect(screen.queryByLabelText('Priority')).not.toBeInTheDocument()
        expect(screen.queryByLabelText('Where it shows')).not.toBeInTheDocument()
        expect(screen.queryByText(en.Promotions.priorityHint)).not.toBeInTheDocument()
    })

    it('still shows priority and placement to an admin', () => {
        renderEditor('admin')
        expect(screen.getByLabelText('Priority')).toBeInTheDocument()
        expect(screen.getByLabelText('Where it shows')).toBeInTheDocument()
        expect(screen.queryByTestId('ad-approval-notice')).not.toBeInTheDocument()
    })

    it('says that saving sends the ad for approval', () => {
        renderEditor('promoter')
        expect(screen.getByTestId('ad-approval-notice')).toHaveTextContent(en.Promoter.savingSendsForApproval)
    })

    it('refuses to save an ad that targets no property, and says why', () => {
        const onSave = renderEditor('promoter')
        fireEvent.change(title(), { target: { value: 'Wash and fold' } })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(screen.getByRole('alert')).toHaveTextContent(en.Promoter.propertyRequired)
        expect(onSave).not.toHaveBeenCalled()
    })

    it('does not let an admin-style "everyone" ad through: the empty selection is an error, not a default', () => {
        renderEditor('promoter')
        // The admin's "Everyone" hint must not appear: a promoter ad always names properties.
        expect(screen.queryByText(en.Promotions.targetingAll)).not.toBeInTheDocument()
        expect(screen.getByText(en.Promoter.propertiesHint)).toBeInTheDocument()
    })

    it('saves with the chosen properties and without priority or placement', () => {
        const onSave = renderEditor('promoter')
        fireEvent.change(title(), { target: { value: 'Wash and fold' } })
        fireEvent.click(screen.getByLabelText('Marina Heights'))
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).toHaveBeenCalledTimes(1)
        const body = onSave.mock.calls[0][0]
        expect(body).toMatchObject({ businessId: 'b-1', titleEn: 'Wash and fold', propertyIds: ['p-2'], active: true })
        expect('priority' in body).toBe(false)
        expect('placement' in body).toBe(false)
    })

    it('does not send an edited ad\'s stored priority and placement back either', () => {
        const onSave = renderEditor('promoter', { ad: rejected })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).toHaveBeenCalledTimes(1)
        const body = onSave.mock.calls[0][0]
        expect(body.propertyIds).toEqual(['p-1'])
        expect('priority' in body).toBe(false)
        expect('placement' in body).toBe(false)
    })

    it('lists only the properties it was given', () => {
        renderEditor('promoter')
        const group = screen.getByRole('group', { name: en.Promotions.targeting })
        expect(within(group).getAllByRole('checkbox').map(c => c.closest('label')?.textContent))
            .toEqual(['Palm Tower', 'Marina Heights'])
    })

    it('explains when no property has been shared yet, and still blocks the save', () => {
        const onSave = renderEditor('promoter', { props: [] })
        fireEvent.change(title(), { target: { value: 'Wash and fold' } })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(screen.getByRole('alert')).toHaveTextContent(en.Promoter.noPropertiesGranted)
        expect(onSave).not.toHaveBeenCalled()
    })

    it('shows the reason a rejected ad was sent back', () => {
        renderEditor('promoter', { ad: rejected })
        expect(screen.getByTestId('ad-rejected-banner')).toHaveTextContent('Image is blurry')
    })

    it('names properties in Arabic when the page is Arabic', () => {
        renderEditor('promoter', { locale: 'ar' })
        expect(screen.getByLabelText('برج النخيل')).toBeInTheDocument()
        // ...and falls back to English where there is no Arabic name.
        expect(screen.getByLabelText('Marina Heights')).toBeInTheDocument()
    })
})

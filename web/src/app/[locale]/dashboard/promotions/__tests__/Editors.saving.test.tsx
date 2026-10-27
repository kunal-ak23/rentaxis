import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { AdEditor } from '../_components/AdEditor'
import { BusinessEditor } from '../_components/BusinessEditor'
import type { PromoAdDTO, PromoAdRequest, PromoBusinessDTO, PromoBusinessRequest } from '@/types/promotion'

/**
 * A double-click on Save created the ad twice (and emailed the administrators
 * twice). Both editors hold Save while a save is in flight and ignore a second
 * click, however quickly it lands.
 */

afterEach(() => {
    cleanup()
})

const business: PromoBusinessDTO = {
    id: 'b-1', nameEn: 'Sparkle Laundry', nameAr: null, logoUrl: null, category: 'SERVICES', phoneE164: null,
    whatsappE164: null, allowedDomains: [], active: true, adCount: 0,
    createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
}
const properties = [{ id: 'p-1', nameEn: 'Palm Tower' }, { id: 'p-2', nameEn: 'Marina Heights' }]

/** A save the test finishes by hand. */
function deferred() {
    let resolve!: () => void
    const promise = new Promise<void>(r => { resolve = r })
    return { promise, resolve }
}

const save = () => screen.getByRole('button', { name: 'Save' })
/** Two clicks before React can re-render: only the editor's own guard can stop the second. */
const clickTwiceInOneTick = () => act(() => { save().click(); save().click() })

describe.each(['admin', 'promoter'] as const)('AdEditor save in flight (%s)', mode => {
    function renderEditor(onSave: (b: PromoAdRequest) => Promise<void>) {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <AdEditor mode={mode} businesses={[business]} properties={properties} ad={null}
                    onSave={onSave} onCancel={vi.fn()} />
            </NextIntlClientProvider>,
        )
        fireEvent.change(screen.getByLabelText('Title (English)'), { target: { value: 'Wash and fold' } })
        if (mode === 'promoter') fireEvent.click(screen.getByLabelText('Palm Tower'))
    }

    it('sends one save for two clicks in the same tick', async () => {
        const d = deferred()
        const onSave = vi.fn(() => d.promise)
        renderEditor(onSave)
        clickTwiceInOneTick()
        expect(onSave).toHaveBeenCalledTimes(1)
        await act(async () => { d.resolve() })
    })

    it('disables Save while the save is pending, and enables it again when it finishes', async () => {
        const d = deferred()
        const onSave = vi.fn(() => d.promise)
        renderEditor(onSave)
        expect(save()).toBeEnabled()
        fireEvent.click(save())
        expect(save()).toBeDisabled()
        // A click on a disabled button, later on, still sends nothing.
        fireEvent.click(save())
        expect(onSave).toHaveBeenCalledTimes(1)
        await act(async () => { d.resolve() })
        await waitFor(() => expect(save()).toBeEnabled())
    })

    it('lets a second, deliberate save through once the first has finished', async () => {
        const onSave = vi.fn(async () => {})
        renderEditor(onSave)
        fireEvent.click(save())
        await waitFor(() => expect(save()).toBeEnabled())
        fireEvent.click(save())
        expect(onSave).toHaveBeenCalledTimes(2)
    })

    it('does not hold Save for a form that failed validation', () => {
        const onSave = vi.fn(async () => {})
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <AdEditor mode={mode} businesses={[business]} properties={properties} ad={null}
                    onSave={onSave} onCancel={vi.fn()} />
            </NextIntlClientProvider>,
        )
        fireEvent.click(save())
        expect(onSave).not.toHaveBeenCalled()
        expect(save()).toBeEnabled()
    })
})

describe('BusinessEditor save in flight', () => {
    function renderEditor(onSave: (b: PromoBusinessRequest) => Promise<void>) {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <BusinessEditor business={null} onSave={onSave} onCancel={vi.fn()} />
            </NextIntlClientProvider>,
        )
        fireEvent.change(screen.getByLabelText('Name (English)'), { target: { value: 'Shine Car Wash' } })
    }

    it('sends one save for two clicks in the same tick', async () => {
        const d = deferred()
        const onSave = vi.fn(() => d.promise)
        renderEditor(onSave)
        clickTwiceInOneTick()
        expect(onSave).toHaveBeenCalledTimes(1)
        await act(async () => { d.resolve() })
    })

    it('disables Save while pending and enables it again after', async () => {
        const d = deferred()
        const onSave = vi.fn(() => d.promise)
        renderEditor(onSave)
        fireEvent.click(save())
        expect(save()).toBeDisabled()
        await act(async () => { d.resolve() })
        await waitFor(() => expect(save()).toBeEnabled())
    })
})

describe('AdEditor — an ad a promoter wrote, edited by an admin', () => {
    const promoterAd = (over: Partial<PromoAdDTO> = {}): PromoAdDTO => ({
        id: 'ad-1', businessId: 'b-1', businessNameEn: 'Sparkle Laundry',
        titleEn: 'Wash and fold', titleAr: null, subtitleEn: null, subtitleAr: null,
        backgroundImageUrl: null, accentColor: null, ctaType: 'NONE', ctaLabelEn: null, ctaLabelAr: null,
        ctaUrl: null, couponCode: null, couponTermsEn: null, couponTermsAr: null,
        startsAt: null, endsAt: null, priority: 1, placement: 'HOME_AND_OFFERS', active: true, propertyIds: ['p-1'],
        impressions: 0, clicks: 0, approvalStatus: 'APPROVED', rejectionReason: null, submittedAt: '2026-10-02T00:00:00Z',
        reviewedAt: null, submittedByName: 'Pat Promoter', createdAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-02T00:00:00Z', ...over,
    })

    function renderAdmin(ad: PromoAdDTO | null) {
        const onSave = vi.fn<(body: PromoAdRequest) => Promise<void>>(async () => {})
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <AdEditor mode="admin" businesses={[business]} properties={properties} ad={ad}
                    onSave={onSave} onCancel={vi.fn()} />
            </NextIntlClientProvider>,
        )
        return onSave
    }

    it('says promoter ads must target a property, in place of "everyone"', () => {
        renderAdmin(promoterAd())
        expect(screen.getByText("Promoter ads must target at least one of the promoter's properties.")).toBeInTheDocument()
        expect(screen.queryByText(en.Promotions.targetingAll)).not.toBeInTheDocument()
        expect(screen.queryByText(en.Promotions.targetingSome)).not.toBeInTheDocument()
    })

    it('refuses to save it with no property, before the server does', () => {
        const onSave = renderAdmin(promoterAd({ propertyIds: [] }))
        fireEvent.click(save())
        expect(screen.getByRole('alert')).toHaveTextContent(en.Promoter.propertyRequired)
        expect(onSave).not.toHaveBeenCalled()
    })

    it('saves it once a property is chosen, keeping an admin\'s priority and placement controls', () => {
        const onSave = renderAdmin(promoterAd({ propertyIds: [] }))
        fireEvent.click(screen.getByLabelText('Marina Heights'))
        fireEvent.click(save())
        expect(onSave).toHaveBeenCalledTimes(1)
        expect(onSave.mock.calls[0][0]).toMatchObject({ propertyIds: ['p-2'], priority: 1, placement: 'HOME_AND_OFFERS' })
        expect(screen.getByLabelText('Priority')).toBeInTheDocument()
    })

    it('leaves an ad only admins have written free to target everyone', () => {
        const onSave = renderAdmin(promoterAd({ submittedByName: null, propertyIds: [] }))
        expect(screen.getByText(en.Promotions.targetingAll)).toBeInTheDocument()
        fireEvent.click(save())
        expect(onSave).toHaveBeenCalledTimes(1)
        expect(onSave.mock.calls[0][0].propertyIds).toEqual([])
    })

    it('still treats it as a promoter ad after the promoter was removed (the name is gone, writtenByPromoter is not)', () => {
        const onSave = renderAdmin(promoterAd({ submittedByName: null, writtenByPromoter: true, propertyIds: [] }))
        expect(screen.getByText("Promoter ads must target at least one of the promoter's properties.")).toBeInTheDocument()
        fireEvent.click(save())
        expect(screen.getByRole('alert')).toHaveTextContent(en.Promoter.propertyRequired)
        expect(onSave).not.toHaveBeenCalled()
        fireEvent.click(screen.getByLabelText('Palm Tower'))
        fireEvent.click(save())
        expect(onSave).toHaveBeenCalledTimes(1)
    })

    it('trusts writtenByPromoter over the name when the backend sends it', () => {
        // A name on an ad the backend says no promoter wrote: an admin ad, free to target everyone.
        const onSave = renderAdmin(promoterAd({ submittedByName: 'Pat Promoter', writtenByPromoter: false, propertyIds: [] }))
        expect(screen.getByText(en.Promotions.targetingAll)).toBeInTheDocument()
        fireEvent.click(save())
        expect(onSave).toHaveBeenCalledTimes(1)
    })

    it('falls back to the name when the backend does not send writtenByPromoter at all', () => {
        const ad = promoterAd({ propertyIds: [] })
        expect('writtenByPromoter' in ad).toBe(false)
        const onSave = renderAdmin(ad)
        fireEvent.click(save())
        expect(onSave).not.toHaveBeenCalled()
        expect(screen.getByRole('alert')).toHaveTextContent(en.Promoter.propertyRequired)
    })

    it('does not apply to a new admin ad', () => {
        const onSave = renderAdmin(null)
        fireEvent.change(screen.getByLabelText('Title (English)'), { target: { value: 'Offer' } })
        fireEvent.click(save())
        expect(onSave).toHaveBeenCalledTimes(1)
    })
})

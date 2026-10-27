import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { AdEditor } from '../_components/AdEditor'
import type { PromoAdDTO, PromoAdRequest, PromoBusinessDTO } from '@/types/promotion'

/** Addendum A: a coupon ad's optional "after copying, open this link" with custom parameters. */

afterEach(() => {
    cleanup()
})

const business: PromoBusinessDTO = {
    id: 'b-1', nameEn: 'QuickWash', nameAr: null, logoUrl: null, category: 'SERVICES',
    phoneE164: null, whatsappE164: null, allowedDomains: ['quickwash.example.test'],
    active: true, adCount: 0, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
}
const properties = [{ id: 'p-1', nameEn: 'Palm Tower' }, { id: 'p-2', nameEn: 'Marina Heights' }]
const LINK = 'https://app.quickwash.example.test/open?src=miftah'

type Save = ReturnType<typeof vi.fn<(body: PromoAdRequest) => void>>

function renderEditor(mode: 'admin' | 'promoter', ad: PromoAdDTO | null = null, locale: 'en' | 'ar' = 'en'): Save {
    const onSave = vi.fn<(body: PromoAdRequest) => void>()
    render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <AdEditor mode={mode} businesses={[business]} properties={properties} ad={ad}
                onSave={onSave} onCancel={vi.fn()} />
        </NextIntlClientProvider>,
    )
    return onSave
}

const L = en.Promotions
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })
const save = () => fireEvent.click(screen.getByRole('button', { name: 'Save' }))
const linkField = () => screen.getByLabelText(L.redirectUrl)

/** A COUPON ad with a title, a code and (for a promoter) the one property they must pick. */
function startCoupon(mode: 'admin' | 'promoter', ad: PromoAdDTO | null = null) {
    const onSave = renderEditor(mode, ad)
    if (!ad) {
        type('Title (English)', 'Free dry-clean')
        type('What happens on tap', 'COUPON')
        type('Coupon code', 'SPRING20')
        // A promoter's new ad already targets all of their properties ("Select all").
    }
    return onSave
}

function addRow(name: string, source: string, value?: string) {
    fireEvent.click(screen.getByRole('button', { name: L.redirectParamAdd }))
    const n = screen.getAllByLabelText(new RegExp(`^${L.redirectParamName} \\d+$`)).length
    type(`${L.redirectParamName} ${n}`, name)
    type(`${L.redirectParamSource} ${n}`, source)
    if (value !== undefined) type(`${L.redirectParamValue} ${n}`, value)
}

describe.each(['admin', 'promoter'] as const)('AdEditor redirect link (%s)', mode => {
    it('is part of the coupon section only', () => {
        renderEditor(mode)
        expect(screen.queryByLabelText(L.redirectUrl)).not.toBeInTheDocument()
        type('What happens on tap', 'WEBSITE')
        expect(screen.queryByLabelText(L.redirectUrl)).not.toBeInTheDocument()
        type('What happens on tap', 'COUPON')
        expect(linkField()).toBeInTheDocument()
    })

    it('sends no redirect when the field is left empty', () => {
        const onSave = startCoupon(mode)
        save()
        expect(onSave).toHaveBeenCalledTimes(1)
        expect(onSave.mock.calls[0][0]).toMatchObject({ ctaType: 'COUPON', couponCode: 'SPRING20', redirectUrl: null, redirectParams: [] })
    })

    it('sends the link and its parameters, a value only for a fixed one', () => {
        const onSave = startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: `  ${LINK}  ` } })
        addRow('  code  ', 'COUPON_CODE')
        addRow('phone', 'RENTER_PHONE')
        addRow('promo', 'STATIC', '  SPRING  ')
        save()

        expect(onSave).toHaveBeenCalledTimes(1)
        const body = onSave.mock.calls[0][0]
        expect(body.redirectUrl).toBe(LINK)
        expect(body.redirectParams).toEqual([
            { name: 'code', source: 'COUPON_CODE' },
            { name: 'phone', source: 'RENTER_PHONE' },
            { name: 'promo', source: 'STATIC', value: 'SPRING' },
        ])
        expect('value' in body.redirectParams![0]).toBe(false)
    })

    it('asks for a fixed value only when the source is a fixed value', () => {
        startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        fireEvent.click(screen.getByRole('button', { name: L.redirectParamAdd }))

        expect(screen.queryByLabelText(`${L.redirectParamValue} 1`)).not.toBeInTheDocument()
        type(`${L.redirectParamSource} 1`, 'STATIC')
        expect(screen.getByLabelText(`${L.redirectParamValue} 1`)).toBeInTheDocument()
        type(`${L.redirectParamSource} 1`, 'RENTER_EMAIL')
        expect(screen.queryByLabelText(`${L.redirectParamValue} 1`)).not.toBeInTheDocument()
    })

    it('drops a typed fixed value when the source is changed away from it', () => {
        const onSave = startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        addRow('promo', 'STATIC', 'SPRING')
        type(`${L.redirectParamSource} 1`, 'UNIT_NUMBER')
        save()
        expect(onSave.mock.calls[0][0].redirectParams).toEqual([{ name: 'promo', source: 'UNIT_NUMBER' }])
    })

    it('shows a live example with sample values, keeping the link\'s own query', () => {
        startCoupon(mode)
        expect(screen.queryByTestId('redirect-example')).not.toBeInTheDocument()
        fireEvent.change(linkField(), { target: { value: LINK } })
        expect(screen.getByTestId('redirect-example')).toHaveTextContent(LINK)

        addRow('code', 'COUPON_CODE')
        addRow('phone', 'RENTER_PHONE')
        addRow('promo', 'STATIC', 'SPRING & SAVE')
        addRow('prop', 'PROPERTY_NAME')
        expect(screen.getByTestId('redirect-example').textContent).toBe(
            `${LINK}&code=SPRING20&phone=%2B971501234567&promo=SPRING+%26+SAVE&prop=Palm+Tower`)
    })

    it('encodes the example the way the server does (Java URLEncoder): a space is +, and ! \' ( ) ~ are percent-encoded', () => {
        startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        addRow('promo', 'STATIC', "it's (50%) off! ~now* é")
        expect(screen.getByTestId('redirect-example').textContent)
            .toBe(`${LINK}&promo=it%27s+%2850%25%29+off%21+%7Enow*+%C3%A9`)
    })

    it('builds the example on a link without a query, and before a fragment', () => {
        startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: 'https://quickwash.example.test/app#open' } })
        addRow('name', 'RENTER_NAME')
        expect(screen.getByTestId('redirect-example').textContent)
            .toBe('https://quickwash.example.test/app?name=Sara+Ahmed#open')
    })

    it('says tenants confirm before details are shared, only when a parameter carries them', () => {
        startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        addRow('code', 'COUPON_CODE')
        addRow('promo', 'STATIC', 'SPRING')
        expect(screen.queryByTestId('redirect-shares-note')).not.toBeInTheDocument()
        addRow('email', 'RENTER_EMAIL')
        expect(screen.getByTestId('redirect-shares-note')).toHaveTextContent(L.redirectSharesDetails)
    })

    it('clears both fields from the request when the tap action is not a coupon', async () => {
        const onSave = startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        addRow('code', 'COUPON_CODE')
        type('What happens on tap', 'NONE')
        save()
        expect(onSave).toHaveBeenCalledTimes(1)
        expect(onSave.mock.calls[0][0]).toMatchObject({ ctaType: 'NONE', couponCode: null, redirectUrl: null, redirectParams: [] })
        // Save is held while a save is in flight; the next one is a second, deliberate save.
        await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())

        // A link that would be refused for a coupon does not block an ad that is not one.
        fireEvent.change(screen.getByLabelText('What happens on tap'), { target: { value: 'COUPON' } })
        fireEvent.change(linkField(), { target: { value: 'http://nope.example.test' } })
        fireEvent.change(screen.getByLabelText('What happens on tap'), { target: { value: 'NONE' } })
        save()
        expect(onSave).toHaveBeenCalledTimes(2)
        expect(onSave.mock.calls[1][0].redirectUrl).toBeNull()
    })

    it('keeps what was typed if the admin switches back to a coupon', () => {
        startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        addRow('code', 'COUPON_CODE')
        type('What happens on tap', 'WEBSITE')
        type('What happens on tap', 'COUPON')
        expect(linkField()).toHaveValue(LINK)
        expect(screen.getByLabelText(`${L.redirectParamName} 1`)).toHaveValue('code')
    })

    it('removes a parameter row and keeps the others', () => {
        const onSave = startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        addRow('a', 'COUPON_CODE')
        addRow('b', 'RENTER_NAME')
        addRow('c', 'UNIT_NUMBER')
        fireEvent.click(screen.getByRole('button', { name: `${L.redirectParamRemove} 2` }))
        save()
        expect(onSave.mock.calls[0][0].redirectParams!.map(p => p.name)).toEqual(['a', 'c'])
    })

    it('allows ten parameters and no more', () => {
        startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        for (let i = 1; i <= 10; i++) addRow(`p${i}`, 'COUPON_CODE')
        expect(screen.getAllByLabelText(new RegExp(`^${L.redirectParamName} \\d+$`))).toHaveLength(10)
        expect(screen.getByRole('button', { name: L.redirectParamAdd })).toBeDisabled()
    })

    describe('validation', () => {
        it.each([
            ['a name with a space', 'my code'],
            ['a name with a symbol', 'code&x'],
            ['an empty name', ''],
            ['a name over 40 characters', 'a'.repeat(41)],
            ['non-ASCII letters', 'رمز'],
        ])('refuses %s', (_label, name) => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: LINK } })
            addRow(name, 'COUPON_CODE')
            save()
            expect(screen.getByText(L.redirectParamNameInvalid)).toBeInTheDocument()
            expect(onSave).not.toHaveBeenCalled()
        })

        it.each(['code', 'Code_1', 'a.b-c', 'a'.repeat(40)])('accepts the name %s', name => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: LINK } })
            addRow(name, 'COUPON_CODE')
            save()
            expect(onSave).toHaveBeenCalledTimes(1)
        })

        it('refuses a name used twice', () => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: LINK } })
            addRow('code', 'COUPON_CODE')
            addRow('code', 'RENTER_NAME')
            save()
            expect(screen.getByText(L.redirectParamNameDuplicate)).toBeInTheDocument()
            expect(onSave).not.toHaveBeenCalled()
        })

        it('refuses a fixed value left empty or over 200 characters', () => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: LINK } })
            addRow('promo', 'STATIC', '   ')
            save()
            expect(screen.getByText(en.Promotions.redirectParamValueRequired.replace('{max}', '200'))).toBeInTheDocument()
            type(`${L.redirectParamValue} 1`, 'x'.repeat(201))
            // maxLength on the input stops typing past 200 in a browser; jsdom does not, so the rule is also checked on save.
            save()
            expect(onSave).not.toHaveBeenCalled()
        })

        it('refuses parameters without a link', () => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: LINK } })
            addRow('code', 'COUPON_CODE')
            fireEvent.change(linkField(), { target: { value: '' } })
            save()
            expect(screen.getByText(L.redirectParamsNeedUrl)).toBeInTheDocument()
            expect(onSave).not.toHaveBeenCalled()
        })

        it('refuses a link that is not https, off the allowlist, with userinfo, or too long', () => {
            const onSave = startCoupon(mode)
            const cases: [string, string][] = [
                ['http://app.quickwash.example.test/x', L.httpsRequired],
                ['https://evil.example.test/x', L.domainNotAllowed],
                ['https://bank.example.test@app.quickwash.example.test/x', L.domainNotAllowed],
                [`https://app.quickwash.example.test/${'a'.repeat(2048)}`, L.redirectUrlTooLong.replace('{max}', '2048')],
            ]
            for (const [value, message] of cases) {
                fireEvent.change(linkField(), { target: { value } })
                save()
                expect(screen.getByText(message), value).toBeInTheDocument()
            }
            expect(onSave).not.toHaveBeenCalled()
        })

        it('accepts a subdomain of an allowed domain', () => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: 'https://deep.app.quickwash.example.test/x' } })
            save()
            expect(onSave).toHaveBeenCalledTimes(1)
        })
    })

    it('loads an ad\'s saved link and parameters for editing', () => {
        const ad = {
            id: 'ad-1', businessId: 'b-1', businessNameEn: 'QuickWash', titleEn: 'Free dry-clean', titleAr: null,
            subtitleEn: null, subtitleAr: null, backgroundImageUrl: null, accentColor: null, ctaType: 'COUPON',
            ctaLabelEn: null, ctaLabelAr: null, ctaUrl: null, couponCode: 'SPRING20', couponTermsEn: null, couponTermsAr: null,
            redirectUrl: LINK,
            redirectParams: [{ name: 'phone', source: 'RENTER_PHONE' }, { name: 'promo', source: 'STATIC', value: 'SPRING' }],
            startsAt: null, endsAt: null, priority: 3, placement: 'OFFERS_ONLY', active: true, propertyIds: ['p-1'],
            impressions: 0, clicks: 0, approvalStatus: 'APPROVED', rejectionReason: null, submittedAt: null,
            reviewedAt: null, submittedByName: null, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
        } as PromoAdDTO
        const onSave = startCoupon(mode, ad)

        expect(linkField()).toHaveValue(LINK)
        expect(screen.getByLabelText(`${L.redirectParamName} 1`)).toHaveValue('phone')
        expect(screen.getByLabelText(`${L.redirectParamSource} 1`)).toHaveValue('RENTER_PHONE')
        expect(screen.getByLabelText(`${L.redirectParamValue} 2`)).toHaveValue('SPRING')
        save()
        expect(onSave.mock.calls[0][0].redirectParams).toEqual(ad.redirectParams)
        expect(onSave.mock.calls[0][0].redirectUrl).toBe(LINK)
    })

    it('shows the sources in Arabic', () => {
        renderEditor(mode, null, 'ar')
        fireEvent.change(screen.getByLabelText(ar.Promotions.ctaType), { target: { value: 'COUPON' } })
        fireEvent.change(screen.getByLabelText(ar.Promotions.redirectUrl), { target: { value: LINK } })
        fireEvent.click(screen.getByRole('button', { name: ar.Promotions.redirectParamAdd }))
        const select = screen.getByLabelText(`${ar.Promotions.redirectParamSource} 1`) as HTMLSelectElement
        expect(Array.from(select.options).map(o => o.textContent)).toEqual([
            ar.Promotions.redirectSourceCOUPON_CODE, ar.Promotions.redirectSourceRENTER_NAME,
            ar.Promotions.redirectSourceRENTER_PHONE, ar.Promotions.redirectSourceRENTER_EMAIL,
            ar.Promotions.redirectSourcePROPERTY_NAME, ar.Promotions.redirectSourceUNIT_NUMBER,
            ar.Promotions.redirectSourceSTATIC,
        ])
    })
})

import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { AdEditor } from '../_components/AdEditor'
import { RENTER_DATA_SOURCES, sharesRenterData } from '../_components/RedirectLinkFields'
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
const androidField = () => screen.getByLabelText(L.redirectUrlAndroid)
const iosField = () => screen.getByLabelText(L.redirectUrlIos)
const ANDROID = 'https://app.quickwash.example.test/android?src=miftah'
const IOS = 'https://quickwash.example.test/ios'

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

    it('offers the property and unit id sources and flags them as renter data', () => {
        const onSave = startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        addRow('pid', 'PROPERTY_ID')
        addRow('uid', 'UNIT_ID')
        expect(screen.getByTestId('redirect-example').textContent).toContain('pid=3f2b8c1e-0000-4000-8000-000000000001')
        expect(screen.getByTestId('redirect-example').textContent).toContain('uid=3f2b8c1e-0000-4000-8000-000000000002')
        expect(RENTER_DATA_SOURCES).toEqual(expect.arrayContaining(['PROPERTY_ID', 'UNIT_ID']))
        expect(sharesRenterData([{ source: 'UNIT_ID' }])).toBe(true)
        save()
        expect(onSave.mock.calls[0][0].redirectParams).toEqual([
            { name: 'pid', source: 'PROPERTY_ID' },
            { name: 'uid', source: 'UNIT_ID' },
        ])
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
            `${LINK}&code=SPRING20&phone=%2B971501234567&promo=SPRING%20%26%20SAVE&prop=Palm%20Tower`)
    })

    it('encodes the example the way the server does (URLEncoder, then + as %20): a space is %20, and ! \' ( ) ~ are percent-encoded', () => {
        startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: LINK } })
        addRow('promo', 'STATIC', "it's (50%) off! ~now* é")
        expect(screen.getByTestId('redirect-example').textContent)
            .toBe(`${LINK}&promo=it%27s%20%2850%25%29%20off%21%20%7Enow*%20%C3%A9`)
    })

    it('builds the example on a link without a query, and before a fragment', () => {
        startCoupon(mode)
        fireEvent.change(linkField(), { target: { value: 'https://quickwash.example.test/app#open' } })
        addRow('name', 'RENTER_NAME')
        expect(screen.getByTestId('redirect-example').textContent)
            .toBe('https://quickwash.example.test/app?name=Sara%20Ahmed#open')
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

    /** Addendum C: an Android and an iPhone link may replace the default link on that phone. */
    describe('platform links', () => {
        const adWithPlatformLinks = () => ({
            id: 'ad-2', businessId: 'b-1', businessNameEn: 'QuickWash', titleEn: 'Free dry-clean', titleAr: null,
            subtitleEn: null, subtitleAr: null, backgroundImageUrl: null, accentColor: null, ctaType: 'COUPON',
            ctaLabelEn: null, ctaLabelAr: null, ctaUrl: null, couponCode: 'SPRING20', couponTermsEn: null, couponTermsAr: null,
            redirectUrl: LINK, redirectUrlAndroid: ANDROID, redirectUrlIos: IOS,
            redirectParams: [{ name: 'phone', source: 'RENTER_PHONE' }],
            startsAt: null, endsAt: null, priority: 3, placement: 'OFFERS_ONLY', active: true, propertyIds: ['p-1'],
            impressions: 0, clicks: 0, approvalStatus: 'APPROVED', rejectionReason: null, submittedAt: null,
            reviewedAt: null, submittedByName: null, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
        }) as PromoAdDTO

        it('adds an Android and an iPhone field under the main link, in the coupon section only', () => {
            renderEditor(mode)
            expect(screen.queryByLabelText(L.redirectUrlAndroid)).not.toBeInTheDocument()
            expect(screen.queryByLabelText(L.redirectUrlIos)).not.toBeInTheDocument()
            type('What happens on tap', 'WEBSITE')
            expect(screen.queryByLabelText(L.redirectUrlAndroid)).not.toBeInTheDocument()
            type('What happens on tap', 'COUPON')

            expect(L.redirectUrlAndroid).toBe('Android link')
            expect(L.redirectUrlIos).toBe('iPhone link')
            // Under the main field, in this order.
            const order = [linkField(), androidField(), iosField()]
            for (let i = 1; i < order.length; i++) {
                expect(order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
            }
            expect(androidField()).toHaveValue('')
            expect(iosField()).toHaveValue('')
            // The hint says what an empty field does, and describes both fields.
            const hint = screen.getByText(L.redirectPlatformHint)
            expect(hint).toHaveTextContent('Leave empty to use the link above on that phone. The same parameters are added.')
            expect(androidField().getAttribute('aria-describedby')).toContain(hint.id)
            expect(iosField().getAttribute('aria-describedby')).toContain(hint.id)
        })

        it('sends both platform links as null when they are left empty or blank', () => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: LINK } })
            fireEvent.change(androidField(), { target: { value: '   ' } })
            save()
            expect(onSave).toHaveBeenCalledTimes(1)
            const body = onSave.mock.calls[0][0]
            expect(body.redirectUrl).toBe(LINK)
            expect(body.redirectUrlAndroid).toBeNull()
            expect(body.redirectUrlIos).toBeNull()
        })

        it('sends the Android link, trimmed, as redirectUrlAndroid', () => {
            const onSave = startCoupon(mode)
            fireEvent.change(androidField(), { target: { value: `  ${ANDROID}  ` } })
            save()
            expect(onSave).toHaveBeenCalledTimes(1)
            expect(onSave.mock.calls[0][0]).toMatchObject({
                ctaType: 'COUPON', redirectUrl: null, redirectUrlAndroid: ANDROID, redirectUrlIos: null,
            })
        })

        it('sends the iPhone link, trimmed, as redirectUrlIos', () => {
            const onSave = startCoupon(mode)
            fireEvent.change(iosField(), { target: { value: `  ${IOS}  ` } })
            save()
            expect(onSave).toHaveBeenCalledTimes(1)
            expect(onSave.mock.calls[0][0]).toMatchObject({
                ctaType: 'COUPON', redirectUrl: null, redirectUrlAndroid: null, redirectUrlIos: IOS,
            })
        })

        it('sends all three links, each in its own field, with the shared parameters', () => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: LINK } })
            fireEvent.change(androidField(), { target: { value: ANDROID } })
            fireEvent.change(iosField(), { target: { value: IOS } })
            addRow('code', 'COUPON_CODE')
            save()
            expect(onSave.mock.calls[0][0]).toMatchObject({
                redirectUrl: LINK, redirectUrlAndroid: ANDROID, redirectUrlIos: IOS,
                redirectParams: [{ name: 'code', source: 'COUPON_CODE' }],
            })
        })

        it('clears all three links and the parameters when the tap action is not a coupon', async () => {
            const onSave = startCoupon(mode)
            fireEvent.change(linkField(), { target: { value: LINK } })
            fireEvent.change(androidField(), { target: { value: ANDROID } })
            fireEvent.change(iosField(), { target: { value: IOS } })
            addRow('code', 'COUPON_CODE')
            type('What happens on tap', 'NONE')
            save()
            expect(onSave).toHaveBeenCalledTimes(1)
            expect(onSave.mock.calls[0][0]).toMatchObject({
                ctaType: 'NONE', redirectUrl: null, redirectUrlAndroid: null, redirectUrlIos: null, redirectParams: [],
            })
            await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled())

            // A platform link that would be refused for a coupon does not block an ad that is not one.
            type('What happens on tap', 'COUPON')
            fireEvent.change(androidField(), { target: { value: 'http://nope.example.test' } })
            fireEvent.change(iosField(), { target: { value: 'https://evil.example.test/x' } })
            type('What happens on tap', 'NONE')
            save()
            expect(onSave).toHaveBeenCalledTimes(2)
            expect(onSave.mock.calls[1][0]).toMatchObject({
                ctaType: 'NONE', redirectUrl: null, redirectUrlAndroid: null, redirectUrlIos: null, redirectParams: [],
            })
        })

        it('keeps what was typed in the platform fields if the admin switches back to a coupon', () => {
            startCoupon(mode)
            fireEvent.change(androidField(), { target: { value: ANDROID } })
            fireEvent.change(iosField(), { target: { value: IOS } })
            type('What happens on tap', 'WEBSITE')
            type('What happens on tap', 'COUPON')
            expect(androidField()).toHaveValue(ANDROID)
            expect(iosField()).toHaveValue(IOS)
        })

        describe.each([
            ['Android', 'redirectUrlAndroid', androidField, iosField],
            ['iPhone', 'redirectUrlIos', iosField, androidField],
        ] as const)('validation of the %s link', (_name, errorKey, field, otherField) => {
            const errorOf = () => document.getElementById(`err-${errorKey}`)

            it.each([
                ['that is not https', 'http://app.quickwash.example.test/x', () => L.httpsRequired],
                ['that is off the allowlist', 'https://evil.example.test/x', () => L.domainNotAllowed],
                ['with userinfo', 'https://bank.example.test@app.quickwash.example.test/x', () => L.domainNotAllowed],
                ['over 2048 characters', `https://app.quickwash.example.test/${'a'.repeat(2048)}`,
                    () => L.redirectUrlTooLong.replace('{max}', '2048')],
            ])('refuses a link %s, on that field and not the other', (_label, value, message) => {
                const onSave = startCoupon(mode)
                fireEvent.change(field(), { target: { value } })
                save()
                expect(onSave).not.toHaveBeenCalled()
                expect(errorOf()).toHaveTextContent(message())
                expect(field()).toHaveAttribute('aria-invalid', 'true')
                expect(field().getAttribute('aria-describedby')).toContain(`err-${errorKey}`)
                expect(otherField()).not.toHaveAttribute('aria-invalid')
                expect(linkField()).not.toHaveAttribute('aria-invalid')
            })

            it('accepts a subdomain of an allowed domain, and clears the error once fixed', () => {
                const onSave = startCoupon(mode)
                fireEvent.change(field(), { target: { value: 'http://app.quickwash.example.test/x' } })
                save()
                expect(onSave).not.toHaveBeenCalled()
                expect(errorOf()).not.toBeNull()

                fireEvent.change(field(), { target: { value: 'https://deep.app.quickwash.example.test/x' } })
                save()
                expect(onSave).toHaveBeenCalledTimes(1)
                expect(errorOf()).toBeNull()
            })

            it('is checked even when the other links are fine', () => {
                const onSave = startCoupon(mode)
                fireEvent.change(linkField(), { target: { value: LINK } })
                fireEvent.change(otherField(), { target: { value: IOS } })
                fireEvent.change(field(), { target: { value: 'https://evil.example.test/x' } })
                save()
                expect(onSave).not.toHaveBeenCalled()
                expect(errorOf()).toHaveTextContent(L.domainNotAllowed)
            })
        })

        describe('parameters', () => {
            it.each([
                ['Android', androidField, ANDROID],
                ['iPhone', iosField, IOS],
            ] as const)('are allowed with only the %s link set', (_name, field, link) => {
                const onSave = startCoupon(mode)
                // No link at all: nothing to add parameters to yet.
                expect(screen.queryByText(L.redirectParams)).not.toBeInTheDocument()
                fireEvent.change(field(), { target: { value: link } })
                expect(screen.getByText(L.redirectParams)).toBeInTheDocument()
                expect(screen.getByRole('button', { name: L.redirectParamAdd })).toBeEnabled()
                addRow('code', 'COUPON_CODE')
                addRow('promo', 'STATIC', 'SPRING')
                save()

                expect(onSave).toHaveBeenCalledTimes(1)
                const body = onSave.mock.calls[0][0]
                expect(body.redirectUrl).toBeNull()
                expect(body.redirectParams).toEqual([
                    { name: 'code', source: 'COUPON_CODE' },
                    { name: 'promo', source: 'STATIC', value: 'SPRING' },
                ])
            })

            it('are blocked, with the list-level error, when every link is empty', () => {
                const onSave = startCoupon(mode)
                fireEvent.change(androidField(), { target: { value: ANDROID } })
                addRow('code', 'COUPON_CODE')
                fireEvent.change(androidField(), { target: { value: '' } })
                // The rows stay on screen, but the button to add more is off.
                expect(screen.getByRole('button', { name: L.redirectParamAdd })).toBeDisabled()
                save()
                expect(screen.getByText(L.redirectParamsNeedUrl)).toBeInTheDocument()
                expect(L.redirectParamsNeedUrl).toBe('Enter at least one link before adding parameters.')
                expect(onSave).not.toHaveBeenCalled()
            })

            it('survive the default link being cleared while a platform link is still set', () => {
                const onSave = startCoupon(mode)
                fireEvent.change(linkField(), { target: { value: LINK } })
                fireEvent.change(iosField(), { target: { value: IOS } })
                addRow('code', 'COUPON_CODE')
                fireEvent.change(linkField(), { target: { value: '' } })
                save()
                expect(screen.queryByText(L.redirectParamsNeedUrl)).not.toBeInTheDocument()
                expect(onSave).toHaveBeenCalledTimes(1)
                expect(onSave.mock.calls[0][0]).toMatchObject({
                    redirectUrl: null, redirectUrlIos: IOS, redirectParams: [{ name: 'code', source: 'COUPON_CODE' }],
                })
            })

            it('are blocked by a whitespace-only link, which counts as empty', () => {
                const onSave = startCoupon(mode)
                fireEvent.change(linkField(), { target: { value: LINK } })
                addRow('code', 'COUPON_CODE')
                fireEvent.change(linkField(), { target: { value: '' } })
                fireEvent.change(iosField(), { target: { value: '   ' } })
                save()
                expect(screen.getByText(L.redirectParamsNeedUrl)).toBeInTheDocument()
                expect(onSave).not.toHaveBeenCalled()
            })
        })

        describe('live example', () => {
            const tail = (el: HTMLElement) => el.textContent!.replace(/^[^?]*\?(?:[^&]*&)?/, '')

            it('shows one labelled example per link that is set, none for a link that is not', () => {
                startCoupon(mode)
                expect(screen.queryByTestId('redirect-example')).not.toBeInTheDocument()
                expect(screen.queryByTestId('redirect-example-android')).not.toBeInTheDocument()
                expect(screen.queryByTestId('redirect-example-ios')).not.toBeInTheDocument()

                fireEvent.change(androidField(), { target: { value: ANDROID } })
                expect(screen.queryByTestId('redirect-example')).not.toBeInTheDocument()
                expect(screen.getByTestId('redirect-example-android')).toHaveTextContent(ANDROID)
                expect(screen.queryByTestId('redirect-example-ios')).not.toBeInTheDocument()

                fireEvent.change(iosField(), { target: { value: IOS } })
                fireEvent.change(linkField(), { target: { value: LINK } })
                expect(screen.getByTestId('redirect-example')).toHaveTextContent(LINK)
                expect(screen.getByTestId('redirect-example-android')).toHaveTextContent(ANDROID)
                expect(screen.getByTestId('redirect-example-ios')).toHaveTextContent(IOS)

                // Each is labelled with the link it stands for.
                for (const [testId, label] of [
                    ['redirect-example', L.redirectLinkDefault],
                    ['redirect-example-android', L.redirectLinkAndroid],
                    ['redirect-example-ios', L.redirectLinkIos],
                ]) {
                    expect(screen.getByTestId(testId).previousElementSibling).toHaveTextContent(label)
                }
                expect([L.redirectLinkDefault, L.redirectLinkAndroid, L.redirectLinkIos]).toEqual(['Default', 'Android', 'iPhone'])
            })

            it('appends the same parameters to each link, built by the same encoder', () => {
                startCoupon(mode)
                fireEvent.change(linkField(), { target: { value: LINK } })
                fireEvent.change(androidField(), { target: { value: ANDROID } })
                fireEvent.change(iosField(), { target: { value: IOS } })
                addRow('code', 'COUPON_CODE')
                addRow('phone', 'RENTER_PHONE')
                addRow('promo', 'STATIC', "it's (50%) off! ~now* é")
                addRow('prop', 'PROPERTY_NAME')

                const encoded = 'code=SPRING20&phone=%2B971501234567&promo=it%27s%20%2850%25%29%20off%21%20%7Enow*%20%C3%A9&prop=Palm%20Tower'
                // A link that already has a query gets '&', one without gets '?'.
                expect(screen.getByTestId('redirect-example').textContent).toBe(`${LINK}&${encoded}`)
                expect(screen.getByTestId('redirect-example-android').textContent).toBe(`${ANDROID}&${encoded}`)
                expect(screen.getByTestId('redirect-example-ios').textContent).toBe(`${IOS}?${encoded}`)
                expect(tail(screen.getByTestId('redirect-example-android'))).toBe(encoded)
            })

            it('still shows the shares-details note once, whichever link is set', () => {
                startCoupon(mode)
                fireEvent.change(iosField(), { target: { value: IOS } })
                addRow('email', 'RENTER_EMAIL')
                expect(screen.getAllByTestId('redirect-shares-note')).toHaveLength(1)
            })
        })

        it('loads an ad\'s saved platform links for editing, and saves them back unchanged', () => {
            const ad = adWithPlatformLinks()
            const onSave = startCoupon(mode, ad)

            expect(linkField()).toHaveValue(LINK)
            expect(androidField()).toHaveValue(ANDROID)
            expect(iosField()).toHaveValue(IOS)
            expect(screen.getByTestId('redirect-example-android')).toHaveTextContent(`${ANDROID}&phone=%2B971501234567`)
            save()
            expect(onSave.mock.calls[0][0]).toMatchObject({
                redirectUrl: LINK, redirectUrlAndroid: ANDROID, redirectUrlIos: IOS, redirectParams: ad.redirectParams,
            })
        })

        it('loads an ad that has only a platform link, with its parameters', () => {
            const ad = { ...adWithPlatformLinks(), redirectUrl: null, redirectUrlIos: null } as PromoAdDTO
            const onSave = startCoupon(mode, ad)

            expect(linkField()).toHaveValue('')
            expect(androidField()).toHaveValue(ANDROID)
            expect(iosField()).toHaveValue('')
            expect(screen.getByLabelText(`${L.redirectParamName} 1`)).toHaveValue('phone')
            save()
            expect(onSave.mock.calls[0][0]).toMatchObject({
                redirectUrl: null, redirectUrlAndroid: ANDROID, redirectUrlIos: null,
                redirectParams: [{ name: 'phone', source: 'RENTER_PHONE' }],
            })
        })

        it('opens blank platform fields on an ad from an older backend that has not got them', () => {
            const older = adWithPlatformLinks()
            delete older.redirectUrlAndroid
            delete older.redirectUrlIos
            const onSave = startCoupon(mode, older)
            expect(linkField()).toHaveValue(LINK)
            expect(androidField()).toHaveValue('')
            expect(iosField()).toHaveValue('')
            save()
            expect(onSave.mock.calls[0][0]).toMatchObject({ redirectUrl: LINK, redirectUrlAndroid: null, redirectUrlIos: null })
        })

        it('has the labels, hint and example labels in Arabic', () => {
            renderEditor(mode, null, 'ar')
            fireEvent.change(screen.getByLabelText(ar.Promotions.ctaType), { target: { value: 'COUPON' } })
            fireEvent.change(screen.getByLabelText(ar.Promotions.redirectUrlAndroid), { target: { value: ANDROID } })
            fireEvent.change(screen.getByLabelText(ar.Promotions.redirectUrlIos), { target: { value: IOS } })
            expect(ar.Promotions.redirectUrlAndroid).toBe('رابط أندرويد')
            expect(ar.Promotions.redirectUrlIos).toBe('رابط آيفون')
            expect(screen.getByText(ar.Promotions.redirectPlatformHint)).toBeInTheDocument()
            expect(screen.getByTestId('redirect-example-android').previousElementSibling).toHaveTextContent(ar.Promotions.redirectLinkAndroid)
            expect(screen.getByTestId('redirect-example-ios').previousElementSibling).toHaveTextContent(ar.Promotions.redirectLinkIos)
        })
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
            ar.Promotions.redirectSourcePROPERTY_ID, ar.Promotions.redirectSourceUNIT_ID,
            ar.Promotions.redirectSourceSTATIC,
        ])
    })
})

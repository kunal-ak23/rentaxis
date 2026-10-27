import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { RedirectSummary } from '../_components/RedirectSummary'
import { urlEncodeLikeServer } from '../_components/RedirectLinkFields'

afterEach(cleanup)

const ad = {
    redirectUrl: 'https://app.quickwash.example.test/open?src=miftah',
    redirectParams: [
        { name: 'phone', source: 'RENTER_PHONE' as const },
        { name: 'promo', source: 'STATIC' as const, value: 'SPRING' },
    ],
}

function renderSummary(locale: 'en' | 'ar') {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <RedirectSummary ad={ad} />
        </NextIntlClientProvider>,
    )
}

describe('RedirectSummary direction', () => {
    it.each(['en', 'ar'] as const)('isolates only the URL and the parameter names as left-to-right (%s)', locale => {
        renderSummary(locale)
        const summary = screen.getByTestId('redirect-summary')

        // Nothing but a <bdi> is forced left-to-right: the sentences follow the page.
        const ltr = Array.from(summary.querySelectorAll('[dir="ltr"]'))
        expect(ltr.map(el => el.tagName)).toEqual(['BDI', 'BDI', 'BDI'])
        expect(ltr.map(el => el.textContent)).toEqual([ad.redirectUrl, 'phone', 'promo'])
        expect(summary.getAttribute('dir')).toBeNull()
        for (const el of summary.querySelectorAll('span')) expect(el.getAttribute('dir')).toBeNull()
    })

    it('keeps the translated words outside the isolates, in Arabic', () => {
        renderSummary('ar')
        const summary = screen.getByTestId('redirect-summary')
        expect(summary).toHaveTextContent(
            ar.Promotions.redirectSummary.replace('{label}', ar.Promotions.redirectLinkDefault).replace('<url></url>', ad.redirectUrl))
        expect(summary).toHaveTextContent(`(${ar.Promotions.redirectSourceRENTER_PHONE})`)
        for (const bdi of summary.querySelectorAll('bdi')) expect(bdi.textContent).toMatch(/^[\x20-\x7e]+$/)
    })

    it('still reads as one sentence each', () => {
        renderSummary('en')
        const summary = screen.getByTestId('redirect-summary')
        expect(summary).toHaveTextContent('After copy (Default): https://app.quickwash.example.test/open?src=miftah')
        expect(summary).toHaveTextContent("Parameters: phone (Tenant's phone), promo (Fixed value)")
        expect(summary).toHaveTextContent('Shares tenant details')
    })
})

describe('RedirectSummary with platform links (Addendum C)', () => {
    const android = 'https://play.quickwash.example.test/open?id=app.quickwash'
    const ios = 'https://apps.quickwash.example.test/quickwash/id1'
    const all = { ...ad, redirectUrlAndroid: android, redirectUrlIos: ios }

    function renderAd(value: Parameters<typeof RedirectSummary>[0]['ad'], locale: 'en' | 'ar' = 'en') {
        return render(
            <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
                <RedirectSummary ad={value} />
            </NextIntlClientProvider>,
        )
    }
    const lines = () => ['redirectUrl', 'redirectUrlAndroid', 'redirectUrlIos']
        .map(k => screen.queryByTestId(`redirect-summary-${k}`))

    it('lists each set link on its own line, labelled Default, Android and iPhone, in that order', () => {
        renderAd(all)
        const [d, a, i] = lines()
        expect(d).toHaveTextContent(`After copy (Default): ${ad.redirectUrl}`)
        expect(a).toHaveTextContent(`After copy (Android): ${android}`)
        expect(i).toHaveTextContent(`After copy (iPhone): ${ios}`)
        const order = [d!, a!, i!]
        for (let n = 1; n < order.length; n++) {
            expect(order[n - 1].compareDocumentPosition(order[n]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
        }
    })

    it('shows only the links that are set', () => {
        renderAd({ redirectUrl: null, redirectUrlAndroid: android, redirectUrlIos: null, redirectParams: [] })
        const [d, a, i] = lines()
        expect(d).toBeNull()
        expect(a).toHaveTextContent(`After copy (Android): ${android}`)
        expect(i).toBeNull()
    })

    it.each([
        ['iPhone only', { redirectUrl: null, redirectUrlAndroid: null, redirectUrlIos: ios }],
        ['default and iPhone', { redirectUrl: ad.redirectUrl, redirectUrlAndroid: '', redirectUrlIos: ios }],
    ])('does not show an empty or missing link (%s)', (_label, links) => {
        renderAd({ ...links, redirectParams: [] })
        const set = lines().filter(Boolean)
        expect(set).toHaveLength(Object.values(links).filter(Boolean).length)
        expect(screen.getByTestId('redirect-summary').textContent).not.toContain('Android')
    })

    it('renders nothing when no link is set, whatever the parameters', () => {
        renderAd({ redirectUrl: null, redirectUrlAndroid: null, redirectUrlIos: null, redirectParams: ad.redirectParams })
        expect(screen.queryByTestId('redirect-summary')).not.toBeInTheDocument()
        cleanup()
        renderAd({ redirectParams: [] })
        expect(screen.queryByTestId('redirect-summary')).not.toBeInTheDocument()
    })

    it('keeps the parameter list and the shares-details badge, once, beside several links', () => {
        renderAd(all)
        const summary = screen.getByTestId('redirect-summary')
        expect(summary).toHaveTextContent("Parameters: phone (Tenant's phone), promo (Fixed value)")
        expect(summary.textContent!.match(/Shares tenant details/g)).toHaveLength(1)
    })

    it('has no badge when the parameters carry no tenant details', () => {
        renderAd({ redirectUrlAndroid: android, redirectParams: [{ name: 'code', source: 'COUPON_CODE' }] })
        expect(screen.getByTestId('redirect-summary')).toHaveTextContent('Parameters: code (Coupon code)')
        expect(screen.getByTestId('redirect-summary')).not.toHaveTextContent('Shares tenant details')
    })

    it.each(['en', 'ar'] as const)('isolates every URL and parameter name as left-to-right, and nothing else (%s)', locale => {
        renderAd(all, locale)
        const summary = screen.getByTestId('redirect-summary')
        const ltr = Array.from(summary.querySelectorAll('[dir="ltr"]'))
        expect(ltr.map(el => el.tagName)).toEqual(['BDI', 'BDI', 'BDI', 'BDI', 'BDI'])
        expect(ltr.map(el => el.textContent)).toEqual([ad.redirectUrl, android, ios, 'phone', 'promo'])
        expect(summary.getAttribute('dir')).toBeNull()
        for (const el of summary.querySelectorAll('span')) expect(el.getAttribute('dir')).toBeNull()
    })

    it('labels the links in Arabic outside the isolates', () => {
        renderAd(all, 'ar')
        const summary = screen.getByTestId('redirect-summary')
        const arLine = (label: string, url: string) =>
            ar.Promotions.redirectSummary.replace('{label}', label).replace('<url></url>', url)
        expect(screen.getByTestId('redirect-summary-redirectUrl')).toHaveTextContent(arLine(ar.Promotions.redirectLinkDefault, ad.redirectUrl))
        expect(screen.getByTestId('redirect-summary-redirectUrlAndroid')).toHaveTextContent(arLine('أندرويد', android))
        expect(screen.getByTestId('redirect-summary-redirectUrlIos')).toHaveTextContent(arLine('آيفون', ios))
        for (const bdi of summary.querySelectorAll('bdi')) expect(bdi.textContent).toMatch(/^[\x20-\x7e]+$/)
    })
})

describe('urlEncodeLikeServer (the server builds the link with URLEncoder.encode(value, UTF_8), + as %20)', () => {
    it.each([
        ['a b', 'a%20b'],
        ['a+b', 'a%2Bb'],
        ["it's", 'it%27s'],
        ['(x)', '%28x%29'],
        ['wow!', 'wow%21'],
        ['~tilde', '%7Etilde'],
        ['keep.-*_', 'keep.-*_'],
        ['a/b?c=d&e#f%g', 'a%2Fb%3Fc%3Dd%26e%23f%25g'],
        ['é', '%C3%A9'],
        ['😀', '%F0%9F%98%80'],
        ['سارة', '%D8%B3%D8%A7%D8%B1%D8%A9'],
        ['A-Za-z0-9', 'A-Za-z0-9'],
    ])('%j -> %s', (input, expected) => {
        expect(urlEncodeLikeServer(input)).toBe(expected)
    })
})

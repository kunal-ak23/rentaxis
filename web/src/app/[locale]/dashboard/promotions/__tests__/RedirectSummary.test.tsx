import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { RedirectSummary } from '../_components/RedirectSummary'
import { urlEncodeLikeJava } from '../_components/RedirectLinkFields'

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
        expect(summary).toHaveTextContent(`${ar.Promotions.redirectSummary.replace('<url></url>', ad.redirectUrl)}`)
        expect(summary).toHaveTextContent(`(${ar.Promotions.redirectSourceRENTER_PHONE})`)
        for (const bdi of summary.querySelectorAll('bdi')) expect(bdi.textContent).toMatch(/^[\x20-\x7e]+$/)
    })

    it('still reads as one sentence each', () => {
        renderSummary('en')
        const summary = screen.getByTestId('redirect-summary')
        expect(summary).toHaveTextContent('After copy: https://app.quickwash.example.test/open?src=miftah')
        expect(summary).toHaveTextContent("Parameters: phone (Tenant's phone), promo (Fixed value)")
        expect(summary).toHaveTextContent('Shares tenant details')
    })
})

describe('urlEncodeLikeJava (the server builds the link with URLEncoder.encode(value, UTF_8))', () => {
    it.each([
        ['a b', 'a+b'],
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
        expect(urlEncodeLikeJava(input)).toBe(expected)
    })
})

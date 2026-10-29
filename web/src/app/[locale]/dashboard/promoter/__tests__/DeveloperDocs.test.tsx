import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'

const session = vi.hoisted(() => ({ role: 'PROMOTER' as string }))
vi.mock('next-auth/react', () => ({
    useSession: () => ({ data: { user: { id: 'u', role: session.role } }, status: 'authenticated' }),
}))
vi.mock('@/i18n/routing', () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
}))

import DeveloperDocsPage from '../developer/page'

function renderDocs(locale: 'en' | 'ar' = 'en') {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <DeveloperDocsPage />
        </NextIntlClientProvider>,
    )
}

afterEach(() => { cleanup(); session.role = 'PROMOTER' })

describe('promoter developer docs page', () => {
    it('has a heading for every section', () => {
        renderDocs()
        const headings = screen.getAllByRole('heading', { level: 2 }).map(h => h.textContent)
        for (const key of ['overviewTitle', 'keyTitle', 'baseUrlTitle', 'authTitle', 'pingTitle', 'countTitle',
            'unitsTitle', 'syncTitle', 'validateTitle', 'errorsTitle', 'securityTitle']) {
            expect(headings).toContain((en.PromoterDocs as Record<string, string>)[key])
        }
    })

    it('shows curl samples with the key header and the three paths, rendered left-to-right', () => {
        const { container } = renderDocs()
        const text = Array.from(container.querySelectorAll('pre')).map(p => p.textContent).join('\n')
        expect(text).toContain('X-Api-Key')
        expect(text).toContain('/api/v1/partner/promoter/ping')
        expect(text).toContain('/api/v1/partner/promoter/units/count')
        expect(text).toContain('/api/v1/partner/promoter/units?')
        expect(text).not.toMatch(/mfk_live_(?!EXAMPLE)/)
        for (const pre of Array.from(container.querySelectorAll('pre'))) expect(pre.getAttribute('dir')).toBe('ltr')
    })

    it('uses the page origin as the base URL', () => {
        renderDocs()
        expect(screen.getAllByText(new RegExp(`${window.location.origin}/api/v1/partner/promoter`)).length).toBeGreaterThan(0)
    })

    it('renders in Arabic', () => {
        renderDocs('ar')
        expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(ar.PromoterDocs.title)
    })

    it('refuses non-promoters', () => {
        session.role = 'TENANT_ADMIN'
        renderDocs()
        expect(screen.getByText(en.Promoter.noAccess)).toBeTruthy()
        expect(within(document.body).queryAllByRole('heading', { level: 2 })).toHaveLength(0)
    })

    it('keeps PromoterDocs keys identical in en and ar', () => {
        expect(Object.keys(ar.PromoterDocs).sort()).toEqual(Object.keys(en.PromoterDocs).sort())
    })

    it('uses the distinct Arabic word for revoke, not the one that reads as cancel', () => {
        expect(ar.Promoter.devRevoke).toBe('إبطال')
        expect(ar.Promoter.devRevokeConfirm).toBe('إبطال المفتاح')
        expect(ar.SuperadminApiKeys.revoke).toBe('إبطال')
        expect(ar.Promoter.devCancel).toBe('إلغاء')
    })
})

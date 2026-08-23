import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, fireEvent } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import messages from '../../../../../../messages/en.json'
import { AdEditor } from '../_components/AdEditor'
import type { PromoBusinessDTO } from '@/types/promotion'

afterEach(() => {
    cleanup()
})

const business: PromoBusinessDTO = {
    id: 'b-1',
    nameEn: 'Spice Bazaar',
    nameAr: null,
    logoUrl: null,
    category: 'DINING',
    phoneE164: '+971501234567',
    whatsappE164: null,
    allowedDomains: ['spice-bazaar.ae'],
    active: true,
    adCount: 0,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
}

function renderEditor(onSave = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={messages}>
            <AdEditor
                businesses={[business]}
                properties={[]}
                ad={null}
                onSave={onSave}
                onCancel={vi.fn()}
            />
        </NextIntlClientProvider>,
    )
    return { onSave }
}

describe('AdEditor', () => {
    it('shows the link field only for a website ad', () => {
        renderEditor()
        expect(screen.queryByLabelText('Link')).not.toBeInTheDocument()

        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        expect(screen.getByLabelText('Link')).toBeInTheDocument()
        expect(screen.queryByLabelText('Coupon code')).not.toBeInTheDocument()
    })

    it('shows the coupon fields only for a coupon ad', () => {
        renderEditor()
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'COUPON' },
        })
        expect(screen.getByLabelText('Coupon code')).toBeInTheDocument()
        expect(screen.queryByLabelText('Link')).not.toBeInTheDocument()
    })

    it('rejects a link that is not on an allowed domain, before submitting', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'https://evil.com/x' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(screen.getByText(/not on one of the business's allowed domains/i))
            .toBeInTheDocument()
        expect(onSave).not.toHaveBeenCalled()
    })

    it('rejects a non-https link', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'http://spice-bazaar.ae/x' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(screen.getByText(/must start with https/i)).toBeInTheDocument()
        expect(onSave).not.toHaveBeenCalled()
    })

    it('rejects a link carrying userinfo, matching the backend', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'https://my-bank.com@spice-bazaar.ae/pay' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).not.toHaveBeenCalled()
    })

    it('accepts a subdomain of an allowed domain', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'https://offers.spice-bazaar.ae/friday' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).toHaveBeenCalledTimes(1)
        expect(onSave.mock.calls[0][0]).toMatchObject({
            ctaType: 'WEBSITE',
            ctaUrl: 'https://offers.spice-bazaar.ae/friday',
        })
    })

    it('refuses to save without a title in either language', () => {
        const { onSave } = renderEditor()
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))
        expect(onSave).not.toHaveBeenCalled()
    })

    it('refuses a call ad when the business has no phone number', () => {
        const noPhone = { ...business, phoneE164: null }
        const onSave = vi.fn()
        render(
            <NextIntlClientProvider locale="en" messages={messages}>
                <AdEditor businesses={[noPhone]} properties={[]} ad={null}
                    onSave={onSave} onCancel={vi.fn()} />
            </NextIntlClientProvider>,
        )
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Call us' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'CALL' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).not.toHaveBeenCalled()
    })

    it('anchors the campaign window to Dubai time, not UTC', () => {
        // The window is compared against absolute instants server-side. With a
        // UTC anchor, "ends 30 September" actually stopped at 04:00 on 1 October
        // in Dubai -- a weekend offer outliving its own stated end date.
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Brunch' },
        })
        fireEvent.change(screen.getByLabelText('Starts'), {
            target: { value: '2026-09-01' },
        })
        fireEvent.change(screen.getByLabelText('Ends'), {
            target: { value: '2026-09-30' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        const body = onSave.mock.calls[0][0]
        expect(body.startsAt).toBe('2026-08-31T20:00:00.000Z')
        expect(body.endsAt).toBe('2026-09-30T19:59:59.000Z')
    })

    it('allows a single-day campaign', () => {
        // start === end compared two date-only strings as equal and blocked
        // Save, for a body the backend accepts (00:00:00 to 23:59:59).
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'One day only' },
        })
        fireEvent.change(screen.getByLabelText('Starts'), {
            target: { value: '2026-09-30' },
        })
        fireEvent.change(screen.getByLabelText('Ends'), {
            target: { value: '2026-09-30' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).toHaveBeenCalledTimes(1)
    })

    it('accepts the FQDN form of an allowed host', () => {
        // PromotionUrlValidator strips one trailing dot on both sides, so the
        // server stores this. Rejecting it told the admin, untruthfully, that
        // the link was off the allowlist.
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'https://spice-bazaar.ae./friday' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).toHaveBeenCalledTimes(1)
    })

    it('rejects empty userinfo, which the backend also refuses', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'WEBSITE' },
        })
        fireEvent.change(screen.getByLabelText('Link'), {
            target: { value: 'https://@spice-bazaar.ae/friday' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave).not.toHaveBeenCalled()
    })

    it('rejects a malformed accent colour before the round trip', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Brunch' },
        })
        fireEvent.change(screen.getByLabelText('Card colour'), {
            target: { value: 'FBF3E2' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(screen.getByText(/hex colour/i)).toBeInTheDocument()
        expect(onSave).not.toHaveBeenCalled()
    })

    it('names the actual problem instead of a generic message', () => {
        // The CALL branch previously rendered "Check the highlighted fields"
        // with no highlighted field, while the backend knew exactly what was
        // wrong: the business has no phone number.
        const noPhone = { ...business, phoneE164: null }
        const onSave = vi.fn()
        render(
            <NextIntlClientProvider locale="en" messages={messages}>
                <AdEditor businesses={[noPhone]} properties={[]} ad={null}
                    onSave={onSave} onCancel={vi.fn()} />
            </NextIntlClientProvider>,
        )
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Call us' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'CALL' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(screen.getByText(/no phone number/i)).toBeInTheDocument()
        expect(onSave).not.toHaveBeenCalled()
    })

    it('renders the live preview with the typed title', () => {
        renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Friday brunch' },
        })
        expect(screen.getByTestId('ad-card-preview')).toHaveTextContent('Friday brunch')
    })

    it('drops the coupon code when the CTA switches away from coupon', () => {
        const { onSave } = renderEditor()
        fireEvent.change(screen.getByLabelText('Title (English)'), {
            target: { value: 'Brunch' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'COUPON' },
        })
        fireEvent.change(screen.getByLabelText('Coupon code'), {
            target: { value: 'MIFTAH25' },
        })
        fireEvent.change(screen.getByLabelText('What happens on tap'), {
            target: { value: 'NONE' },
        })
        fireEvent.click(screen.getByRole('button', { name: 'Save' }))

        expect(onSave.mock.calls[0][0].couponCode).toBeNull()
    })
})

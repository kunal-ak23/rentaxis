import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import { useState } from 'react'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { PromoImageField } from '../_components/PromoImageField'

/** ux6 item 2: a business logo / ad artwork is uploaded, not typed as a URL. */

const ROUTE = '/api/v1/public/promo-images/0b0f2c1e-1111-4222-8333-944455556666.png'

function Harness({ initial = '', locale = 'en' as 'en' | 'ar' }) {
    const [value, setValue] = useState(initial)
    return (
        <NextIntlClientProvider locale={locale} messages={locale === 'en' ? en : ar}>
            <PromoImageField label="Logo" value={value} onChange={setValue} />
            <output data-testid="value">{value}</output>
        </NextIntlClientProvider>
    )
}

const png = (size = 10) => new File([new Uint8Array(size)], 'logo.png', { type: 'image/png' })

beforeEach(() => {
    global.fetch = vi.fn(async () => ({
        ok: true, status: 201, json: async () => ({ url: ROUTE }), text: async () => '',
    })) as unknown as typeof fetch
})

afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

describe('PromoImageField', () => {
    it('uploads the chosen image and keeps the route it answers', async () => {
        render(<Harness />)
        const input = screen.getByLabelText(`Logo: ${en.Promotions.uploadImage}`)
        await act(async () => { fireEvent.change(input, { target: { files: [png()] } }) })
        await waitFor(() => expect(screen.getByTestId('value').textContent).toBe(ROUTE))
        const [url, init] = (global.fetch as ReturnType<typeof vi.fn>).mock.calls[0]
        expect(url).toBe('/api/proxy/v1/promotions/images')
        expect((init as RequestInit).method).toBe('POST')
        // The preview loads through the admin route (the public one serves live images only).
        expect(screen.getByRole('img', { name: 'Logo' }).getAttribute('src'))
            .toBe('/api/proxy/v1/promotions/images/0b0f2c1e-1111-4222-8333-944455556666.png')
        expect(screen.getByRole('button', { name: en.Promotions.replaceImage })).toBeInTheDocument()
    })

    it('refuses an image over 2 MB without uploading it', async () => {
        render(<Harness />)
        const input = screen.getByLabelText(`Logo: ${en.Promotions.uploadImage}`)
        await act(async () => { fireEvent.change(input, { target: { files: [png(2 * 1024 * 1024 + 1)] } }) })
        expect(screen.getByRole('alert').textContent).toBe(en.Promotions.imageTooLarge)
        expect(global.fetch).not.toHaveBeenCalled()
    })

    it('says so in Arabic when the server refuses the file', async () => {
        global.fetch = vi.fn(async () => ({
            ok: false, status: 400, json: async () => ({}), text: async () => '{"message":"Upload a PNG"}',
        })) as unknown as typeof fetch
        render(<Harness locale="ar" />)
        const input = screen.getByLabelText(`Logo: ${ar.Promotions.uploadImage}`)
        await act(async () => { fireEvent.change(input, { target: { files: [png()] } }) })
        await waitFor(() => expect(screen.getByRole('alert').textContent).toBe(ar.Promotions.imageUploadFailed))
        expect(screen.getByTestId('value').textContent).toBe('')
    })

    it('keeps an https link saved earlier and can remove it', () => {
        render(<Harness initial="https://cdn.example.com/logo.png" />)
        expect(screen.getByRole('img', { name: 'Logo' }).getAttribute('src')).toBe('https://cdn.example.com/logo.png')
        fireEvent.click(screen.getByRole('button', { name: en.Promotions.removeImage }))
        expect(screen.getByTestId('value').textContent).toBe('')
    })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import { useState } from 'react'
import en from '../../../../../../messages/en.json'
import { PromoImageField } from '../_components/PromoImageField'
import { fetchRouter } from '@/test/fetchRouter'

/**
 * A promoter is refused every /promotions route, so their image field uploads to
 * and previews from /promoter/images, never the admin's.
 */
const ROUTE = '/api/v1/public/promo-images/0b0f2c1e-1111-4222-8333-944455556666.png'

function Harness({ scope }: { scope: 'admin' | 'promoter' }) {
    const [value, setValue] = useState('')
    return (
        <NextIntlClientProvider locale="en" messages={en}>
            <PromoImageField label="Logo" value={value} onChange={setValue} scope={scope} />
        </NextIntlClientProvider>
    )
}

let api: ReturnType<typeof fetchRouter>

beforeEach(() => {
    api = fetchRouter()
    api.on('POST', '/images', { status: 201, body: { url: ROUTE } })
})
afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
})

async function upload() {
    const input = screen.getByLabelText(`Logo: ${en.Promotions.uploadImage}`)
    await act(async () => {
        fireEvent.change(input, { target: { files: [new File([new Uint8Array(10)], 'logo.png', { type: 'image/png' })] } })
    })
}

describe('PromoImageField scope', () => {
    it('uploads to the promoter route and previews through it', async () => {
        render(<Harness scope="promoter" />)
        await upload()
        await waitFor(() => expect(screen.getByRole('img', { name: 'Logo' })).toBeInTheDocument())

        expect(api.calls.map(c => `${c.method} ${c.url}`)).toEqual(['POST /api/proxy/v1/promoter/images'])
        expect(screen.getByRole('img', { name: 'Logo' }).getAttribute('src'))
            .toBe('/api/proxy/v1/promoter/images/0b0f2c1e-1111-4222-8333-944455556666.png')
    })

    it('keeps the admin route for an admin', async () => {
        render(<Harness scope="admin" />)
        await upload()
        await waitFor(() => expect(screen.getByRole('img', { name: 'Logo' })).toBeInTheDocument())

        expect(api.calls.map(c => `${c.method} ${c.url}`)).toEqual(['POST /api/proxy/v1/promotions/images'])
        expect(screen.getByRole('img', { name: 'Logo' }).getAttribute('src'))
            .toBe('/api/proxy/v1/promotions/images/0b0f2c1e-1111-4222-8333-944455556666.png')
    })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import { fetchRouter } from '@/test/fetchRouter'
import type { ApiKeyDTO } from '@/types/promotion'

vi.mock('@/i18n/routing', () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}))

import { PromoterDeveloperTab } from '../_components/PromoterDeveloperTab'

const API = '/api/proxy/v1/promoter/api-keys'
const SECRET = 'mfk_live_SECRETSECRETSECRETSECRETSECRET123'

const key = (over: Partial<ApiKeyDTO> = {}): ApiKeyDTO => ({
    id: 'k-1', name: 'Production sync', keyPrefix: 'mfk_live_a1B', createdAt: '2026-10-01T00:00:00Z',
    lastUsedAt: null, expiresAt: null, revokedAt: null, revokedReason: null, revokedByAdmin: false,
    status: 'active', ...over,
})

let api: ReturnType<typeof fetchRouter>

function renderTab() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <PromoterDeveloperTab />
        </NextIntlClientProvider>,
    )
}

beforeEach(() => { api = fetchRouter() })
afterEach(() => cleanup())

describe('PromoterDeveloperTab', () => {
    it('renders keys with prefix, Never for unused, and status', async () => {
        api.on('GET', API, { body: [key(), key({ id: 'k-2', name: 'Old', status: 'revoked', revokedAt: '2026-10-02T00:00:00Z', lastUsedAt: '2026-10-03T00:00:00Z' })] })
        renderTab()
        const row = (await screen.findByText('Production sync')).closest('tr') as HTMLElement
        expect(within(row).getByText('mfk_live_a1B…')).toBeTruthy()
        expect(within(row).getByText('Never')).toBeTruthy()
        expect(within(row).getByText('Active')).toBeTruthy()
        expect(within(row).getByRole('button', { name: 'Rotate' })).toBeTruthy()
        const old = screen.getByText('Old').closest('tr') as HTMLElement
        expect(within(old).queryByRole('button', { name: 'Rotate' })).toBeNull()
        expect(within(old).queryByRole('button', { name: 'Revoke' })).toBeNull()
        expect(screen.getByRole('link', { name: 'Read the developer docs' }).getAttribute('href'))
            .toBe('/dashboard/promoter/developer')
    })

    it('create shows the secret once and drops it after closing', async () => {
        api.on('GET', API, { body: [] })
        api.on('POST', API, { status: 201, body: { key: key(), secret: SECRET } })
        renderTab()
        fireEvent.click(await screen.findByRole('button', { name: 'Create key' }))
        fireEvent.change(screen.getByLabelText('Key name'), { target: { value: 'Production sync' } })
        fireEvent.click(screen.getByRole('button', { name: 'Create' }))
        expect(await screen.findByText(SECRET)).toBeTruthy()
        expect(screen.getByText(/only time you will see this key/)).toBeTruthy()
        expect(api.callsTo('POST', API)[0].body).toEqual({ name: 'Production sync' })
        fireEvent.click(screen.getByRole('button', { name: "I've saved it" }))
        await waitFor(() => expect(screen.queryByText(SECRET)).toBeNull())
        expect(document.body.textContent).not.toContain(SECRET)
    })

    it('disables Create key at 2 active keys, but expiring keys do not count', async () => {
        api.on('GET', API, { body: [key(), key({ id: 'k-2', name: 'Second' })] })
        renderTab()
        const btn = await screen.findByRole('button', { name: 'Create key' })
        expect((btn as HTMLButtonElement).disabled).toBe(true)
        expect(screen.getByText(/You have 2 active keys/)).toBeTruthy()
        cleanup()
        api.on('GET', API, { body: [key(), key({ id: 'k-2', name: 'Second', status: 'expiring', expiresAt: '2099-01-01T00:00:00Z' })] })
        renderTab()
        await screen.findByText('Second')
        expect((screen.getByRole('button', { name: 'Create key' }) as HTMLButtonElement).disabled).toBe(false)
    })

    it('rotate calls the endpoint and shows the new secret', async () => {
        api.on('GET', API, { body: [key()] })
        api.on('POST', `${API}/k-1/rotate`, { status: 201, body: { key: key({ id: 'k-3', name: 'Production sync' }), secret: SECRET } })
        renderTab()
        fireEvent.click(await screen.findByRole('button', { name: 'Rotate' }))
        expect(await screen.findByText(SECRET)).toBeTruthy()
        expect(api.callsTo('POST', '/k-1/rotate')).toHaveLength(1)
    })

    it('revoke sends the reason', async () => {
        api.on('GET', API, { body: [key()] })
        api.on('POST', `${API}/k-1/revoke`, { body: key({ status: 'revoked', revokedAt: '2026-10-05T00:00:00Z' }) })
        renderTab()
        fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }))
        fireEvent.change(screen.getByLabelText('Reason (optional)'), { target: { value: 'Leaked in a log' } })
        fireEvent.click(screen.getByRole('button', { name: 'Revoke key' }))
        await waitFor(() => expect(api.callsTo('POST', '/k-1/revoke')).toHaveLength(1))
        expect(api.callsTo('POST', '/k-1/revoke')[0].body).toEqual({ reason: 'Leaked in a log' })
    })

    it('shows the reason on an admin-revoked row', async () => {
        api.on('GET', API, { body: [key({ status: 'revoked', revokedByAdmin: true, revokedReason: 'Abuse report', revokedAt: '2026-10-02T00:00:00Z' })] })
        renderTab()
        expect(await screen.findByText('Revoked by Miftah admin: Abuse report')).toBeTruthy()
    })

    it('translates the cap conflict by code, not the raw server text', async () => {
        api.on('GET', API, { body: [] })
        api.on('POST', API, { status: 409, body: { message: 'Key limit reached', code: 'api_key.limit' } })
        renderTab()
        fireEvent.click(await screen.findByRole('button', { name: 'Create key' }))
        fireEvent.change(screen.getByLabelText('Key name'), { target: { value: 'x' } })
        fireEvent.click(screen.getByRole('button', { name: 'Create' }))
        expect(await screen.findByText(en.Promoter.devErrorLimit)).toBeTruthy()
        expect(screen.queryByText('Key limit reached')).toBeNull()
    })

    it('maps not_active on rotate and falls back to the generic text for unknown errors', async () => {
        api.on('GET', API, { body: [key()] })
        api.on('POST', `${API}/k-1/rotate`, { status: 409, body: { message: 'raw', code: 'api_key.not_active' } })
        renderTab()
        fireEvent.click(await screen.findByRole('button', { name: 'Rotate' }))
        expect(await screen.findByText(en.Promoter.devErrorNotActive)).toBeTruthy()
        api.on('POST', `${API}/k-1/rotate`, { status: 500, body: { message: 'boom internals' } })
        fireEvent.click(screen.getByRole('button', { name: 'Rotate' }))
        expect(await screen.findByText(en.Promoter.devActionError)).toBeTruthy()
        expect(screen.queryByText('boom internals')).toBeNull()
    })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchListings, fetchMarketplaceListings, reorderMedia } from '../listings'

/**
 * Wire-contract tests for the listings API client. Each of these encodes a
 * param/body name the backend actually reads — they existed as silent
 * mismatches before (bare-array reorder body, `bedrooms` vs `minBedrooms`,
 * `search` vs `q`).
 */
describe('listings API client wire contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function requestedUrl(): URL {
    const raw = fetchMock.mock.calls[0][0] as string
    return new URL(raw, 'http://localhost')
  }

  it('reorderMedia wraps the ids in a mediaIds object (backend ReorderRequest shape)', async () => {
    fetchMock.mockResolvedValue({ ok: true })
    await reorderMedia('listing-1', ['m1', 'm2'], 'token')

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/proxy/listings/listing-1/media/reorder')
    expect(init.method).toBe('PUT')
    // The backend binds to ReorderRequest(List<UUID> mediaIds); a bare JSON
    // array fails deserialization with a 400.
    expect(JSON.parse(init.body as string)).toEqual({ mediaIds: ['m1', 'm2'] })
  })

  it('fetchMarketplaceListings sends minBedrooms, not bedrooms', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ content: [] }) })
    await fetchMarketplaceListings('acme', { minBedrooms: 3 })

    const url = requestedUrl()
    expect(url.searchParams.get('minBedrooms')).toBe('3')
    expect(url.searchParams.has('bedrooms')).toBe(false)
  })

  it('fetchListings sends the search text as q, not search', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ content: [] }) })
    await fetchListings({ search: 'marina' })

    const url = requestedUrl()
    expect(url.searchParams.get('q')).toBe('marina')
    expect(url.searchParams.has('search')).toBe(false)
  })
})

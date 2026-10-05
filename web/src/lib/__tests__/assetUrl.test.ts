import { describe, expect, it } from 'vitest'
import { assetSrc } from '../assetUrl'

describe('assetSrc', () => {
  it('sends a private attachment through the authenticated proxy', () => {
    expect(assetSrc('/api/v1/assets/serve/ticket-attachments/abc123.png'))
      .toBe('/api/proxy/v1/assets/serve/ticket-attachments/abc123.png')
    expect(assetSrc('/api/v1/assets/serve/settlement-deductions/d1/damage.png'))
      .toBe('/api/proxy/v1/assets/serve/settlement-deductions/d1/damage.png')
    expect(assetSrc('/api/v1/assets/serve/lease-docs/contract.pdf'))
      .toBe('/api/proxy/v1/assets/serve/lease-docs/contract.pdf')
  })

  it('leaves a public asset alone — it is rendered where there is no session', () => {
    expect(assetSrc('/api/v1/assets/serve/assets/logo.png'))
      .toBe('/api/v1/assets/serve/assets/logo.png')
  })

  it('leaves anything that is not a local serve URL untouched', () => {
    expect(assetSrc('https://acct.blob.core.windows.net/tenant-1/listings/a.jpg'))
      .toBe('https://acct.blob.core.windows.net/tenant-1/listings/a.jpg')
    expect(assetSrc('data:image/png;base64,AAAA')).toBe('data:image/png;base64,AAAA')
    expect(assetSrc(undefined)).toBe('')
    expect(assetSrc(null)).toBe('')
  })
})

import { listingMediaSrc, promoImageSrc } from '../assetUrl'

describe('listingMediaSrc (bug 26/27)', () => {
  it('sends the staff media route through the authenticated proxy', () => {
    expect(listingMediaSrc('/api/listings/l1/media/m1/file')).toBe('/api/proxy/listings/l1/media/m1/file')
  })
  it('leaves the public media route, external links and nothing alone', () => {
    expect(listingMediaSrc('/api/v1/public/listing-media/m1')).toBe('/api/v1/public/listing-media/m1')
    expect(listingMediaSrc('https://cdn.example.com/a.jpg')).toBe('https://cdn.example.com/a.jpg')
    expect(listingMediaSrc(null)).toBe('')
    expect(listingMediaSrc(undefined)).toBe('')
  })
})

describe('promoImageSrc (ux6 item 2)', () => {
  it('previews an uploaded image through the admin route, live or not', () => {
    expect(promoImageSrc('/api/v1/public/promo-images/0b0f2c1e-1111-4222-8333-944455556666.png'))
      .toBe('/api/proxy/v1/promotions/images/0b0f2c1e-1111-4222-8333-944455556666.png')
  })
  it('leaves an https link to artwork hosted elsewhere alone', () => {
    expect(promoImageSrc('https://cdn.example.com/art.webp')).toBe('https://cdn.example.com/art.webp')
    expect(promoImageSrc('')).toBe('')
  })
})

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

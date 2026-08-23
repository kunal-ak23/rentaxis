import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { AdCardPreview, toCssColour } from '../_components/AdCardPreview'

afterEach(() => {
    cleanup()
})

describe('toCssColour', () => {
    it('passes a 6-digit hex through unchanged', () => {
        expect(toCssColour('#FBF3E2')).toBe('#FBF3E2')
    })

    it('reorders 8-digit hex from the stored #AARRGGBB to CSS #RRGGBBAA', () => {
        // The phone reads #80FBF3E2 as rgb(FB,F3,E2) at 50% alpha. Handed to CSS
        // unchanged it would render rgb(80,FB,F3) at 89% -- a different colour,
        // in the one component whose job is showing what the renter will see.
        expect(toCssColour('#80FBF3E2')).toBe('#FBF3E280')
    })

    it('keeps a fully opaque colour looking identical', () => {
        expect(toCssColour('#FFFBF3E2')).toBe('#FBF3E2FF')
    })
})

describe('AdCardPreview fallback surface', () => {
    const base = {
        title: 'Half off shawarma',
        businessName: 'Spice Bazaar',
        ctaType: 'COUPON' as const,
    }

    it('uses the brass tint, not the near-invisible surface tint', () => {
        // The phone's home canvas is #F6F5FA. The old #F4F2F9 fallback differed
        // from it by six across all three channels combined, so a card with
        // neither artwork nor an accent colour vanished into the background.
        const { container } = render(<AdCardPreview {...base} accentColor={null} />)
        const card = container.firstElementChild as HTMLElement

        expect(card.style.background).toBe('rgb(251, 243, 226)')
        expect(card.style.border).toContain('rgb(235, 215, 168)')
    })

    it('does not claim the colour is unset when the admin typed the brass tint', () => {
        // The fallback used to be inferred by comparing the resolved fill to the
        // fallback constant, so this exact accent colour reported itself missing.
        render(<AdCardPreview {...base} accentColor="#FFFBF3E2" />)

        expect(screen.queryByText(/No card colour set/)).toBeNull()
    })

    it('still reports a genuinely unset colour to screen readers', () => {
        render(<AdCardPreview {...base} accentColor="#FF" />)

        expect(screen.getByText(/No card colour set/)).toBeTruthy()
    })
})

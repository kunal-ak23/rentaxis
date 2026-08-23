import { describe, expect, it } from 'vitest'
import { toCssColour } from '../_components/AdCardPreview'

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

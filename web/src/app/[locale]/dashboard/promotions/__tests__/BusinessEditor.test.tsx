import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { NextIntlClientProvider } from 'next-intl'
import en from '../../../../../../messages/en.json'
import ar from '../../../../../../messages/ar.json'
import { BusinessEditor } from '../_components/BusinessEditor'

afterEach(() => {
    cleanup()
})

describe('BusinessEditor', () => {
    it.each([['en', en], ['ar', ar]] as const)('recommends the logo size under the logo upload (%s)', (locale, messages) => {
        render(
            <NextIntlClientProvider locale={locale} messages={messages}>
                <BusinessEditor business={null} onSave={vi.fn()} onCancel={vi.fn()} />
            </NextIntlClientProvider>,
        )
        expect(screen.getByLabelText(`${messages.Promotions.logo}: ${messages.Promotions.uploadImage}`))
            .toHaveAccessibleDescription(`${messages.Promotions.imageHint} ${messages.Promotions.logoSizeHint}`)
    })
})

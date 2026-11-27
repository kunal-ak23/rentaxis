import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
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

    const renderEditor = (props: Partial<React.ComponentProps<typeof BusinessEditor>> = {}, onSave = vi.fn()) => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <BusinessEditor business={null} onSave={onSave} onCancel={vi.fn()} {...props} />
            </NextIntlClientProvider>,
        )
        return onSave
    }

    it('always sends description and website, null when blank', async () => {
        const onSave = renderEditor()
        fireEvent.change(screen.getByLabelText(en.Promotions.nameEn), { target: { value: 'Sparkle' } })
        fireEvent.click(screen.getByRole('button', { name: en.Promotions.save }))
        await waitFor(() => expect(onSave).toHaveBeenCalled())
        const body = onSave.mock.calls[0][0]
        expect(body).toHaveProperty('descriptionEn', null)
        expect(body).toHaveProperty('descriptionAr', null)
        expect(body).toHaveProperty('websiteUrl', null)
    })

    it('sends the typed description and website, and hints at the allowed domain', async () => {
        const onSave = renderEditor()
        fireEvent.change(screen.getByLabelText(en.Promotions.nameEn), { target: { value: 'Sparkle' } })
        fireEvent.change(screen.getByLabelText(en.Promotions.descriptionEn), { target: { value: ' Laundry ' } })
        fireEvent.change(screen.getByLabelText(en.Promotions.descriptionAr), { target: { value: 'مغسلة' } })
        fireEvent.change(screen.getByLabelText(en.Promotions.websiteUrl), { target: { value: 'https://sparkle.ae' } })
        expect(screen.getByLabelText(en.Promotions.websiteUrl)).toHaveAccessibleDescription(en.Promotions.websiteUrlHint)
        fireEvent.click(screen.getByRole('button', { name: en.Promotions.save }))
        await waitFor(() => expect(onSave).toHaveBeenCalled())
        expect(onSave.mock.calls[0][0]).toMatchObject({
            descriptionEn: 'Laundry', descriptionAr: 'مغسلة', websiteUrl: 'https://sparkle.ae',
        })
    })

    it('hides the directory fields when showDirectoryFields is false', () => {
        renderEditor({ showDirectoryFields: false })
        expect(screen.queryByLabelText(en.Promotions.descriptionEn)).toBeNull()
        expect(screen.queryByLabelText(en.Promotions.websiteUrl)).toBeNull()
    })
})

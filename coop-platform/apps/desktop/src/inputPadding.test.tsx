import { MenuItem, TextField } from '@mui/material'
import { render } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Field, SelectField } from './profile/fields'
import { INPUT_PAD, AppThemeProvider } from './theme'

const px = (n: number) => `${n}px`

/** Every input in the app takes its inner padding from the theme, so a field cannot quietly be roomier than the rest. */
describe('input padding', () => {
  it('is the same on text fields, text areas, dropdowns and the profile fields', () => {
    const { container } = render(
      <AppThemeProvider>
        <TextField label="Plain" />
        <TextField label="Medium" size="medium" />
        <TextField label="Select" select value="a"><MenuItem value="a">A</MenuItem></TextField>
        <Field label="Profile" value="x" />
        <SelectField label="Dropdown" lookup="t" code={1} lk={{ t: { 1: 'One' } } as never} />
      </AppThemeProvider>,
    )
    const inputs = [...container.querySelectorAll<HTMLElement>('input:not([aria-hidden]), .MuiSelect-select')]
    expect(inputs.length).toBeGreaterThanOrEqual(4)
    for (const el of inputs) {
      const cs = getComputedStyle(el)
      expect(cs.paddingTop, el.className).toBe(px(INPUT_PAD.y))
      expect(cs.paddingBottom, el.className).toBe(px(INPUT_PAD.y))
      expect(cs.paddingLeft, el.className).toBe(px(INPUT_PAD.x))
    }
    // A text area is padded on the field, with none left on the text itself.
    const area = container.querySelector<HTMLElement>('textarea:not([aria-hidden])')!
    expect(getComputedStyle(area).paddingTop).toBe('0px')
    expect(getComputedStyle(area.closest('.MuiOutlinedInput-root')!).paddingTop).toBe(px(INPUT_PAD.y))
  })
})

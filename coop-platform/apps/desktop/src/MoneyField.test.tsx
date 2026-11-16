import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import MoneyField, { grouped } from './MoneyField'

let handed = ''
function Form({ start = '' }: { start?: string }) {
  const [v, setV] = useState(start)
  handed = v
  return <><MoneyField label="Principal" value={v} onValueChange={setV} /><button>next</button></>
}
const field = () => screen.getByLabelText('Principal') as HTMLInputElement

describe('MoneyField', () => {
  it('puts the commas in while typing and hands the form the plain number', async () => {
    render(<Form />)
    await userEvent.click(field())
    await userEvent.keyboard('1234')
    expect(field().value).toBe('1,234')
    await userEvent.keyboard('567')
    expect(field().value).toBe('1,234,567')
    expect(handed).toBe('1234567')
    await userEvent.keyboard('.5')
    expect(field().value).toBe('1,234,567.5')
    await userEvent.click(screen.getByRole('button', { name: 'next' }))
    expect(field().value).toBe('1,234,567.50')
    expect(handed).toBe('1234567.50')
  })

  it('keeps the cursor where the person is typing when a comma moves', async () => {
    render(<Form />)
    await userEvent.click(field())
    await userEvent.keyboard('10000')
    expect(field().value).toBe('10,000')
    field().setSelectionRange(2, 2) // after "10,"
    await userEvent.keyboard('5')
    expect(field().value).toBe('105,000')
    expect(field().selectionStart).toBe(3) // still just after the 5
  })

  it('removes the digit before a comma on Backspace and the one after it on Delete', async () => {
    render(<Form />)
    await userEvent.click(field())
    await userEvent.keyboard('1234567')
    field().setSelectionRange(2, 2) // just after "1,"
    await userEvent.keyboard('{Backspace}')
    expect(field().value).toBe('234,567')
    field().setSelectionRange(3, 3) // just before ",567"
    await userEvent.keyboard('{Delete}')
    expect(field().value).toBe('23,467')
  })

  it('drops letters and a second decimal point, and keeps two decimals', async () => {
    render(<Form />)
    await userEvent.click(field())
    await userEvent.keyboard('12a.3.45')
    expect(field().value).toBe('12.34')
  })

  it('groups only the whole part', () => {
    expect(grouped('1000000')).toBe('1,000,000')
    expect(grouped('1000.')).toBe('1,000.')
    expect(grouped('0.5')).toBe('0.5')
    expect(grouped('')).toBe('')
  })
})

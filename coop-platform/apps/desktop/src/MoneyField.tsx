import { TextField, type TextFieldProps } from '@mui/material'
import { useLayoutEffect, useRef, useState } from 'react'

const shown = (v: string) => {
  const n = Number(v)
  return v === '' || !Number.isFinite(n) ? v : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** The number as it is being typed, with the thousands commas put in as you go ("1234567.5" shows as "1,234,567.5"); the decimals are left as typed. */
export const grouped = (v: string) => {
  const [whole, ...frac] = v.split('.')
  const withCommas = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return frac.length ? `${withCommas}.${frac.join('')}` : withCommas
}

/** Digits and one decimal point, at most two decimals; commas and anything else typed or pasted are dropped. */
const clean = (typed: string) => {
  const raw = typed.replace(/[^0-9.]/g, '')
  const [whole, ...frac] = raw.split('.')
  return frac.length ? `${whole}.${frac.join('').slice(0, 2)}` : whole
}

/** How many digits and decimal points come before position {@code at}: where the cursor is, ignoring commas. */
const significantBefore = (s: string, at: number) => s.slice(0, at).replace(/[^0-9.]/g, '').length

/** The position in {@code s} just after its {@code n}th digit or decimal point. */
const positionAfter = (s: string, n: number) => {
  if (n <= 0) return 0
  let seen = 0
  for (let i = 0; i < s.length; i++) {
    if (/[0-9.]/.test(s[i])) seen++
    if (seen === n) return i + 1
  }
  return s.length
}

/**
 * A money input. The thousands commas appear while you type (1,000,000), and the cursor stays where you are typing; when you leave the field
 * the amount is shown with two decimals (1,000,000.00). The value handed to the form is always the plain number ("1000000.00"), so the form can
 * read it with Number().
 */
export default function MoneyField({ value, onValueChange, slotProps, onBlur, onFocus, onKeyDown, ...rest }: Omit<TextFieldProps, 'value' | 'onChange' | 'type'> & { value: string; onValueChange: (v: string) => void }) {
  const [focused, setFocused] = useState(false)
  const input = useRef<HTMLInputElement | null>(null)
  // where the cursor goes after the next render, counted in digits (commas move as they are put in)
  const caret = useRef<number | null>(null)
  // the key that made the last change, to tell Backspace from Delete when only a comma was removed
  const lastKey = useRef<string | null>(null)
  const display = focused ? grouped(value) : shown(value)

  useLayoutEffect(() => {
    if (caret.current == null || !input.current || document.activeElement !== input.current) return
    const at = positionAfter(display, caret.current)
    input.current.setSelectionRange(at, at)
    caret.current = null
  })

  return (
    <TextField {...rest} type="text" value={display} inputRef={input}
      onFocus={(e) => { setFocused(true); onFocus?.(e) }}
      onKeyDown={(e) => { lastKey.current = e.key; onKeyDown?.(e) }}
      onBlur={(e) => {
        setFocused(false)
        const n = Number(value)
        if (value !== '' && Number.isFinite(n)) onValueChange(n.toFixed(2))
        onBlur?.(e)
      }}
      onChange={(e) => {
        const typed = e.target.value
        const at = e.target.selectionStart ?? typed.length
        let next = clean(typed)
        let digitsBefore = significantBefore(typed, at)
        // Backspace or Delete on a comma alone would change nothing, so it takes the digit beside the comma instead.
        if (next === value && typed.length < display.length) {
          const removedAt = significantBefore(display, at)
          const deleteKey = lastKey.current === 'Delete'
          const drop = deleteKey ? removedAt : removedAt - 1
          if (drop >= 0 && drop < value.length) {
            next = clean(value.slice(0, drop) + value.slice(drop + 1))
            digitsBefore = deleteKey ? removedAt : removedAt - 1
          }
        }
        caret.current = digitsBefore
        onValueChange(next)
      }}
      slotProps={{ ...slotProps, htmlInput: { inputMode: 'decimal', style: { textAlign: 'right' }, ...(slotProps as { htmlInput?: object } | undefined)?.htmlInput } }} />
  )
}

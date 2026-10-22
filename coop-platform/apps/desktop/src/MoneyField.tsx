import { TextField, type TextFieldProps } from '@mui/material'
import { useState } from 'react'

const shown = (v: string) => {
  const n = Number(v)
  return v === '' || !Number.isFinite(n) ? v : n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/**
 * A money input. While typing it holds a plain number ("1000000"); when the person leaves the field it is shown as 1,000,000.00.
 * The value handed to the form is always the plain number with two decimals, so the form can read it with Number().
 */
export default function MoneyField({ value, onValueChange, slotProps, onBlur, onFocus, ...rest }: Omit<TextFieldProps, 'value' | 'onChange' | 'type'> & { value: string; onValueChange: (v: string) => void }) {
  const [focused, setFocused] = useState(false)
  return (
    <TextField {...rest} type="text" value={focused ? value : shown(value)}
      onFocus={(e) => { setFocused(true); onFocus?.(e) }}
      onBlur={(e) => {
        setFocused(false)
        const n = Number(value)
        if (value !== '' && Number.isFinite(n)) onValueChange(n.toFixed(2))
        onBlur?.(e)
      }}
      onChange={(e) => {
        // Digits and one decimal point, at most two decimals; commas and anything else typed or pasted are dropped.
        const raw = e.target.value.replace(/[^0-9.]/g, '')
        const [whole, ...frac] = raw.split('.')
        onValueChange(frac.length ? `${whole}.${frac.join('').slice(0, 2)}` : whole)
      }}
      slotProps={{ ...slotProps, htmlInput: { inputMode: 'decimal', style: { textAlign: 'right' }, ...(slotProps as { htmlInput?: object } | undefined)?.htmlInput } }} />
  )
}

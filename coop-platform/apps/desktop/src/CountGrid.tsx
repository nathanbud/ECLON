import { Box, TextField, Typography } from '@mui/material'
import { type KeyboardEvent as ReactKeyboardEvent } from 'react'
import type { FoSetup } from './api'

/** How many of each note and coin, as typed (denomination id -> text). */
export type Counts = Record<number, string>

export const toNumbers = (c: Counts): Record<number, number> => Object.fromEntries(Object.entries(c).filter(([, v]) => v).map(([k, v]) => [Number(k), Number(v)]))

export const countTotal = (setup: FoSetup, c: Counts) => setup.denominations.reduce((a, d) => a + d.amount * Number(c[d.id] || 0), 0)

/**
 * Count of notes and coins; the total is worked out from it so the amount can never disagree with the count.
 * Enter moves to the next note or coin, so a count can be typed without touching the mouse; Ctrl+Enter sends it.
 */
export function CountGrid({ setup, counts, onChange, onSubmit }: { setup: FoSetup; counts: Counts; onChange: (c: Counts) => void; onSubmit?: () => void }) {
  const move = (e: ReactKeyboardEvent<HTMLElement>) => {
    if (e.key !== 'Enter') return
    e.preventDefault()
    if ((e.ctrlKey || e.metaKey) && onSubmit) { onSubmit(); return }
    const inputs = Array.from(e.currentTarget.closest('[data-count-grid]')?.querySelectorAll<HTMLInputElement>('input') ?? [])
    inputs[inputs.indexOf(e.target as HTMLInputElement) + 1]?.focus()
  }
  return (
    <Box data-count-grid="" onKeyDown={move} sx={{ display: 'grid', gap: '4px 10px', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))' }}>
      {setup.denominations.map((d) => (
        <Box key={d.id} sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
          <Typography sx={{ fontSize: 12.5, width: 52, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{d.description}</Typography>
          <TextField
            size="small" value={counts[d.id] ?? ''} placeholder="0" sx={{ width: 70 }}
            onChange={(e) => onChange({ ...counts, [d.id]: e.target.value.replace(/[^0-9]/g, '') })}
            slotProps={{ htmlInput: { 'aria-label': `${d.kind} ${d.description} count`, inputMode: 'numeric', style: { textAlign: 'right', padding: '4px 8px' } } }}
          />
        </Box>
      ))}
    </Box>
  )
}

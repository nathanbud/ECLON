import { Box, ButtonBase, Typography } from '@mui/material'
import { FiCheck } from 'react-icons/fi'

export interface Step { label: string }

/**
 * Progress through the loan application, in the manner of Apple's setup flows: one quiet row, a filled check for what is finished, an accent
 * ring on the step in hand and grey for what is still ahead. Finished steps can be clicked to look back; nothing ahead can be.
 * Colour is never the only signal: each state also changes the mark (check, number) and is announced to screen readers.
 */
export default function LoanStepper({ steps, current, viewing, onView, stopped }: {
  steps: Step[]; current: number; viewing: number; onView: (i: number) => void
  /** Set when the application ended before the last step (disapproved or cancelled). */
  stopped?: string
}) {
  return (
    <Box component="ol" aria-label="Loan application progress" sx={{ display: 'flex', alignItems: 'flex-start', listStyle: 'none', m: 0, px: 3, pt: 1, pb: 1.5 }}>
      {steps.map((s, i) => {
        const done = i < current
        const active = i === current
        const state = done ? 'completed' : active ? (stopped ? 'stopped' : 'current') : 'upcoming'
        const clickable = done
        return (
          <Box component="li" key={s.label} sx={{ display: 'flex', alignItems: 'flex-start', flex: i === steps.length - 1 ? '0 0 auto' : 1, minWidth: 0 }}>
            <ButtonBase disabled={!clickable} onClick={() => onView(i)} aria-current={viewing === i ? 'step' : undefined}
              aria-label={`Step ${i + 1}, ${s.label}, ${state}`} focusRipple
              sx={{ flexDirection: 'column', gap: 0.5, borderRadius: '8px', px: 0.5, py: 0.25, '&.Mui-disabled': { pointerEvents: 'none' } }}>
              <Box sx={(t) => ({
                width: 28, height: 28, borderRadius: '50%', display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 600, fontVariantNumeric: 'tabular-nums',
                transition: 'background-color .25s ease, border-color .25s ease, color .25s ease, box-shadow .25s ease',
                '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
                ...(done && { bgcolor: t.palette.primary.main, color: t.palette.primary.contrastText, border: '2px solid transparent' }),
                ...(active && {
                  bgcolor: 'transparent', color: stopped ? t.palette.error.main : t.palette.primary.main,
                  border: `2px solid ${stopped ? t.palette.error.main : t.palette.primary.main}`,
                  boxShadow: `0 0 0 4px ${stopped ? t.palette.error.main : t.palette.primary.main}26`,
                }),
                ...(!done && !active && { bgcolor: 'transparent', color: t.palette.text.disabled, border: `2px solid ${t.palette.divider}` }),
              })}>
                {done ? <FiCheck size={15} strokeWidth={3} aria-hidden /> : i + 1}
              </Box>
              <Typography sx={(t) => ({
                fontSize: 12, fontWeight: active || viewing === i ? 600 : 500, whiteSpace: 'nowrap',
                color: done || active ? t.palette.text.primary : t.palette.text.disabled,
              })}>{s.label}</Typography>
            </ButtonBase>
            {i < steps.length - 1 && (
              <Box aria-hidden sx={(t) => ({ flex: 1, minWidth: 12, height: 2, mt: '13px', mx: 0.5, borderRadius: 1, bgcolor: t.palette.divider, position: 'relative', overflow: 'hidden',
                '&::after': { content: '""', position: 'absolute', inset: 0, bgcolor: t.palette.primary.main, transformOrigin: 'left', transform: done ? 'scaleX(1)' : 'scaleX(0)',
                  transition: 'transform .35s ease', '@media (prefers-reduced-motion: reduce)': { transition: 'none' } } })} />
            )}
          </Box>
        )
      })}
    </Box>
  )
}

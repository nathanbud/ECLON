import { Box } from '@mui/material'
import { FiCheck } from 'react-icons/fi'

const SIZE = 18

/** One dot of a step tracker (loan applications, Back Office entries): a check for a step done, a ring for the one waited at, an empty circle for one still to come. */
export function StepDot({ state }: { state: 'done' | 'current' | 'todo' }) {
  return (
    <Box
      component="span" aria-hidden
      sx={(t) => ({
        width: SIZE, height: SIZE, borderRadius: '50%', flex: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', boxSizing: 'border-box',
        ...(state === 'done' && { bgcolor: t.palette.success.main, color: t.palette.success.contrastText, fontSize: 11 }),
        ...(state === 'current' && { border: `2px solid ${t.palette.warning.main}` }),
        ...(state === 'todo' && { border: `1.5px solid ${t.palette.divider}` }),
      })}
    >
      {state === 'done' && <FiCheck strokeWidth={3.5} />}
      {state === 'current' && <Box component="span" sx={(t) => ({ width: 8, height: 8, borderRadius: '50%', bgcolor: t.palette.warning.main })} />}
    </Box>
  )
}

import { Box, Tooltip, Typography } from '@mui/material'
import { ENTRY_STEPS, entryStep } from './backOfficeSort'
import { StepDot } from './ui/StepDot'

/** What each step waits for, for the hover text. */
const WAITS = ['being sent for review by the person who made it', 'approval by someone other than the maker', 'posting']

/**
 * Where a Back Office entry stands, like the loan applications' tracker (LoanStepStatus): a check for each step done, a ring at the one it waits at,
 * then that step's name; once posted every step is checked and it says Posted. A cancelled entry shows Cancelled instead.
 */
export function EntryStepStatus({ status }: { status: string }) {
  const at = entryStep(status)
  if (at == null) return <Typography component="span" sx={{ fontSize: 13, fontWeight: 600 }} color={status === 'CANCELLED' ? 'error.main' : 'text.secondary'}>{status === 'CANCELLED' ? 'Cancelled' : status}</Typography>
  const posted = at >= ENTRY_STEPS.length
  const label = posted
    ? `Posted. All ${ENTRY_STEPS.length} steps done.`
    : `At step ${at + 1} of ${ENTRY_STEPS.length}: ${ENTRY_STEPS[at]}, waiting for ${WAITS[at]}.${at > 0 ? ` Done: ${ENTRY_STEPS.slice(0, at).join(', ')}.` : ''}`
  const steps = ENTRY_STEPS.map((s, i) => `${i < at ? '✓' : i === at ? '●' : '○'} ${i + 1} · ${s}`).join('   ')
  return (
    <Tooltip title={<>{steps}{!posted && <><br />Waiting for {WAITS[at]}.</>}</>}>
      <Box role="img" aria-label={label} sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, flex: 'none' }}>
        {ENTRY_STEPS.map((s, i) => <StepDot key={s} state={i < at ? 'done' : i === at ? 'current' : 'todo'} />)}
        <Typography component="span" sx={{ fontSize: 13, fontWeight: 600, ml: 0.75, minWidth: 64, color: posted ? 'success.main' : undefined }}>
          {posted ? 'Posted' : ENTRY_STEPS[at]}
        </Typography>
      </Box>
    </Tooltip>
  )
}

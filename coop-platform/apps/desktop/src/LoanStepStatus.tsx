import { Box, Tooltip, Typography } from '@mui/material'
import { FiCheck } from 'react-icons/fi'
import type { LoanApplicationItem } from './api'
import { currentStep, LOAN_STEPS } from './loanSteps'

const SIZE = 18

/** One dot per step: a check for each step done, a ring for the one the application waits at, an empty circle for those still to come. */
function StepDot({ state }: { state: 'done' | 'current' | 'todo' }) {
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

/**
 * Where a loan application stands in its steps, after pics/status.png: the steps done (checked), the one it waits at (ring) and the ones still
 * to come, then that step's name. Applications in a status outside the steps show their status word instead.
 */
export function LoanStepStatus({ application }: { application: Pick<LoanApplicationItem, 'status' | 'statusName' | 'waitingForDeductions'> }) {
  const at = currentStep(application)
  if (at == null) return <Typography sx={{ fontSize: 13 }} color="text.secondary">{application.statusName}</Typography>
  const done = LOAN_STEPS.slice(0, at)
  const label = `At step ${at + 1} of ${LOAN_STEPS.length}: ${LOAN_STEPS[at]}. Done: ${done.join(', ')}.`
  return (
    <Tooltip title={LOAN_STEPS.map((s, i) => `${i < at ? '✓' : i === at ? '●' : '○'} ${i + 1} · ${s}`).join('   ')}>
      <Box role="img" aria-label={label} sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, flex: 'none' }}>
        {LOAN_STEPS.map((s, i) => <StepDot key={s} state={i < at ? 'done' : i === at ? 'current' : 'todo'} />)}
        <Typography component="span" sx={{ fontSize: 13, fontWeight: 600, ml: 0.75, minWidth: 84 }}>{LOAN_STEPS[at]}</Typography>
      </Box>
    </Tooltip>
  )
}

import { Box, Tooltip, Typography } from '@mui/material'
import type { LoanApplicationItem } from './api'
import { currentStep, LOAN_STEPS, releaseWords } from './loanSteps'
import { StepDot } from './ui/StepDot'

/**
 * Where a loan application stands in its steps, after pics/status.png: the steps done (checked), the one it waits at (ring) and the ones still
 * to come, then that step's name; once released every step is checked and it says Released. Hovering also says where the release stands.
 * Applications in a status outside the steps show their status word instead.
 */
export function LoanStepStatus({ application }: { application: Pick<LoanApplicationItem, 'status' | 'statusName' | 'waitingForDeductions' | 'releaseStatus' | 'releasedOn'> }) {
  const at = currentStep(application)
  if (at == null) return <Typography sx={{ fontSize: 13 }} color="text.secondary">{application.statusName}</Typography>
  const released = at >= LOAN_STEPS.length
  const release = releaseWords(application)
  const label = released
    ? `${release}. All ${LOAN_STEPS.length} steps done.`
    : `At step ${at + 1} of ${LOAN_STEPS.length}: ${LOAN_STEPS[at]}${release ? ` (${release})` : ''}. Done: ${LOAN_STEPS.slice(0, at).join(', ')}.`
  const steps = LOAN_STEPS.map((s, i) => `${i < at ? '✓' : i === at ? '●' : '○'} ${i + 1} · ${s}`).join('   ')
  return (
    <Tooltip title={<>{steps}{release && <><br />{release}</>}</>}>
      <Box role="img" aria-label={label} sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, flex: 'none' }}>
        {LOAN_STEPS.map((s, i) => <StepDot key={s} state={i < at ? 'done' : i === at ? 'current' : 'todo'} />)}
        <Typography component="span" sx={{ fontSize: 13, fontWeight: 600, ml: 0.75, minWidth: 96, color: released ? 'success.main' : undefined }}>
          {released ? 'Released' : LOAN_STEPS[at]}
        </Typography>
      </Box>
    </Tooltip>
  )
}

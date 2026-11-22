import { Alert, AlertTitle, Button } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { FiLock } from 'react-icons/fi'
import { getBusinessDayStatus } from './api'

/** What the notice says, also used by the screens' tests. */
export const CLOSED_TITLE = 'The business day has not been started'
export const CLOSED_TEXT = 'You can look things up, but no transaction can be made until it is started: no deposits, withdrawals, payments, vouchers, journals, memos, sales or stock movements. Someone with the Start Day right starts it in Business Day.'

/**
 * Across the top of every screen while the branch has no open business day: nothing can be posted or prepared until it is started (the server
 * refuses it, BusinessDayGuard), only looked at. Shares the title bar chip's status (refreshed every minute and when Start Day is done), so it goes
 * away by itself once the day is started. Hidden on the Business Day screen itself, which says so in its own words, and where business days are
 * not in use or the server cannot say.
 */
export function BusinessDayClosedNotice({ onOpen, hide = false }: { onOpen: () => void; hide?: boolean }) {
  const q = useQuery({ queryKey: ['business-day-status'], queryFn: getBusinessDayStatus, refetchInterval: 60_000, retry: false, staleTime: 15_000 })
  const s = q.data
  if (hide || !s || !s.inUse || s.openDate) return null
  return (
    <Alert
      severity="warning" icon={<FiLock />} role="alert" square
      sx={{ borderRadius: 0, alignItems: 'center', py: 0.25, '& .MuiAlert-message': { py: 0.5 } }}
      action={<Button color="inherit" size="small" onClick={onOpen}>Open Business Day</Button>}
    >
      <AlertTitle sx={{ fontSize: 13, fontWeight: 600, mb: 0 }}>{CLOSED_TITLE}</AlertTitle>
      {CLOSED_TEXT}
    </Alert>
  )
}

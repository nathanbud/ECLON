import { Box, ButtonBase, Tooltip } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { RADIUS } from './theme'
import { getBusinessDayStatus } from './api'

/**
 * The business date in the title bar, so whoever posts can see which day it goes on: a green dot and the date while a day is open, an orange dot and
 * "Posting closed" while none is. A date in orange means the open day is not today (End Day was not done). Pressing it opens Business Day. Hidden
 * where the server does not use business days, or when it cannot say.
 */
export function BusinessDayChip({ onOpen }: { onOpen: () => void }) {
  const q = useQuery({ queryKey: ['business-day-status'], queryFn: getBusinessDayStatus, refetchInterval: 60_000, retry: false, staleTime: 15_000 })
  const s = q.data
  if (!s || !s.inUse) return null
  const open = s.openDate != null
  const behind = open && s.openDate !== s.calendarDate
  const short = s.openDate ? new Date(`${s.openDate}T00:00:00`).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' }) : null
  const tip = open
    ? `Business day ${s.openWords}: everything posted now is dated on it.${behind ? ' It is not today: End Day was not done.' : ''} Open Business Day.`
    : 'No business day is open, so nothing can be posted. Open Business Day to start it.'
  return (
    <Tooltip title={tip}>
      <ButtonBase onClick={onOpen} aria-label={open ? `Business day ${s.openWords}${behind ? ', not today' : ''}` : 'Posting closed: no business day is open'}
        sx={{ display: 'flex', alignItems: 'center', gap: 0.75, px: 1, height: 24, borderRadius: `${RADIUS}px`, fontSize: 12, mr: 0.5, whiteSpace: 'nowrap', flexShrink: 0, color: 'inherit', '&:hover': { bgcolor: 'action.hover' } }}>
        <Box component="span" aria-hidden sx={{ width: 7, height: 7, borderRadius: '50%', bgcolor: open && !behind ? 'success.main' : 'warning.main' }} />
        <Box component="span" sx={{ fontWeight: 600, color: behind ? 'warning.main' : 'inherit' }}>{open ? short : 'Posting closed'}</Box>
      </ButtonBase>
    </Tooltip>
  )
}

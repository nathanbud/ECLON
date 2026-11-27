import { Tooltip } from '@mui/material'
import { visuallyHidden } from '@mui/utils'
import type { ReactNode } from 'react'

/**
 * A control with why it is as it is (why it cannot be used, who allowed it, what switching it does): shown on hover and on keyboard focus, and read out
 * with the control when the control's input points at `id` with `aria-describedby`. Use it instead of a `title`, which reaches neither the keyboard nor
 * a screen reader. With no `why`, the control is shown as it is.
 *
 *   <Explained id="ben-enabled" why="You cannot switch off your own account">
 *     <Switch slotProps={{ input: { 'aria-label': 'Enabled, ben', 'aria-describedby': 'ben-enabled' } }} ... />
 *   </Explained>
 */
export function Explained({ id, why, children }: { id: string; why: string; children: ReactNode }) {
  if (!why) return <>{children}</>
  return (
    <Tooltip title={why} describeChild>
      <span>{children}<span id={id} style={visuallyHidden}>{why}</span></span>
    </Tooltip>
  )
}

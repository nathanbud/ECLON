import { Tooltip } from '@mui/material'
import { cloneElement, type ReactElement } from 'react'

/**
 * Shows an action the person may not use as a disabled button with the reason on hover or focus, instead of hiding it or letting the server refuse it. The server still decides:
 * this only explains. `children` must be a single button.
 */
export function Gated({ allowed, reason, children }: { allowed: boolean; reason: string; children: ReactElement<{ disabled?: boolean }> }) {
  if (allowed) return children
  return (
    <Tooltip title={reason}>
      {/* A disabled button gets no pointer or focus events, so the tooltip sits on a wrapper that can be focused. */}
      <span tabIndex={0} aria-label={reason} style={{ display: 'inline-flex' }}>
        {cloneElement(children, { disabled: true })}
      </span>
    </Tooltip>
  )
}

export const NEEDS_EDIT = 'You need the edit-member permission, and editing must be switched on for this server.'
export const NEEDS_ADMIN = 'Only an administrator can do this.'
export const NEEDS_OFFICER = 'Only a Membership Officer can do this. An administrator assigns the role in Settings > Users.'

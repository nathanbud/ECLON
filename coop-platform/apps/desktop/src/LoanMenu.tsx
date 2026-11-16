import { Button, Divider, ListItemIcon, ListItemText, ListSubheader, Menu, MenuItem, Tooltip, Typography } from '@mui/material'
import { type ReactNode, useEffect, useRef, useState } from 'react'
import { FiCheck, FiChevronDown, FiFilePlus, FiSettings } from 'react-icons/fi'
import type { LoanApplicationItem } from './api'
import { APPROVED, isReleased } from './loanSteps'

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
export const NEW_LOAN_SHORTCUT = isMac ? '⌘N' : 'Ctrl+N'

/** Which applications the Loan Module lists: all still on their way, those at one step, or those released lately. */
export type LoanFilter = 'all' | 'deductions' | 'verify' | 'approve' | 'release' | 'released'

/** `step` is the loan step the application waits at, as the step buttons over the list name it. */
export const FILTERS: { key: LoanFilter; label: string; step: string; test: (a: LoanApplicationItem) => boolean }[] = [
  { key: 'all', label: 'All waiting', step: 'All', test: (a) => !isReleased(a) },
  { key: 'deductions', label: 'Waiting for deductions', step: '2 · Deduction', test: (a) => a.status === 1 && a.waitingForDeductions },
  { key: 'verify', label: 'New, to verify', step: '3 · Verification', test: (a) => a.status === 1 && !a.waitingForDeductions },
  { key: 'approve', label: 'Verified, to approve', step: '4 · Approval', test: (a) => a.status === 2 },
  { key: 'release', label: 'Approved, waiting for release', step: '5 · Release', test: (a) => a.status === APPROVED && !isReleased(a) },
  { key: 'released', label: 'Released, last 30 days', step: 'Released', test: isReleased },
]

const Shortcut = ({ children }: { children: ReactNode }) => (
  <Typography component="span" sx={{ ml: 4, fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{children}</Typography>
)

/**
 * The "Loan Module" menu in the title bar, like the Client Profile menu: start a new application (Ctrl+N), choose which waiting applications the
 * list shows, and, for administrators, open Loan Setup. Alt+L opens it.
 */
export function LoanMenu({ onNew, mayNew, filter, onFilter, counts, onLoanSetup }: {
  onNew: () => void; mayNew: boolean; filter: LoanFilter; onFilter: (f: LoanFilter) => void
  counts: Record<LoanFilter, number> | null; onLoanSetup?: () => void
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const button = useRef<HTMLButtonElement>(null)
  const close = () => setAnchor(null)

  // Alt+L opens the menu, like a menu-bar mnemonic (Alt+C is the Client Profile's).
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.altKey && !e.ctrlKey && !e.metaKey && e.key.toLowerCase() === 'l') {
        e.preventDefault()
        setAnchor(button.current)
      }
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [])

  const text = (t: string) => <ListItemText slotProps={{ primary: { sx: { fontSize: 13 } } }}>{t}</ListItemText>
  return (
    <>
      <Tooltip title="Loan Module menu (Alt+L)">
        <Button
          ref={button} size="small" onClick={(e) => setAnchor(e.currentTarget)} endIcon={<FiChevronDown />}
          aria-haspopup="menu" aria-expanded={!!anchor} aria-keyshortcuts="Alt+L"
          sx={{ color: 'inherit', fontSize: 13, fontWeight: 400, px: 0.75, py: 0.25, minWidth: 0, borderRadius: '5px', '& .MuiButton-endIcon': { ml: 0.25 }, '&:hover': { bgcolor: 'action.hover' } }}
        >
          <u>L</u>oan Module
        </Button>
      </Tooltip>
      <Menu anchorEl={anchor} open={!!anchor} onClose={close} slotProps={{ paper: { sx: { borderRadius: '5px', minWidth: 280, mt: 0.5 } } }}>
        <MenuItem disabled={!mayNew} onClick={() => { close(); onNew() }}>
          <ListItemIcon sx={{ minWidth: 32, fontSize: 15 }}><FiFilePlus /></ListItemIcon>
          {text('New loan application')}
          <Shortcut>{NEW_LOAN_SHORTCUT}</Shortcut>
        </MenuItem>
        <Divider />
        <ListSubheader sx={{ fontSize: 11, lineHeight: '28px', bgcolor: 'transparent' }}>Show in the list</ListSubheader>
        {FILTERS.map((f) => (
          <MenuItem key={f.key} selected={filter === f.key} onClick={() => { close(); onFilter(f.key) }}>
            <ListItemIcon sx={{ minWidth: 32, fontSize: 15 }}>{filter === f.key ? <FiCheck /> : null}</ListItemIcon>
            {text(f.label)}
            <Shortcut>{counts ? counts[f.key] : ''}</Shortcut>
          </MenuItem>
        ))}
        {onLoanSetup && <Divider />}
        {onLoanSetup && (
          <MenuItem onClick={() => { close(); onLoanSetup() }}>
            <ListItemIcon sx={{ minWidth: 32, fontSize: 15 }}><FiSettings /></ListItemIcon>
            {text('Loan Setup…')}
          </MenuItem>
        )}
        {!mayNew && <Divider />}
        {!mayNew && (
          <Typography sx={{ px: 2, py: 0.75, fontSize: 12, maxWidth: 280 }} color="text.secondary">
            Your account may not do 1 · Profiling, so it cannot start a loan application.
          </Typography>
        )}
      </Menu>
    </>
  )
}

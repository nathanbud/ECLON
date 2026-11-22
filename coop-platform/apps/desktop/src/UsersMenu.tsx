import { Button, Divider, ListItemIcon, ListItemText, ListSubheader, Menu, MenuItem, Tooltip, Typography } from '@mui/material'
import { type ReactNode, useEffect, useRef, useState } from 'react'
import { FiCheck, FiChevronDown, FiClipboard, FiUserPlus } from 'react-icons/fi'
import type { AdminUser } from './api'

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
export const NEW_USER_SHORTCUT = isMac ? '⌘N' : 'Ctrl+N'

/** Which people the Users list shows. */
export type UserFilter = 'all' | 'enabled' | 'disabled' | 'admins' | 'officers' | 'password' | 'locked'

const ADMIN_LEVELS = ['ADMIN', 'SUPERADMIN']

/** `locked` says whether a person is locked out after too many wrong passwords (the page asks the server for that list separately). */
export const USER_FILTERS: { key: UserFilter; label: string; test: (u: AdminUser, locked: (username: string) => boolean) => boolean }[] = [
  { key: 'all', label: 'All users', test: () => true },
  { key: 'enabled', label: 'Enabled', test: (u) => u.enabled },
  { key: 'disabled', label: 'Disabled', test: (u) => !u.enabled },
  { key: 'admins', label: 'Administrators', test: (u) => ADMIN_LEVELS.includes((u.level ?? '').toUpperCase()) },
  { key: 'officers', label: 'Membership Officers', test: (u) => u.roles.includes('MEMBERSHIP_OFFICER') },
  { key: 'password', label: 'Needs a password', test: (u) => u.enabled && (u.passwordState === 'NONE' || u.passwordState === 'EXPIRED') },
  { key: 'locked', label: 'Locked out', test: (u, locked) => locked(u.username) },
]

const Shortcut = ({ children }: { children: ReactNode }) => (
  <Typography component="span" sx={{ ml: 4, fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{children}</Typography>
)

/**
 * The "Users" menu in the title bar, like the Loan Module and Member Profile menus: add a new user (Ctrl+N), choose which people the list shows,
 * with how many each choice has, and open the Audit Log. Alt+U opens it.
 */
export function UsersMenu({ onNew, mayNew, filter, onFilter, counts, onAuditLog }: {
  onNew: () => void; mayNew: boolean; filter: UserFilter; onFilter: (f: UserFilter) => void
  counts: Record<UserFilter, number> | null; onAuditLog?: () => void
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const button = useRef<HTMLButtonElement>(null)
  const close = () => setAnchor(null)

  // Alt+U opens the menu, like a menu-bar mnemonic (Alt+C is the Member Profile's, Alt+L the Loan Module's).
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.altKey && !e.ctrlKey && !e.metaKey && e.key.toLowerCase() === 'u') {
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
      <Tooltip title="Users menu (Alt+U)">
        <Button
          ref={button} size="small" onClick={(e) => setAnchor(e.currentTarget)} endIcon={<FiChevronDown />}
          aria-haspopup="menu" aria-expanded={!!anchor} aria-keyshortcuts="Alt+U"
          sx={{ color: 'inherit', fontSize: 13, fontWeight: 400, px: 0.75, py: 0.25, minWidth: 0, borderRadius: '5px', '& .MuiButton-endIcon': { ml: 0.25 }, '&:hover': { bgcolor: 'action.hover' } }}
        >
          <u>U</u>sers
        </Button>
      </Tooltip>
      <Menu anchorEl={anchor} open={!!anchor} onClose={close} slotProps={{ paper: { sx: { borderRadius: '5px', minWidth: 260, mt: 0.5 } } }}>
        <MenuItem disabled={!mayNew} onClick={() => { close(); onNew() }}>
          <ListItemIcon sx={{ minWidth: 32, fontSize: 15 }}><FiUserPlus /></ListItemIcon>
          {text('New user…')}
          <Shortcut>{NEW_USER_SHORTCUT}</Shortcut>
        </MenuItem>
        <Divider />
        <ListSubheader sx={{ fontSize: 11, lineHeight: '28px', bgcolor: 'transparent' }}>Show in the list</ListSubheader>
        {USER_FILTERS.map((f) => (
          <MenuItem key={f.key} selected={filter === f.key} onClick={() => { close(); onFilter(f.key) }}>
            <ListItemIcon sx={{ minWidth: 32, fontSize: 15 }}>{filter === f.key ? <FiCheck /> : null}</ListItemIcon>
            {text(f.label)}
            <Shortcut>{counts ? counts[f.key] : ''}</Shortcut>
          </MenuItem>
        ))}
        {onAuditLog && <Divider />}
        {onAuditLog && (
          <MenuItem onClick={() => { close(); onAuditLog() }}>
            <ListItemIcon sx={{ minWidth: 32, fontSize: 15 }}><FiClipboard /></ListItemIcon>
            {text('Audit Log…')}
          </MenuItem>
        )}
      </Menu>
    </>
  )
}

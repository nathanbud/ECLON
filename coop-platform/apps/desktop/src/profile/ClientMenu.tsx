import { type ReactNode, useEffect, useRef, useState } from 'react'
import { FiChevronDown, FiEdit2, FiPlus, FiSave, FiSearch, FiTrash2, FiUserX, FiX } from 'react-icons/fi'
import { Button, Divider, ListItemIcon, ListItemText, Menu, MenuItem, Tooltip, Typography } from '@mui/material'

const isMac = /Mac|iPhone|iPad/.test(navigator.platform)
const MOD = isMac ? '⌘' : 'Ctrl+'
const SHORTCUT_FIND = `${MOD}F`

// Record actions with their shortcuts. The page supplies `run` only while an action is possible, which keeps the
// others disabled and leaves their keys free for the browser.
interface RecordAction { label: string; icon: ReactNode; shortcut: string; match: (e: KeyboardEvent) => boolean; run?: () => void }
export const mod = (e: KeyboardEvent) => (isMac ? e.metaKey : e.ctrlKey)
export const ACTIONS: RecordAction[] = [
  { label: 'New', icon: <FiPlus />, shortcut: `${MOD}N`, match: (e) => mod(e) && e.key === 'n' },
  { label: 'Edit', icon: <FiEdit2 />, shortcut: `${MOD}E`, match: (e) => mod(e) && e.key === 'e' },
  { label: 'Delete', icon: <FiTrash2 />, shortcut: `${MOD}Del`, match: (e) => mod(e) && e.key === 'Delete' },
  { label: 'Save', icon: <FiSave />, shortcut: `${MOD}S`, match: (e) => mod(e) && e.key === 's' },
  { label: 'Cancel', icon: <FiX />, shortcut: 'Esc', match: (e) => e.key === 'Escape' },
]

export const CLOSE_SHORTCUT = `${MOD}${isMac ? '⇧' : 'Shift+'}C`
/** Close Membership: Ctrl/Cmd+Shift+C (plain Ctrl+C is Copy and must stay so). Only taken while Close Membership can run, so a browser's own use of the keys is untouched otherwise. */
export const closeMatch = (e: KeyboardEvent) => mod(e) && e.shiftKey && !e.altKey && e.key.toLowerCase() === 'c'

const Shortcut = ({ children }: { children: ReactNode }) => (
  <Typography component="span" sx={{ ml: 4, fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{children}</Typography>
)

/** The "Client Profile" menu in the title bar: find plus the record actions, each with its shortcut. */
export function ClientMenu({ onFind, actions, editOn, pendingNote, onCloseMembership }: { onFind: () => void; actions: RecordAction[]; editOn: boolean; pendingNote: string | null; onCloseMembership?: () => void }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const button = useRef<HTMLButtonElement>(null)
  const close = () => setAnchor(null)

  // Alt+C opens the menu, like a menu-bar mnemonic.
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.altKey && !e.ctrlKey && !e.metaKey && e.key.toLowerCase() === 'c') {
        e.preventDefault()
        setAnchor(button.current)
      }
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [])

  const item = (a: RecordAction) => (
    <MenuItem key={a.label} disabled={!a.run} onClick={() => { close(); a.run?.() }}>
      <ListItemIcon sx={{ minWidth: 32, fontSize: 15 }}>{a.icon}</ListItemIcon>
      <ListItemText slotProps={{ primary: { sx: { fontSize: 13 } } }}>{a.label}</ListItemText>
      <Shortcut>{a.shortcut}</Shortcut>
    </MenuItem>
  )
  return (
    <>
      <Tooltip title="Client Profile menu (Alt+C)">
        <Button
          ref={button} size="small" onClick={(e) => setAnchor(e.currentTarget)} endIcon={<FiChevronDown />}
          aria-haspopup="menu" aria-expanded={!!anchor} aria-keyshortcuts="Alt+C"
          sx={{ color: 'inherit', fontSize: 13, fontWeight: 400, px: 0.75, py: 0.25, minWidth: 0, borderRadius: '5px', '& .MuiButton-endIcon': { ml: 0.25 }, '&:hover': { bgcolor: 'action.hover' } }}
        >
          <u>C</u>lient Profile
        </Button>
      </Tooltip>
      <Menu anchorEl={anchor} open={!!anchor} onClose={close} slotProps={{ paper: { sx: { borderRadius: '5px', minWidth: 270, mt: 0.5 } } }}>
        <MenuItem onClick={() => { close(); onFind() }}>
          <ListItemIcon sx={{ minWidth: 32, fontSize: 15 }}><FiSearch /></ListItemIcon>
          <ListItemText slotProps={{ primary: { sx: { fontSize: 13 } } }}>Find Client</ListItemText>
          <Shortcut>{SHORTCUT_FIND}</Shortcut>
        </MenuItem>
        <Divider />
        {actions.slice(0, 3).map(item)}
        <Divider />
        {actions.slice(3).map(item)}
        <Divider />
        <MenuItem disabled={!onCloseMembership} onClick={() => { close(); onCloseMembership?.() }} sx={{ color: 'error.main' }}>
          <ListItemIcon sx={{ minWidth: 32, fontSize: 15, color: 'inherit' }}><FiUserX /></ListItemIcon>
          <ListItemText slotProps={{ primary: { sx: { fontSize: 13 } } }}>Close Membership…</ListItemText>
          <Shortcut>{CLOSE_SHORTCUT}</Shortcut>
        </MenuItem>
        {(!editOn || pendingNote) && <Divider />}
        {(!editOn || pendingNote) && (
          <Typography sx={{ px: 2, py: 0.75, fontSize: 12, maxWidth: 270 }} color="text.secondary">
            {pendingNote ?? 'You cannot edit clients with this account, or editing is turned off on this server.'}
          </Typography>
        )}
      </Menu>
    </>
  )
}

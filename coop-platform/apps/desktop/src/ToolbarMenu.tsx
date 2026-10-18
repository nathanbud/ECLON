import { Button, Divider, ListItemIcon, ListItemText, Menu, MenuItem, Tooltip, Typography } from '@mui/material'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { FiChevronDown } from 'react-icons/fi'

export interface MenuEntry {
  label: string
  icon: ReactNode
  shortcut?: string
  /** Set while the action is possible; unset items are shown dimmed. */
  run?: () => void
  /** Draws a line above this item. */
  divider?: boolean
}

/**
 * A menu button for the title bar, like a menu-bar item: a label with an underlined mnemonic letter, opened by click
 * or Alt+letter, listing actions with their shortcuts.
 */
export default function ToolbarMenu({ label, mnemonic, items, note }: { label: string; mnemonic: string; items: MenuEntry[]; note?: string | null }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const button = useRef<HTMLButtonElement>(null)
  const close = () => setAnchor(null)
  const at = label.toLowerCase().indexOf(mnemonic.toLowerCase())

  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.altKey && !e.ctrlKey && !e.metaKey && e.key.toLowerCase() === mnemonic.toLowerCase()) {
        e.preventDefault()
        setAnchor(button.current)
      }
    }
    window.addEventListener('keydown', on)
    return () => window.removeEventListener('keydown', on)
  }, [mnemonic])

  return (
    <>
      <Tooltip title={`${label} menu (Alt+${mnemonic.toUpperCase()})`}>
        <Button
          ref={button} size="small" onClick={(e) => setAnchor(e.currentTarget)} endIcon={<FiChevronDown />}
          aria-haspopup="menu" aria-expanded={!!anchor} aria-keyshortcuts={`Alt+${mnemonic.toUpperCase()}`}
          sx={{ color: 'inherit', fontSize: 13, fontWeight: 400, px: 0.75, py: 0.25, minWidth: 0, borderRadius: '5px', '& .MuiButton-endIcon': { ml: 0.25 }, '&:hover': { bgcolor: 'action.hover' } }}
        >
          {at < 0 ? label : <>{label.slice(0, at)}<u>{label[at]}</u>{label.slice(at + 1)}</>}
        </Button>
      </Tooltip>
      <Menu anchorEl={anchor} open={!!anchor} onClose={close} slotProps={{ paper: { sx: { borderRadius: '5px', minWidth: 270, mt: 0.5 } } }}>
        {items.flatMap((it) => [
          ...(it.divider ? [<Divider key={`${it.label}-d`} />] : []),
          <MenuItem key={it.label} disabled={!it.run} onClick={() => { close(); it.run?.() }}>
            <ListItemIcon sx={{ minWidth: 32, fontSize: 16 }}>{it.icon}</ListItemIcon>
            <ListItemText slotProps={{ primary: { sx: { fontSize: 13 } } }}>{it.label}</ListItemText>
            {it.shortcut && <Typography component="span" sx={{ ml: 4, fontSize: 12, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">{it.shortcut}</Typography>}
          </MenuItem>,
        ])}
        {note && <Divider />}
        {note && <Typography sx={{ px: 2, py: 0.75, fontSize: 12, maxWidth: 270 }} color="text.secondary">{note}</Typography>}
      </Menu>
    </>
  )
}

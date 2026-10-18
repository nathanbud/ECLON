import { Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, Divider, IconButton, ListItemText, Menu, MenuItem, TextField } from '@mui/material'
import { useCallback, useState } from 'react'
import { FiBookmark, FiX } from 'react-icons/fi'
import type { FoForm } from './FoTransactionForm'

/** A saved transaction that can be started again in one step (for standing or repeated work). */
export interface FoTemplate { name: string; tab: string; form: FoForm }

const keyFor = (user: string) => `coop.foTemplates.${user}`

function load(user: string): FoTemplate[] {
  try { return JSON.parse(localStorage.getItem(keyFor(user)) ?? '[]') } catch { return [] }
}

/** Templates are kept on this computer, per person. */
export function useTemplates(user: string) {
  const [items, setItems] = useState<FoTemplate[]>(() => load(user))
  const write = useCallback((next: FoTemplate[]) => {
    setItems(next)
    try { localStorage.setItem(keyFor(user), JSON.stringify(next)) } catch { /* storage unavailable */ }
  }, [user])
  return {
    items,
    save: (t: FoTemplate) => write([...items.filter((x) => x.name !== t.name), t].sort((a, b) => a.name.localeCompare(b.name))),
    remove: (name: string) => write(items.filter((x) => x.name !== name)),
  }
}

/** The Templates button: start a draft from a saved template, or save the draft on screen as one. */
export default function TemplatesMenu({ templates, canSave, onUse, onSave }: {
  templates: ReturnType<typeof useTemplates>; canSave: boolean; onUse: (t: FoTemplate) => void; onSave: (name: string) => void
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  const close = () => setAnchor(null)
  return (
    <>
      <Button size="small" startIcon={<FiBookmark />} onClick={(e) => setAnchor(e.currentTarget)} aria-haspopup="menu" aria-expanded={!!anchor}>Templates</Button>
      <Menu anchorEl={anchor} open={!!anchor} onClose={close}>
        <MenuItem disabled={!canSave} onClick={() => { close(); setName(''); setNaming(true) }}>Save this draft as a template…</MenuItem>
        <Divider />
        {templates.items.length === 0 && <MenuItem disabled>No templates yet</MenuItem>}
        {templates.items.map((t) => (
          <MenuItem key={t.name} onClick={() => { close(); onUse(t) }}>
            <ListItemText primary={t.name} secondary={t.form.clientName || undefined} />
            <IconButton size="small" aria-label={`Delete template ${t.name}`} sx={{ ml: 2 }} onClick={(e) => { e.stopPropagation(); templates.remove(t.name) }}><FiX /></IconButton>
          </MenuItem>
        ))}
      </Menu>
      <Dialog open={naming} onClose={() => setNaming(false)} fullWidth maxWidth="xs">
        <DialogTitle>Save as template</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1.5 }}>Keeps the member, accounts and amounts so the same transaction can be started again with one click. The date is set to today when it is used.</DialogContentText>
          <TextField
            autoFocus fullWidth size="small" label="Name" value={name} onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && name.trim()) { e.preventDefault(); onSave(name.trim()); setNaming(false) } }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setNaming(false)}>Cancel</Button>
          <Button variant="contained" disabled={!name.trim()} onClick={() => { onSave(name.trim()); setNaming(false) }}>Save</Button>
        </DialogActions>
      </Dialog>
    </>
  )
}

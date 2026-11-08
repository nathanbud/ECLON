import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, MenuItem, Stack, TextField, Tooltip, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { FiDatabase, FiEdit2, FiPlus, FiTrash2 } from 'react-icons/fi'
import { activeServer, addServer, chooseServer, deleteServer, editServer, getPublicConnections, LOCAL_ID, servers, type ServerEntry } from './api'

/**
 * The sign-in screen's Connection window: which server this app talks to (a list kept on this computer), and which database that server works on.
 * Choosing a server restarts the window on it. The database is shown, not chosen: a server works on one database at a time and only a SUPERADMIN, once signed in, can move it (Settings > Database connection),
 * because everyone's sign-in is read from that database.
 */
export default function ConnectionDialog({ onClose }: { onClose: () => void }) {
  const [list, setList] = useState<ServerEntry[]>(servers)
  const [pick, setPick] = useState(activeServer().id)
  const [form, setForm] = useState<{ id: string | null; name: string; url: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const chosen = list.find((s) => s.id === pick) ?? list[0]

  const databases = useQuery({ queryKey: ['public-connections', chosen.id, chosen.url], queryFn: () => getPublicConnections(chosen.url), retry: false })

  const refresh = (nextPick?: string) => {
    const next = servers()
    setList(next)
    setPick(nextPick && next.some((s) => s.id === nextPick) ? nextPick : activeServer().id)
  }

  const saveForm = () => {
    if (!form) return
    try {
      const entry = form.id ? editServer(form.id, form.name, form.url) : addServer(form.name, form.url)
      setForm(null)
      setError(null)
      refresh(entry.id)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const remove = () => {
    try {
      deleteServer(chosen.id)
      setError(null)
      refresh()
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const connect = () => {
    chooseServer(chosen.id)
    window.location.reload() // the window starts again on the chosen server, so nothing of the old one is kept
  }

  const current = activeServer().id === chosen.id

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="connection-title">
      <DialogTitle id="connection-title" sx={{ display: 'flex', alignItems: 'center', gap: 1 }}><FiDatabase aria-hidden /> Connection</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} sx={{ pt: 0.5 }}>
          <Box>
            <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Server</Typography>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <TextField select size="small" fullWidth label="Server" value={chosen.id} onChange={(e) => { setPick(e.target.value); setForm(null); setError(null) }}>
                {list.map((s) => <MenuItem key={s.id} value={s.id}>{s.name}{s.url ? ` (${s.url})` : ''}</MenuItem>)}
              </TextField>
              <Tooltip title="Add a server"><IconButton aria-label="Add a server" onClick={() => { setForm({ id: null, name: '', url: 'http://' }); setError(null) }}><FiPlus /></IconButton></Tooltip>
              <Tooltip title={chosen.id === LOCAL_ID ? 'The built-in entry cannot be changed' : 'Edit this server'}>
                <span><IconButton aria-label="Edit this server" disabled={chosen.id === LOCAL_ID} onClick={() => { setForm({ id: chosen.id, name: chosen.name, url: chosen.url }); setError(null) }}><FiEdit2 /></IconButton></span>
              </Tooltip>
              <Tooltip title={chosen.id === LOCAL_ID ? 'The built-in entry cannot be deleted' : 'Delete this server'}>
                <span><IconButton aria-label="Delete this server" disabled={chosen.id === LOCAL_ID} onClick={remove}><FiTrash2 /></IconButton></span>
              </Tooltip>
            </Stack>
          </Box>

          {form && (
            <Stack spacing={1.5} sx={{ p: 1.5, border: 1, borderColor: 'divider', borderRadius: '5px' }} component="form" onSubmit={(e) => { e.preventDefault(); saveForm() }} aria-label={form.id ? 'Edit server' : 'Add server'}>
              <TextField size="small" label="Connection name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus fullWidth slotProps={{ htmlInput: { maxLength: 60 } }} />
              <TextField size="small" label="Server address" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} fullWidth placeholder="http://192.168.1.20:8090" helperText="Where the Coop Platform server runs: http://host:port" />
              <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
                <Button color="inherit" onClick={() => { setForm(null); setError(null) }}>Cancel</Button>
                <Button type="submit" variant="contained" disabled={!form.name.trim() || !form.url.trim()}>Save</Button>
              </Stack>
            </Stack>
          )}
          {error && <Alert severity="error" role="alert">{error}</Alert>}

          <Box>
            <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">Database this server works on</Typography>
            {databases.isPending && <Typography color="text.secondary" sx={{ fontSize: 14 }}>Asking the server…</Typography>}
            {databases.isError && <Alert severity="warning">This server does not answer. Check the address, or that the server is running.</Alert>}
            {databases.data && (
              <Stack spacing={0.75} component="ul" sx={{ listStyle: 'none', p: 0, m: 0 }} aria-label="Saved database connections">
                {databases.data.connections.map((c) => (
                  <Box component="li" key={c.id} sx={{ display: 'flex', alignItems: 'center', gap: 1, p: 1, borderRadius: '5px', border: 1, borderColor: c.active ? 'primary.main' : 'divider' }}>
                    <Box sx={{ minWidth: 0, flex: 1 }}>
                      <Typography sx={{ fontSize: 14, fontWeight: 600 }} noWrap>{c.name}</Typography>
                      <Typography sx={{ fontSize: 12.5 }} color="text.secondary" noWrap>{c.dbName}{c.branch ? ` · ${c.branch}` : ''}</Typography>
                    </Box>
                    {c.active && <Chip size="small" color="primary" label="In use" />}
                  </Box>
                ))}
              </Stack>
            )}
            <Typography sx={{ fontSize: 12.5, mt: 1 }} color="text.secondary">
              A server works on one database at a time. A SUPERADMIN can move it to another saved connection in Settings, after signing in.
            </Typography>
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button color="inherit" onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={current || !!form} onClick={connect}>{current ? 'Connected' : 'Connect'}</Button>
      </DialogActions>
    </Dialog>
  )
}

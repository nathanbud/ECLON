import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, FormControlLabel, MenuItem, Snackbar, Stack, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { FiCheckCircle, FiDatabase, FiEdit2, FiPlus, FiRefreshCw, FiTrash2, FiX, FiZap } from 'react-icons/fi'
import {
  activateConnection, createConnection, deleteConnection, getConnections, loadBranches, loadDatabases, testConnection, updateConnection,
  type BranchOption, type ConnectionIn, type ConnectionProbe, type ConnectionRow,
} from './api'
import { appBackground, glass } from './glass'
import { useLeaveGuard } from './leaveGuard'
import { SchemaStatusNotice } from './SchemaStatusNotice'
import { useMe } from './session'
import { can } from './nav/screens'
import { ScreenTitle } from './appleUi'

interface Form { name: string; host: string; port: string; dbName: string; branchId: number | null; makeDefault: boolean }

const blank = (): Form => ({ name: '', host: 'localhost', port: '3306', dbName: '', branchId: null, makeDefault: false })
const fromRow = (r: ConnectionRow): Form => ({ name: r.name, host: r.host, port: String(r.port), dbName: r.dbName, branchId: r.branchId, makeDefault: r.isDefault })
const toIn = (f: Form): ConnectionIn => ({ name: f.name, host: f.host, port: f.port.trim() === '' ? null : Number(f.port), dbName: f.dbName, branchId: f.branchId, makeDefault: f.makeDefault })
const same = (a: Form, b: Form) => a.name.trim() === b.name.trim() && a.host.trim() === b.host.trim() && a.port.trim() === b.port.trim() && a.dbName === b.dbName && a.branchId === b.branchId && a.makeDefault === b.makeDefault

/**
 * Settings > Database connection (SUPERADMIN only): the saved connections of this server, set out like the old Connection Preferences window: name, server host and port, the
 * database (Load Databases asks the server what it has) and the branch or office (Get List reads it from the chosen database). Saving never switches; "Use this connection"
 * does, after a warning, because everyone is asked to sign in again against the database it opens.
 */
export default function DatabaseConnectionsPage() {
  const me = useMe()
  const qc = useQueryClient()
  const superadmin = me.roles.some((r) => r.replace('ROLE_', '') === 'SUPERADMIN')
  const rows = useQuery({ queryKey: ['connections'], queryFn: getConnections, enabled: superadmin })
  const [sel, setSel] = useState<string | null>(null) // a row's id, or 'new'
  const [form, setForm] = useState<Form>(blank)
  const [editing, setEditing] = useState(false)
  const [databases, setDatabases] = useState<string[]>([])
  const [branches, setBranches] = useState<BranchOption[]>([])
  const [probe, setProbe] = useState<ConnectionProbe | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [confirm, setConfirm] = useState<'delete' | 'use' | null>(null)

  const list = rows.data ?? []
  const row = list.find((r) => r.id === sel) ?? null
  const original = row ? fromRow(row) : blank()
  const changed = editing && !same(form, original)
  useLeaveGuard(changed ? 'The connection has changes that are not saved.' : null)

  const open = (r: ConnectionRow) => {
    setSel(r.id); setForm(fromRow(r)); setEditing(false); setProbe(null); setError(null)
    setDatabases([r.dbName]); setBranches(r.branchId != null && r.branchName ? [{ id: r.branchId, name: r.branchName }] : [])
  }

  // open the one in use when the list first arrives
  useEffect(() => {
    if (sel === null && list.length > 0) open(list.find((r) => r.active) ?? list[0])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [list.length])

  const fail = (e: unknown) => { setError((e as Error).message); setNotice(null) }
  const refreshed = () => qc.invalidateQueries({ queryKey: ['connections'] })

  const dbLoad = useMutation({
    mutationFn: () => loadDatabases(form.host, form.port.trim() === '' ? null : Number(form.port)),
    onSuccess: (names) => { setDatabases(names); setError(null); setNotice(names.length === 0 ? 'The server has no databases for this user.' : `${names.length} database${names.length === 1 ? '' : 's'} found.`) },
    onError: fail,
  })
  const branchLoad = useMutation({
    mutationFn: () => loadBranches({ host: form.host, port: form.port.trim() === '' ? null : Number(form.port), dbName: form.dbName }),
    onSuccess: (list) => { setBranches(list); setError(null); setNotice(`${list.length} branch${list.length === 1 ? '' : 'es'} or office${list.length === 1 ? '' : 's'} found.`) },
    onError: fail,
  })
  const tryIt = useMutation({
    mutationFn: () => testConnection({ host: form.host, port: form.port.trim() === '' ? null : Number(form.port), dbName: form.dbName }),
    onSuccess: (p) => { setProbe(p); setError(null) },
    onError: fail,
  })
  const save = useMutation({
    mutationFn: () => (sel === 'new' || !row ? createConnection(toIn(form)) : updateConnection(row.id, toIn(form))),
    onSuccess: async (saved) => { await refreshed(); setEditing(false); setSel(saved.id); setForm(fromRow(saved)); setError(null); setNotice('Saved. The server keeps working on the database it is on until you use this connection.') },
    onError: fail,
  })
  const remove = useMutation({
    mutationFn: () => deleteConnection(row!.id),
    onSuccess: async () => { setConfirm(null); setSel(null); setEditing(false); await refreshed(); setNotice('Deleted.') },
    onError: (e) => { setConfirm(null); fail(e) },
  })
  const use = useMutation({
    mutationFn: () => activateConnection(row!.id),
    onSuccess: async () => { setConfirm(null); setError(null); setNotice(`The server now works on "${row!.name}". Everyone signs in again.`); await qc.invalidateQueries() },
    onError: (e) => { setConfirm(null); fail(e) },
  })

  const busy = dbLoad.isPending || branchLoad.isPending || tryIt.isPending || save.isPending || remove.isPending || use.isPending
  const portOk = /^\d{1,5}$/.test(form.port.trim()) && Number(form.port) >= 1 && Number(form.port) <= 65535
  const complete = form.name.trim() !== '' && form.host.trim() !== '' && portOk && form.dbName !== ''
  const startup = !!row?.startup

  const start = () => { setSel('new'); setForm(blank()); setEditing(true); setDatabases([]); setBranches([]); setProbe(null); setError(null) }
  const cancel = () => { if (row) open(row); else { setSel(null); setForm(blank()); setEditing(false) } setError(null) }

  if (!superadmin) {
    return (
      <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
        <Alert severity="info" sx={{ maxWidth: 700, mx: 'auto' }}>Only a SUPERADMIN can see or change which database the server works on.</Alert>
      </Box>
    )
  }

  const selectedBranch = branches.find((b) => b.id === form.branchId)

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1000, mx: 'auto' }}>
        <ScreenTitle title="Database connection" sx={{ mb: 2 }}>
          The database this server works on, and the others it can be moved to. The database user and password are the server&apos;s own settings; none is saved here.
        </ScreenTitle>
        <SchemaStatusNotice showOk canApply={can(me, 'superadmin')} />
        {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)} role="alert">{error}</Alert>}
        {rows.isError && <Alert severity="error" sx={{ mb: 2 }}>The saved connections could not be read.</Alert>}

        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '280px minmax(0, 1fr)' }, gap: 2, alignItems: 'start' }}>
          <Box sx={(t) => ({ p: 1, ...glass(t) })} component="nav" aria-label="Saved connections">
            <Stack spacing={0.5} component="ul" sx={{ listStyle: 'none', p: 0, m: 0 }}>
              {list.map((r) => (
                <Box component="li" key={r.id}>
                  <Box
                    component="button" type="button" onClick={() => !changed && open(r)} aria-current={r.id === sel ? 'true' : undefined}
                    sx={(t) => ({ width: '100%', textAlign: 'left', cursor: 'pointer', font: 'inherit', color: 'inherit', p: 1, borderRadius: '5px', border: 1, borderColor: r.id === sel ? 'primary.main' : 'transparent', bgcolor: r.id === sel ? t.palette.action.selected : 'transparent', '&:hover': { bgcolor: t.palette.action.hover } })}
                  >
                    <Typography sx={{ fontSize: 13, fontWeight: 600 }} noWrap>{r.name}</Typography>
                    <Typography sx={{ fontSize: 12 }} color="text.secondary" noWrap>{r.host}:{r.port} · {r.dbName}</Typography>
                    <Stack direction="row" spacing={0.5} sx={{ mt: 0.5 }}>
                      {r.active && <Chip size="small" color="primary" label="In use" />}
                      {r.isDefault && <Chip size="small" variant="outlined" label="Default" />}
                    </Stack>
                  </Box>
                </Box>
              ))}
              {sel === 'new' && <Box component="li" sx={{ p: 1, fontSize: 13, fontStyle: 'italic' }}>New connection</Box>}
            </Stack>
          </Box>

          <Box sx={(t) => ({ p: 2.5, ...glass(t) })} component="section" aria-label="Connection details">
            <Stack direction="row" spacing={1} sx={{ mb: 2.5, flexWrap: 'wrap', rowGap: 1 }} role="toolbar" aria-label="Connection actions">
              <Button size="small" variant="outlined" startIcon={<FiPlus />} disabled={busy || editing} onClick={start}>New</Button>
              <Button size="small" variant="outlined" startIcon={<FiEdit2 />} disabled={busy || editing || !row || startup} onClick={() => setEditing(true)}>Edit</Button>
              <Button size="small" variant="outlined" color="error" startIcon={<FiTrash2 />} disabled={busy || editing || !row || startup || row.active} onClick={() => setConfirm('delete')}>Delete</Button>
              <Button size="small" variant="contained" startIcon={<FiCheckCircle />} disabled={busy || !editing || !complete || !changed} onClick={() => save.mutate()}>Save</Button>
              <Button size="small" variant="outlined" color="inherit" startIcon={<FiX />} disabled={busy || !editing} onClick={cancel}>Cancel</Button>
            </Stack>

            {!row && sel !== 'new' ? (
              <Typography color="text.secondary">Choose a connection, or add a new one.</Typography>
            ) : (
              <Stack spacing={2.25}>
                <TextField label="Connection Name" size="small" fullWidth value={form.name} disabled={!editing} onChange={(e) => setForm({ ...form, name: e.target.value })} slotProps={{ htmlInput: { maxLength: 60 } }} />
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                  <TextField label="Server Host" size="small" fullWidth value={form.host} disabled={!editing} onChange={(e) => { setForm({ ...form, host: e.target.value }); setDatabases([]); setBranches([]) }} />
                  <TextField label="Port" size="small" value={form.port} disabled={!editing} error={editing && form.port !== '' && !portOk} onChange={(e) => { setForm({ ...form, port: e.target.value }); setDatabases([]); setBranches([]) }} sx={{ width: { sm: 140 } }} slotProps={{ htmlInput: { inputMode: 'numeric' } }} />
                </Stack>
                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                  <TextField select label="DatabaseName" size="small" fullWidth value={form.dbName} disabled={!editing} onChange={(e) => { setForm({ ...form, dbName: e.target.value, branchId: null }); setBranches([]) }}>
                    {(databases.includes(form.dbName) || !form.dbName ? databases : [form.dbName, ...databases]).map((d) => <MenuItem key={d} value={d}>{d}</MenuItem>)}
                  </TextField>
                  <Button variant="outlined" startIcon={<FiDatabase />} disabled={!editing || busy || !form.host.trim() || !portOk} onClick={() => dbLoad.mutate()} sx={{ flexShrink: 0 }}>Load Databases</Button>
                </Stack>
                <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                  <TextField select label="Branch/Office" size="small" fullWidth value={form.branchId ?? ''} disabled={!editing} onChange={(e) => setForm({ ...form, branchId: e.target.value === '' ? null : Number(e.target.value) })}
                    helperText={!editing && row?.branchName ? undefined : selectedBranch || branches.length > 0 ? undefined : 'Optional. Choose Get List to read them from the database.'}>
                    <MenuItem value=""><em>None</em></MenuItem>
                    {branches.map((b) => <MenuItem key={b.id} value={b.id}>{b.name}</MenuItem>)}
                  </TextField>
                  <Button variant="outlined" startIcon={<FiRefreshCw />} disabled={!editing || busy || !form.dbName || !portOk} onClick={() => branchLoad.mutate()} sx={{ flexShrink: 0 }}>Get List</Button>
                </Stack>
                <FormControlLabel control={<Checkbox checked={form.makeDefault} disabled={!editing || (!!row && row.isDefault)} onChange={(e) => setForm({ ...form, makeDefault: e.target.checked })} />}
                  label="Set as default (the database the server opens when it starts)" />

                {probe && (
                  <Alert severity={!probe.reachable || !probe.usable ? 'error' : probe.warnings.length > 0 ? 'warning' : 'success'}>
                    {probe.message}
                    {probe.warnings.map((w) => <Box key={w} component="div">{w}</Box>)}
                  </Alert>
                )}

                <Stack direction="row" spacing={1.5} sx={{ pt: 0.5, flexWrap: 'wrap', rowGap: 1 }}>
                  <Button variant="outlined" disabled={busy || !complete || !portOk} onClick={() => tryIt.mutate()}>Test connection</Button>
                  <Button variant="contained" startIcon={<FiZap />} disabled={busy || editing || !row || row.active} onClick={() => setConfirm('use')}>
                    {row?.active ? 'In use now' : 'Use this connection'}
                  </Button>
                </Stack>
                {startup && <Typography sx={{ fontSize: 12 }} color="text.secondary">This is the setting the server was started with. Save a connection of your own to keep one you can edit.</Typography>}
              </Stack>
            )}
          </Box>
        </Box>
      </Box>

      <Dialog open={confirm === 'delete'} onClose={() => setConfirm(null)}>
        <DialogTitle>Delete this connection?</DialogTitle>
        <DialogContent><DialogContentText>&ldquo;{row?.name}&rdquo; is removed from the list. The database itself is not touched.</DialogContentText></DialogContent>
        <DialogActions><Button color="inherit" onClick={() => setConfirm(null)}>Keep it</Button><Button color="error" disabled={remove.isPending} onClick={() => remove.mutate()}>Delete</Button></DialogActions>
      </Dialog>

      <Dialog open={confirm === 'use'} onClose={() => setConfirm(null)}>
        <DialogTitle>Move the server to &ldquo;{row?.name}&rdquo;?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Everyone using this server will be working on <strong>{row?.dbName}</strong> from now on, and has to sign in again, because people are read from that database.
            If your own sign-in is not in it, you will be asked to sign in with the bootstrap account. The change is recorded in the audit log.
          </DialogContentText>
        </DialogContent>
        <DialogActions><Button color="inherit" onClick={() => setConfirm(null)}>Not now</Button><Button variant="contained" disabled={use.isPending} onClick={() => use.mutate()}>Move the server</Button></DialogActions>
      </Dialog>

      <Snackbar open={!!notice} autoHideDuration={6000} onClose={() => setNotice(null)} message={notice} />
    </Box>
  )
}

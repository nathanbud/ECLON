import {
  Alert, Autocomplete, Avatar, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle,
  FormControlLabel, IconButton, MenuItem, Skeleton, Switch, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs,
  TextField, Tooltip, Typography, type Theme,
} from '@mui/material'
import { visuallyHidden } from '@mui/utils'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useState, type ReactNode } from 'react'
import { FiCheck, FiEdit2, FiLock, FiMinus, FiSave, FiX } from 'react-icons/fi'
import {
  getUserProfile, getUserTransactionTypes, saveUserProfile, saveUserTransactionTypes,
  type AdminUser, type UserField, type UserSection,
} from './api'
import { glass } from './glass'
import { tileFill } from './theme'

const TABS = [
  { label: 'General', key: 'general' }, { label: 'Transaction Types', key: 'transaction-types' }, { label: 'Transactions', key: 'transactions' },
  { label: 'POS / Priority Queue', key: 'pos' }, { label: 'Client', key: 'client' }, { label: 'Receivables', key: 'receivables' },
  { label: 'Deposits', key: 'deposits' }, { label: 'Others', key: 'others' },
]

const dark = (t: Theme) => t.palette.mode === 'dark'
const fmtDate = (s: string) => new Date(`${s.slice(0, 10)}T00:00:00`).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })

// ---- edit state ---------------------------------------------------------------------------------------------------
// While editing, every change is kept here, keyed by column (or by "code|permission" for the transaction types table),
// together with the value it started from.

interface Edit {
  editing: boolean
  draft: Record<string, { from: string | null; to: string | null }>
  set: (key: string, from: string | null, to: string | null) => void
  trans: Record<string, { code: number; column: string; allowed: boolean; from: boolean }>
  setTrans: (code: number, column: string, from: boolean, allowed: boolean) => void
}
const EditCtx = createContext<Edit>({ editing: false, draft: {}, set: () => {}, trans: {}, setTrans: () => {} })
const useEdit = () => useContext(EditCtx)

function display(f: UserField): string | null {
  if (f.value == null) return null
  if (f.kind === 'date') return fmtDate(f.value)
  if (f.kind === 'money') {
    const n = Number(f.value)
    return Number.isNaN(n) ? f.value : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  }
  return f.value
}

const inputSx = (live: boolean) => ({
  '& .MuiOutlinedInput-root': { fontSize: 14, backgroundColor: live ? 'background.paper' : 'transparent' },
  '& .MuiOutlinedInput-notchedOutline': { borderColor: live ? 'text.disabled' : 'divider' },
  '& .MuiInputLabel-root': { fontSize: 13 },
  ...(live ? {} : { '& .MuiOutlinedInput-root:hover .MuiOutlinedInput-notchedOutline': { borderColor: 'divider' } }),
}) as const

/** One field of the account setup: read-only, or an input that fits its type while the person is being edited. */
function FieldInput({ f }: { f: UserField }) {
  const { editing, draft, set } = useEdit()
  const live = editing && f.editable
  const from = f.raw ?? null
  const current = draft[f.key] ? draft[f.key].to : from

  if (f.kind === 'bool') {
    const on = (live ? current : from) === '1'
    if (!live) return <Allowed on={on} label={f.label} />
    return (
      <FormControlLabel
        label={<Typography sx={{ fontSize: 13.5 }}>{f.label}</Typography>}
        control={<Checkbox size="small" checked={on} onChange={(e) => set(f.key, from, e.target.checked ? '1' : '0')} />}
      />
    )
  }

  if (f.kind === 'list') {
    const options = f.options ?? []
    const chosen = (current ?? '').split(',').map((x) => x.trim()).filter(Boolean)
    const label = (v: string) => options.find((o) => o.value === v)?.label ?? v
    if (!live) {
      return (
        <Box>
          <Typography sx={{ fontSize: 12, fontWeight: 500, mb: 0.5 }} color="text.secondary">{f.label}</Typography>
          {chosen.length
            ? <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>{chosen.map((v) => <Chip key={v} size="small" variant="outlined" label={label(v)} />)}</Box>
            : <Typography sx={{ fontSize: 14 }} color="text.disabled">—</Typography>}
        </Box>
      )
    }
    return (
      <Autocomplete
        multiple size="small" options={options.map((o) => o.value)} getOptionLabel={label} value={chosen}
        onChange={(_, v) => set(f.key, from, v.length ? v.join(',') : null)}
        renderInput={(params) => <TextField {...params} label={f.label} sx={inputSx(true)} slotProps={{ ...params.slotProps, inputLabel: { ...params.slotProps.inputLabel, shrink: true } }} />}
      />
    )
  }

  if (f.options && f.kind === 'text') {
    return (
      <TextField
        select label={f.label} value={live ? current ?? '' : from ?? ''} fullWidth sx={inputSx(live)}
        onChange={live ? (e) => set(f.key, from, e.target.value || null) : undefined}
        slotProps={{ input: { readOnly: !live }, inputLabel: { shrink: true }, select: { displayEmpty: true } }}
      >
        <MenuItem value=""><em>—</em></MenuItem>
        {f.options.map((o) => <MenuItem key={o.value} value={o.value}>{o.label}</MenuItem>)}
      </TextField>
    )
  }

  const text = live ? current ?? '' : display(f) ?? ''
  return (
    <TextField
      label={f.label} value={text} placeholder="—" fullWidth
      multiline={!live || (f.kind !== 'date' && f.kind !== 'number' && f.kind !== 'money')} maxRows={4}
      type={live && f.kind === 'date' ? 'date' : undefined}
      onChange={live ? (e) => set(f.key, from, e.target.value || null) : undefined}
      slotProps={{
        input: { readOnly: !live }, inputLabel: { shrink: true },
        htmlInput: live && (f.kind === 'number' || f.kind === 'money') ? { inputMode: f.kind === 'number' ? 'numeric' : 'decimal' } : {},
      }}
      sx={inputSx(live)}
    />
  )
}

/** Allowed or not, shown as an icon and a hidden word so colour is never the only signal. */
function Allowed({ on, label }: { on: boolean; label: string }) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0, opacity: on ? 1 : 0.55 }}>
      <Box aria-hidden sx={{ display: 'flex', color: on ? 'success.main' : 'text.disabled', fontSize: 16 }}>{on ? <FiCheck /> : <FiMinus />}</Box>
      <Typography sx={{ fontSize: 13.5 }}>{label}</Typography>
      <span style={visuallyHidden}>{on ? 'allowed' : 'not allowed'}</span>
    </Box>
  )
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box sx={(t) => ({ p: 1.5, mb: 1.5, breakInside: 'avoid', ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 1 }} color="text.secondary">{title}</Typography>
      {children}
    </Box>
  )
}

function Sections({ sections }: { sections: UserSection[] }) {
  return (
    <Box sx={{ columns: '340px', columnGap: '12px' }}>
      {sections.map((sec) => {
        const flags = sec.fields.filter((f) => f.kind === 'bool')
        const lists = sec.fields.filter((f) => f.kind === 'list')
        const values = sec.fields.filter((f) => f.kind !== 'bool' && f.kind !== 'list')
        return (
          <Card key={sec.title} title={sec.title}>
            {values.length > 0 && (
              <Box sx={{ display: 'grid', gap: '12px 14px', gridTemplateColumns: 'repeat(auto-fill, minmax(132px, 1fr))', mb: flags.length || lists.length ? 1.5 : 0 }}>
                {values.map((f) => <FieldInput key={f.key} f={f} />)}
              </Box>
            )}
            {lists.map((f) => <Box key={f.key} sx={{ mb: flags.length ? 2 : 0 }}><FieldInput f={f} /></Box>)}
            {flags.length > 0 && (
              <Box sx={{ display: 'grid', gap: 0.75, gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))' }}>
                {flags.map((f) => <FieldInput key={f.key} f={f} />)}
              </Box>
            )}
          </Card>
        )
      })}
    </Box>
  )
}

function TransactionTypesTab({ username }: { username: string }) {
  const { editing, trans, setTrans } = useEdit()
  const { data, error, isLoading } = useQuery({ queryKey: ['user-transaction-types', username], queryFn: () => getUserTransactionTypes(username), staleTime: 60_000 })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={240} />
  if (!data || data.rows.length === 0) return <Alert severity="info">No transaction type permissions are set for this person.</Alert>
  const live = editing && data.canEdit
  return (
    <Box sx={(t) => ({ overflow: 'auto', maxHeight: '64vh', ...glass(t) })}>
      <Table size="small" stickyHeader aria-label="Transaction type permissions">
        <TableHead>
          <TableRow>
            <TableCell sx={{ minWidth: 200 }}>Transaction type</TableCell>
            {data.columns.map((c) => (
              <TableCell key={c} align="center" sx={{ p: 0.5, height: 120, verticalAlign: 'bottom' }}>
                <Box component="span" sx={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)', whiteSpace: 'nowrap', fontSize: 11.5, display: 'inline-block' }}>{c}</Box>
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {data.rows.map((r) => (
            <TableRow key={r.code} hover>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{r.name}</TableCell>
              {r.allowed.map((was, i) => {
                const column = data.keys[i]
                const change = trans[`${r.code}|${column}`]
                const on = change ? change.allowed : was
                return (
                  <TableCell key={column} align="center" sx={{ p: 0.5, bgcolor: change ? 'action.selected' : undefined }}>
                    {live
                      ? <Checkbox size="small" checked={on} onChange={(e) => setTrans(r.code, column, was, e.target.checked)} sx={{ p: 0.25 }} slotProps={{ input: { 'aria-label': `${r.name}: ${data.columns[i]}` } }} />
                      : (
                        <>
                          <Box aria-hidden sx={{ display: 'flex', justifyContent: 'center', color: on ? 'success.main' : 'text.disabled', fontSize: 14, opacity: on ? 1 : 0.4 }}>{on ? <FiCheck /> : <FiMinus />}</Box>
                          <span style={visuallyHidden}>{`${data.columns[i]}: ${on ? 'allowed' : 'not allowed'}`}</span>
                        </>
                      )}
                  </TableCell>
                )
              })}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  )
}

/**
 * A person's account setup, like the iAccs "Setup User Account" window: general settings and permission tabs.
 * Administrators can edit it (Edit, then Save), when editing is on and the account is not their own. The app password
 * and the Membership Officer role, which this app owns, are on the General tab.
 */
export default function UserProfileDialog({ user, onClose, onSetPassword, onToggleOfficer, onNotice, busy }: {
  user: AdminUser | null
  onClose: () => void
  onSetPassword: (u: AdminUser) => void
  onToggleOfficer: (u: AdminUser, on: boolean) => void
  onNotice: (message: string) => void
  busy: boolean
}) {
  const qc = useQueryClient()
  const [tab, setTab] = useState(0)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<Edit['draft']>({})
  const [trans, setTransDraft] = useState<Edit['trans']>({})
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [discarding, setDiscarding] = useState<null | 'cancel' | 'close'>(null)

  const username = user?.username ?? ''
  const { data: profile, error: loadError, isLoading } = useQuery({
    queryKey: ['user-profile', username], queryFn: () => getUserProfile(username), enabled: !!user, staleTime: 30_000,
  })
  const officer = profile?.roles.includes('MEMBERSHIP_OFFICER') ?? false
  const key = TABS[tab].key
  const changes = Object.keys(draft).length + Object.keys(trans).length

  const set: Edit['set'] = (k, from, to) =>
    setDraft((d) => {
      const next = { ...d }
      if ((from ?? '') === (to ?? '')) delete next[k]
      else next[k] = { from, to }
      return next
    })
  const setTrans: Edit['setTrans'] = (code, column, from, allowed) =>
    setTransDraft((d) => {
      const next = { ...d }
      const k = `${code}|${column}`
      if (from === allowed) delete next[k]
      else next[k] = { code, column, allowed, from }
      return next
    })

  const leave = () => { setEditing(false); setDraft({}); setTransDraft({}); setError(null); setDiscarding(null) }
  const cancel = () => (changes > 0 ? setDiscarding('cancel') : leave())
  const requestClose = () => { if (editing && changes > 0) setDiscarding('close'); else { leave(); onClose() } }

  const save = async () => {
    if (!user || changes === 0) return
    setSaving(true)
    setError(null)
    try {
      if (Object.keys(draft).length) await saveUserProfile(user.username, draft)
      if (Object.keys(trans).length) await saveUserTransactionTypes(user.username, Object.values(trans).map(({ code, column, allowed }) => ({ code, column, allowed })))
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['user-profile', user.username] }),
        qc.invalidateQueries({ queryKey: ['user-transaction-types', user.username] }),
        qc.invalidateQueries({ queryKey: ['users'] }),
      ])
      leave()
      onNotice(`Saved changes to ${user.username}`)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setSaving(false)
    }
  }

  const editReason = profile && !profile.canEdit ? profile.editNote ?? 'This account cannot be edited.' : null

  return (
    <EditCtx.Provider value={{ editing, draft, set, trans, setTrans }}>
      <Dialog
        open={!!user} onClose={requestClose} fullWidth maxWidth="xl" aria-labelledby="user-profile-title"
        slotProps={{
          transition: { onExited: () => { setTab(0); leave() } },
          backdrop: { sx: (t: Theme) => ({ backdropFilter: 'blur(10px)', backgroundColor: dark(t) ? 'rgba(0,0,0,0.5)' : 'rgba(40,50,80,0.28)' }) },
          paper: { sx: (t: Theme) => ({ ...glass(t, { elevated: true }), height: '94vh', overflow: 'hidden', display: 'flex', flexDirection: 'column' }) },
        }}
      >
        {/* Header */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2.5, py: 1.25 }}>
          <Avatar variant="rounded" sx={{ width: 40, height: 40, fontSize: 15, fontWeight: 600, color: '#fff', bgcolor: tileFill.indigo }}>
            {(profile?.name ?? username).split(' ').map((p) => p[0]).slice(0, 2).join('').toUpperCase()}
          </Avatar>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography id="user-profile-title" component="h2" noWrap sx={{ fontSize: 18, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.2 }}>{profile?.name ?? username} <Typography component="span" sx={{ fontSize: 13, fontWeight: 400 }} color="text.secondary">{username}</Typography></Typography>
          </Box>
          {/* Toolbar, as in the old window */}
          <Box role="toolbar" aria-label="Account actions" sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
            <Tooltip title={editReason ?? 'Edit this account'}>
              <span>
                <Button size="small" startIcon={<FiEdit2 />} disabled={!profile?.canEdit || editing} onClick={() => { setEditing(true); setError(null) }}>Edit</Button>
              </span>
            </Tooltip>
            <Button size="small" variant={editing ? 'contained' : 'text'} startIcon={<FiSave />} disabled={!editing || changes === 0 || saving} onClick={save}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
            <Button size="small" startIcon={<FiX />} disabled={!editing || saving} onClick={cancel}>Cancel</Button>
          </Box>
          {(editing || editReason) && (
            <Typography sx={{ fontSize: 12.5, maxWidth: 260 }} color="text.secondary" role="status">
              {editing ? (changes === 0 ? 'No changes yet.' : `${changes} change${changes === 1 ? '' : 's'} not saved.`) : editReason}
            </Typography>
          )}
          {profile && (
            <Box sx={{ display: 'flex', gap: 0.75 }}>
              {editing && <Chip size="small" color="primary" icon={<FiEdit2 />} label="Editing" />}
              <Chip size="small" variant="outlined" color={profile.enabled ? 'success' : 'default'} label={profile.enabled ? 'Enabled' : 'Disabled'} />
              {officer && <Chip size="small" color="primary" label="Membership Officer" />}
            </Box>
          )}
          <Tooltip title="Close"><IconButton aria-label="Close" onClick={requestClose}><FiX /></IconButton></Tooltip>
        </Box>

        <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons="auto" aria-label="Account setup sections"
          sx={{ px: 2, borderTop: 1, borderBottom: 1, borderColor: 'divider', minHeight: 40, '& .MuiTab-root': { minHeight: 40, fontSize: 13.5 } }}>
          {TABS.map((t) => <Tab key={t.key} label={t.label} />)}
        </Tabs>

        <DialogContent sx={{ flex: 1, p: 1.5 }}>
          {error && <Alert severity="error" sx={{ mb: 1.5 }}>{error}</Alert>}
          {loadError && <Alert severity="error">{(loadError as Error).message}</Alert>}
          {isLoading && <Skeleton variant="rounded" height={260} />}
          {profile && user && (
            <>
              {key === 'general' && (
                <Box sx={(t) => ({ mb: 1.5, p: 2, display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap', ...glass(t) })}>
                  <Box sx={{ flex: 1, minWidth: 220 }}>
                    <Typography sx={{ fontWeight: 600, fontSize: 14 }}>Sign-in for this app</Typography>
                    <Typography sx={{ fontSize: 12.5 }} color="text.secondary">
                      This is separate from the old iAccs password, which does not work here.
                    </Typography>
                  </Box>
                  <Chip size="small" variant="outlined" color={profile.hasPassword ? 'success' : 'default'} label={profile.hasPassword ? 'Password set' : 'No password yet'} />
                  <Button size="small" variant="outlined" startIcon={<FiLock />} disabled={!profile.enabled} onClick={() => onSetPassword(user)}>
                    {profile.hasPassword ? 'Reset password' : 'Set password'}
                  </Button>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                    <Typography id="officer-label" sx={{ fontSize: 13.5 }}>Membership Officer</Typography>
                    <Switch checked={officer} disabled={!profile.enabled || busy} onChange={(_, on) => onToggleOfficer(user, on)} slotProps={{ input: { 'aria-labelledby': 'officer-label' } }} />
                  </Box>
                </Box>
              )}
              {key === 'transaction-types' ? <TransactionTypesTab username={username} /> : <Sections sections={profile.tabs[key] ?? []} />}
              <Typography sx={{ mt: 2, fontSize: 12.5 }} color="text.secondary">
                Settings come from iAccs. Changes saved here are written to it and recorded in the audit log.
              </Typography>
            </>
          )}
        </DialogContent>
      </Dialog>

      <Dialog open={discarding !== null} onClose={() => setDiscarding(null)} aria-labelledby="discard-user-title">
        <DialogTitle id="discard-user-title">Discard changes?</DialogTitle>
        <DialogContent><DialogContentText>{changes} change{changes === 1 ? '' : 's'} will be lost.</DialogContentText></DialogContent>
        <DialogActions>
          <Button onClick={() => setDiscarding(null)} autoFocus>Keep editing</Button>
          <Button color="error" onClick={() => { const closing = discarding === 'close'; leave(); if (closing) onClose() }}>Discard</Button>
        </DialogActions>
      </Dialog>
    </EditCtx.Provider>
  )
}

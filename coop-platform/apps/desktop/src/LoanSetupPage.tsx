import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Skeleton, Snackbar, Switch, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs,
  TextField, Tooltip, Typography,
} from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  createLoanType, deleteSetupNamed, getHoldSetup, setHoldable, setHoldDefault, getLoanSetupLists, getLoanSetupTypes, getSetupCollateral, getSetupPurposes, saveSetupNamed, updateLoanType,
  type HoldSetupRow, type LoanSetupForm, type LoanSetupLists, type LoanSetupType, type SetupNamed,
} from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'

const num = { fontVariantNumeric: 'tabular-nums' } as const
const pct = (v: number | null) => (v == null ? '—' : `${v.toFixed(2)}%`)

const blank: LoanSetupForm = {
  name: '', fullName: '', terms: null, termUnit: null, principalMode: null, interestMode: null, amortization: null, interestRate: null, penaltyRate: null,
  serviceFeeRate: null, minCoMakers: null, requireCollateral: false, priority: null, yearDays: 365, disabled: false, reason: '',
}

const toForm = (t: LoanSetupType): LoanSetupForm => ({
  name: t.name, fullName: t.fullName, terms: t.terms, termUnit: t.termUnit, principalMode: t.principalMode, interestMode: t.interestMode, amortization: t.amortization,
  interestRate: t.interestRate, penaltyRate: t.penaltyRate, serviceFeeRate: t.serviceFeeRate, minCoMakers: t.minCoMakers, requireCollateral: t.requireCollateral,
  priority: t.priority, yearDays: t.yearDays, disabled: t.disabled, reason: '',
})

/** Create or edit one loan type. A new type starts as a copy of an existing one, which also brings its ledger accounts and deductions. */
function TypeDialog({ editing, creating, types, lists, onClose, onDone }: {
  editing: LoanSetupType | null; creating: boolean; types: LoanSetupType[]; lists: LoanSetupLists | undefined; onClose: () => void; onDone: (message: string) => void
}) {
  const open = creating || editing != null
  const [form, setForm] = useState<LoanSetupForm>(blank)
  const [copyFrom, setCopyFrom] = useState<number | ''>('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [seeded, setSeeded] = useState<string>('')
  // Fill the form once per opening (not on every render, which would undo typing).
  const key = editing ? `e${editing.code}` : creating ? 'new' : ''
  if (open && seeded !== key) {
    setSeeded(key)
    setForm(editing ? toForm(editing) : blank)
    setCopyFrom('')
    setError(null)
  }
  if (!open && seeded !== '') setSeeded('')
  const set = <K extends keyof LoanSetupForm>(k: K, v: LoanSetupForm[K]) => setForm((f) => ({ ...f, [k]: v }))
  const numberOrNull = (s: string) => (s.trim() === '' ? null : Number(s))
  const copyChosen = (code: number | '') => {
    setCopyFrom(code)
    const src = types.find((t) => t.code === code)
    if (src) setForm({ ...toForm(src), name: '', fullName: '', disabled: false, reason: form.reason })
  }
  const submit = async () => {
    setBusy(true)
    setError(null)
    try {
      if (editing) { await updateLoanType(editing.code, form); onDone(`${form.name} was saved`) }
      else { await createLoanType(Number(copyFrom), form); onDone(`${form.name} was created`) }
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }
  const ready = form.name.trim() !== '' && form.reason.trim() !== '' && (editing != null || copyFrom !== '')

  const select = (label: string, k: 'termUnit' | 'principalMode' | 'interestMode' | 'amortization', options: { id: number; name: string }[] = []) => (
    <TextField select label={label} value={form[k] ?? ''} onChange={(e) => set(k, e.target.value === '' ? null : Number(e.target.value))}>
      <MenuItem value="">—</MenuItem>
      {options.map((o) => <MenuItem key={o.id} value={o.id}>{o.name}</MenuItem>)}
    </TextField>
  )

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} fullWidth maxWidth="md" aria-labelledby="type-title">
      <DialogTitle id="type-title">{editing ? `Edit ${editing.name}` : 'New loan type'}</DialogTitle>
      <form onSubmit={(e) => { e.preventDefault(); if (ready) void submit() }}>
        <DialogContent>
          {error && <Alert severity="error" sx={{ mb: 1.5 }}>{error}</Alert>}
          {editing && (editing.loans > 0 || editing.applications > 0) && (
            <Alert severity="info" sx={{ mb: 1.5 }}>{editing.loans.toLocaleString()} loans and {editing.applications.toLocaleString()} applications use this type. Changes apply to new applications only; a loan keeps the setup it was made with.</Alert>
          )}
          {!editing && (
            <TextField select fullWidth required label="Start from a copy of" value={copyFrom} onChange={(e) => copyChosen(e.target.value === '' ? '' : Number(e.target.value))} sx={{ mb: 1.5 }}
              helperText="The copy brings the general ledger accounts and the deductions of that type. You set the rest below.">
              {types.map((t) => <MenuItem key={t.code} value={t.code}>{t.name}{t.disabled ? ' (disabled)' : ''}</MenuItem>)}
            </TextField>
          )}
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 1.5 }}>
            <TextField required label="Name" value={form.name} onChange={(e) => set('name', e.target.value)} slotProps={{ htmlInput: { maxLength: 20 } }} helperText="Up to 20 characters" />
            <TextField label="Full name" value={form.fullName ?? ''} onChange={(e) => set('fullName', e.target.value)} slotProps={{ htmlInput: { maxLength: 30 } }} sx={{ gridColumn: 'span 2' }} />
            <TextField type="number" label="Term" value={form.terms ?? ''} onChange={(e) => set('terms', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 1, max: 600 } }} />
            {select('Term counted in', 'termUnit', lists?.termUnits)}
            {select('Amortization', 'amortization', lists?.amortizations)}
            {select('Principal paid', 'principalMode', lists?.paymentModes)}
            {select('Interest paid', 'interestMode', lists?.paymentModes)}
            <TextField select label="Days in a year for interest" value={form.yearDays ?? ''} onChange={(e) => set('yearDays', e.target.value === '' ? null : Number(e.target.value))}>
              <MenuItem value="">—</MenuItem><MenuItem value={365}>365</MenuItem><MenuItem value={360}>360</MenuItem>
            </TextField>
            <TextField type="number" label="Interest rate (% a year)" value={form.interestRate ?? ''} onChange={(e) => set('interestRate', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 0, max: 100, step: 0.01 } }} />
            <TextField type="number" label="Penalty rate (%)" value={form.penaltyRate ?? ''} onChange={(e) => set('penaltyRate', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 0, max: 100, step: 0.01 } }} />
            <TextField type="number" label="Service fee (%)" value={form.serviceFeeRate ?? ''} onChange={(e) => set('serviceFeeRate', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 0, max: 100, step: 0.01 } }} />
            <TextField type="number" label="Minimum co-makers" value={form.minCoMakers ?? ''} onChange={(e) => set('minCoMakers', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 0, max: 20 } }} />
            <TextField type="number" label="Order in lists" value={form.priority ?? ''} onChange={(e) => set('priority', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 0, max: 999 } }} />
            <FormControlLabel control={<Switch checked={form.requireCollateral} onChange={(e) => set('requireCollateral', e.target.checked)} />} label="Collateral required" />
            {editing && <FormControlLabel control={<Switch checked={form.disabled} onChange={(e) => set('disabled', e.target.checked)} />} label="Disabled (hidden from new applications)" sx={{ gridColumn: 'span 2' }} />}
          </Box>
          <TextField required fullWidth label="Reason for this change" value={form.reason} onChange={(e) => set('reason', e.target.value)} sx={{ mt: 2 }} slotProps={{ htmlInput: { maxLength: 255 } }}
            helperText="Kept in the audit log with the values before and after" />
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={busy} color="inherit">Cancel</Button>
          <Button type="submit" variant="contained" disabled={busy || !ready}>{editing ? 'Save' : 'Create'}</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

function TypesTab({ lists, onNotice }: { lists: LoanSetupLists | undefined; onNotice: (m: string) => void }) {
  const qc = useQueryClient()
  const { data, error, isLoading } = useQuery({ queryKey: ['loan-setup-types'], queryFn: getLoanSetupTypes })
  const [editing, setEditing] = useState<LoanSetupType | null>(null)
  const [creating, setCreating] = useState(false)
  const unit = (id: number | null) => lists?.termUnits.find((u) => u.id === id)?.name ?? ''
  const close = () => { setEditing(null); setCreating(false) }
  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 1.25 }}>
        <Typography color="text.secondary" sx={{ flex: 1, fontSize: 13 }}>A change here applies to new applications. Loans already made keep the setup they were made with.</Typography>
        <Button variant="contained" onClick={() => setCreating(true)}>New loan type</Button>
      </Box>
      {error && <Alert severity="error">{(error as Error).message}</Alert>}
      <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
        <Table size="small" aria-label="Loan types">
          <TableHead><TableRow>
            <TableCell>Name</TableCell><TableCell>Term</TableCell><TableCell align="right">Interest</TableCell><TableCell align="right">Penalty</TableCell><TableCell align="right">Service fee</TableCell>
            <TableCell align="right">Co-makers</TableCell><TableCell align="right">Loans</TableCell><TableCell>Status</TableCell><TableCell />
          </TableRow></TableHead>
          <TableBody>
            {isLoading && Array.from({ length: 6 }, (_, i) => <TableRow key={i}><TableCell colSpan={9}><Skeleton /></TableCell></TableRow>)}
            {data?.map((t) => (
              <TableRow key={t.code} hover sx={{ opacity: t.disabled ? 0.55 : 1 }}>
                <TableCell sx={{ fontWeight: 600 }}>{t.name}</TableCell>
                <TableCell sx={num}>{t.terms == null ? '—' : `${t.terms} ${unit(t.termUnit).toLowerCase()}`}</TableCell>
                <TableCell align="right" sx={num}>{pct(t.interestRate)}</TableCell>
                <TableCell align="right" sx={num}>{pct(t.penaltyRate)}</TableCell>
                <TableCell align="right" sx={num}>{pct(t.serviceFeeRate)}</TableCell>
                <TableCell align="right" sx={num}>{t.minCoMakers ?? '—'}</TableCell>
                <TableCell align="right" sx={num}>{t.loans.toLocaleString()}</TableCell>
                <TableCell>
                  {t.disabled ? <Chip size="small" label="Disabled" />
                    : t.unavailable ? <Tooltip title={t.unavailable}><Chip size="small" color="warning" variant="outlined" label="Not yet in application screen" /></Tooltip>
                    : <Chip size="small" color="success" variant="outlined" label="Active" />}
                </TableCell>
                <TableCell align="right"><Button size="small" onClick={() => setEditing(t)}>Edit</Button></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
      <TypeDialog editing={editing} creating={creating} types={data ?? []} lists={lists} onClose={close}
        onDone={(m) => { close(); onNotice(m); void qc.invalidateQueries({ queryKey: ['loan-setup-types'] }); void qc.invalidateQueries({ queryKey: ['loan-types'] }); void qc.invalidateQueries({ queryKey: ['audit-log'] }) }} />
    </>
  )
}

/** Loan purposes and collateral types: a name each (purposes also belong to a loan class). Unused entries can be deleted. */
function NamedTab({ kind, noun, lists, onNotice }: { kind: 'purposes' | 'collateral-types'; noun: string; lists: LoanSetupLists | undefined; onNotice: (m: string) => void }) {
  const qc = useQueryClient()
  const { data, error, isLoading } = useQuery({ queryKey: ['loan-setup', kind], queryFn: kind === 'purposes' ? getSetupPurposes : getSetupCollateral })
  const [row, setRow] = useState<SetupNamed | 'new' | null>(null)
  const [deleting, setDeleting] = useState<SetupNamed | null>(null)
  const [name, setName] = useState('')
  const [cls, setCls] = useState<number>(1)
  const [reason, setReason] = useState('')
  const [fail, setFail] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const className = (id: number | null) => lists?.loanClasses.find((c) => c.id === id)?.name ?? ''
  const open = (r: SetupNamed | 'new') => { setRow(r); setName(r === 'new' ? '' : r.name); setCls(r === 'new' ? 1 : r.loanClass ?? 1); setReason(''); setFail(null) }
  const done = (m: string) => { setRow(null); setDeleting(null); onNotice(m); void qc.invalidateQueries({ queryKey: ['loan-setup', kind] }); void qc.invalidateQueries({ queryKey: ['audit-log'] }) }
  const run = async (f: () => Promise<unknown>, message: string) => {
    setBusy(true)
    setFail(null)
    try { await f(); done(message) } catch (e) { setFail((e as Error).message) } finally { setBusy(false) }
  }
  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 1.25 }}>
        <Typography color="text.secondary" sx={{ flex: 1, fontSize: 13 }}>Entries that loans use can be renamed but not deleted.</Typography>
        <Button variant="contained" onClick={() => open('new')}>Add {noun}</Button>
      </Box>
      {error && <Alert severity="error">{(error as Error).message}</Alert>}
      <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
        <Table size="small" aria-label={noun}>
          <TableHead><TableRow><TableCell>Name</TableCell>{kind === 'purposes' && <TableCell>Loan class</TableCell>}<TableCell align="right">Used by loans</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {isLoading && Array.from({ length: 5 }, (_, i) => <TableRow key={i}><TableCell colSpan={4}><Skeleton /></TableCell></TableRow>)}
            {data?.map((r) => (
              <TableRow key={r.id} hover>
                <TableCell>{r.name}</TableCell>
                {kind === 'purposes' && <TableCell>{className(r.loanClass)}</TableCell>}
                <TableCell align="right" sx={num}>{r.uses.toLocaleString()}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <Button size="small" onClick={() => open(r)}>Rename</Button>
                  <Tooltip title={r.uses > 0 ? 'Loans use this, so it cannot be deleted' : ''}>
                    <span><Button size="small" color="error" disabled={r.uses > 0} onClick={() => { setDeleting(r); setReason(''); setFail(null) }}>Delete</Button></span>
                  </Tooltip>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
      <Dialog open={row != null} onClose={busy ? undefined : () => setRow(null)} fullWidth maxWidth="xs" aria-labelledby="named-title">
        <DialogTitle id="named-title">{row === 'new' ? `Add ${noun}` : `Rename ${noun}`}</DialogTitle>
        <form onSubmit={(e) => { e.preventDefault(); if (name.trim() && reason.trim()) void run(() => saveSetupNamed(kind, row === 'new' || row == null ? null : row.id, { name, loanClass: kind === 'purposes' ? cls : null, reason }), `${name} was saved`) }}>
          <DialogContent sx={{ display: 'grid', gap: 1.5 }}>
            {fail && <Alert severity="error">{fail}</Alert>}
            <TextField autoFocus required label="Name" value={name} onChange={(e) => setName(e.target.value)} slotProps={{ htmlInput: { maxLength: 45 } }} />
            {kind === 'purposes' && (
              <TextField select label="Loan class" value={cls} onChange={(e) => setCls(Number(e.target.value))}>
                {lists?.loanClasses.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}
              </TextField>
            )}
            <TextField required label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} />
          </DialogContent>
          <DialogActions>
            <Button color="inherit" onClick={() => setRow(null)} disabled={busy}>Cancel</Button>
            <Button type="submit" variant="contained" disabled={busy || !name.trim() || !reason.trim()}>Save</Button>
          </DialogActions>
        </form>
      </Dialog>
      <Dialog open={deleting != null} onClose={busy ? undefined : () => setDeleting(null)} fullWidth maxWidth="xs" aria-labelledby="del-title">
        <DialogTitle id="del-title">Delete {deleting?.name}?</DialogTitle>
        <form onSubmit={(e) => { e.preventDefault(); if (deleting && reason.trim()) void run(() => deleteSetupNamed(kind, deleting.id, reason), `${deleting.name} was deleted`) }}>
          <DialogContent sx={{ display: 'grid', gap: 1.5 }}>
            {fail && <Alert severity="error">{fail}</Alert>}
            <TextField autoFocus required label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} />
          </DialogContent>
          <DialogActions>
            <Button color="inherit" onClick={() => setDeleting(null)} disabled={busy}>Cancel</Button>
            <Button type="submit" color="error" variant="contained" disabled={busy || !reason.trim()}>Delete</Button>
          </DialogActions>
        </form>
      </Dialog>
    </>
  )
}

/**
 * Which savings and share capital types a co-maker's agreed amount may be held on, and which one the loan application suggests first. A type
 * switched off is not offered for new holds; holds already placed stay.
 */
function HoldsTab({ onNotice }: { onNotice: (m: string) => void }) {
  const qc = useQueryClient()
  const { data, error, isLoading } = useQuery({ queryKey: ['hold-setup'], queryFn: getHoldSetup })
  const [ask, setAsk] = useState<{ row: HoldSetupRow; what: 'allow' | 'forbid' | 'default' } | null>(null)
  const [reason, setReason] = useState('')
  const [fail, setFail] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const open = (row: HoldSetupRow, what: 'allow' | 'forbid' | 'default') => { setAsk({ row, what }); setReason(''); setFail(null) }
  const submit = async () => {
    if (!ask) return
    setBusy(true)
    setFail(null)
    try {
      if (ask.what === 'default') await setHoldDefault(ask.row.slc, ask.row.slt, reason)
      else await setHoldable(ask.row.slc, ask.row.slt, ask.what === 'allow', reason)
      onNotice(ask.what === 'default' ? `${ask.row.name} is now suggested first` : `${ask.row.name} ${ask.what === 'allow' ? 'can now' : 'can no longer'} take holds`)
      setAsk(null)
      void qc.invalidateQueries({ queryKey: ['hold-setup'] })
      void qc.invalidateQueries({ queryKey: ['comaker-accounts'] })
      void qc.invalidateQueries({ queryKey: ['audit-log'] })
    } catch (e) {
      setFail((e as Error).message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <>
      <Typography color="text.secondary" sx={{ fontSize: 13, mb: 1.25 }}>
        When a co-maker agrees to cover an amount, it is held on one of their accounts. Choose the deposit types that can take such a hold, and the one suggested first.
        Money on hold cannot be withdrawn. Holds already placed on a type stay when it is switched off.
      </Typography>
      {error && <Alert severity="error">{(error as Error).message}</Alert>}
      <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
        <Table size="small" aria-label="Deposit types that can be held">
          <TableHead><TableRow><TableCell>Deposit</TableCell><TableCell>Kind</TableCell><TableCell align="right">Holds now</TableCell><TableCell>Can be held</TableCell><TableCell>Suggested first</TableCell></TableRow></TableHead>
          <TableBody>
            {isLoading && Array.from({ length: 6 }, (_, i) => <TableRow key={i}><TableCell colSpan={5}><Skeleton /></TableCell></TableRow>)}
            {data?.map((r) => (
              <TableRow key={`${r.slc}-${r.slt}`} hover>
                <TableCell sx={{ fontWeight: 600 }}>{r.name}</TableCell>
                <TableCell>{r.group}</TableCell>
                <TableCell align="right" sx={num}>{r.holds.toLocaleString()}</TableCell>
                <TableCell><Switch checked={r.holdable} onChange={() => open(r, r.holdable ? 'forbid' : 'allow')} slotProps={{ input: { 'aria-label': `${r.name} can be held` } }} /></TableCell>
                <TableCell>
                  {r.isDefault ? <Chip size="small" color="primary" label="Suggested first" />
                    : <Button size="small" disabled={!r.holdable} onClick={() => open(r, 'default')}>Suggest first</Button>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
      <Dialog open={ask != null} onClose={busy ? undefined : () => setAsk(null)} fullWidth maxWidth="xs" aria-labelledby="hold-title">
        <DialogTitle id="hold-title">
          {ask?.what === 'default' ? `Suggest ${ask.row.name} first` : ask?.what === 'allow' ? `Allow holds on ${ask?.row.name}` : `Stop holds on ${ask?.row.name}`}
        </DialogTitle>
        <form onSubmit={(e) => { e.preventDefault(); if (reason.trim()) void submit() }}>
          <DialogContent sx={{ display: 'grid', gap: 1.5 }}>
            {fail && <Alert severity="error">{fail}</Alert>}
            <TextField autoFocus required label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} helperText="Kept in the audit log" />
          </DialogContent>
          <DialogActions>
            <Button color="inherit" onClick={() => setAsk(null)} disabled={busy}>Cancel</Button>
            <Button type="submit" variant="contained" disabled={busy || !reason.trim()}>Save</Button>
          </DialogActions>
        </form>
      </Dialog>
    </>
  )
}

/** Loan setup for administrators: loan types and the lists clerks choose from. Every change is audited. */
export default function LoanSetupPage() {
  const me = useMe()
  const [tab, setTab] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)
  const lists = useQuery({ queryKey: ['loan-setup-lists'], queryFn: getLoanSetupLists, enabled: me.admin, staleTime: 5 * 60_000 })
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1200, mx: 'auto' }}>
        <Typography variant="h5" component="h1" sx={{ mb: 0.5 }}>Loan setup</Typography>
        <Typography color="text.secondary" sx={{ fontSize: 13, mb: 1.5 }}>The loan products and the lists behind the loan application. Administrators only; each change asks for a reason and is kept in the audit log.</Typography>
        {!me.admin ? <Alert severity="info">Only administrators can change the loan setup.</Alert> : (
          <>
            <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 1.5, borderBottom: 1, borderColor: 'divider' }}>
              <Tab label="Loan types" /><Tab label="Loan purposes" /><Tab label="Collateral types" /><Tab label="Co-maker holds" />
            </Tabs>
            {tab === 0 && <TypesTab lists={lists.data} onNotice={setNotice} />}
            {tab === 1 && <NamedTab kind="purposes" noun="loan purpose" lists={lists.data} onNotice={setNotice} />}
            {tab === 2 && <NamedTab kind="collateral-types" noun="collateral type" lists={lists.data} onNotice={setNotice} />}
            {tab === 3 && <HoldsTab onNotice={setNotice} />}
          </>
        )}
      </Box>
      <Snackbar open={notice != null} autoHideDuration={4000} onClose={() => setNotice(null)} message={notice} />
    </Box>
  )
}

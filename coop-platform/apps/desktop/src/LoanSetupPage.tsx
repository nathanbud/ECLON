import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, InputAdornment, MenuItem, Skeleton, Snackbar, Switch, Table, TableBody, TableCell,
  Tab, TableHead, TableRow, Tabs, TextField, Tooltip, Typography,
} from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import { FiChevronRight, FiPlus } from 'react-icons/fi'
import {
  createLoanType, deleteSetupNamed, getHoldSetup, setHoldable, setHoldDefault, addLoanTypeLink, changeLoanTypeLink, getLoanSetupLists, getLoanSetupTypes, getLoanTypeAccounts, removeLoanTypeLink, getSetupCollateral, getSetupPurposes, saveSetupNamed, updateLoanType,
  type HoldSetupRow, type WiredAccount, type LoanSetupForm, type LoanSetupLists, type LoanSetupType, type SetupNamed,
} from './api'
import AmortizationTab from './AmortizationTab'
import LoanDeductions from './LoanDeductions'
import { AboutNote, Dot, Hinted, listTable, PageTitle, ReasonDialog, SearchField, SectionLabel, Segmented, Surface } from './appleUi'
import { appBackground } from './glass'
import { useMe } from './session'
import { tabular } from './ui/format'

const num = tabular
const pct = (v: number | null) => (v == null ? '—' : `${v.toFixed(2)}%`)

const blank: LoanSetupForm = {
  name: '', fullName: '', terms: null, termUnit: null, principalMode: null, interestMode: null, amortization: null, interestRate: null, penaltyRate: null,
  serviceFeeRate: null, minCoMakers: null, requireCollateral: false, priority: null, yearDays: 365, disabled: false, reason: '', dayCount: null,
}

const toForm = (t: LoanSetupType): LoanSetupForm => ({
  name: t.name, fullName: t.fullName, terms: t.terms, termUnit: t.termUnit, principalMode: t.principalMode, interestMode: t.interestMode, amortization: t.amortization,
  interestRate: t.interestRate, penaltyRate: t.penaltyRate, serviceFeeRate: t.serviceFeeRate, minCoMakers: t.minCoMakers, requireCollateral: t.requireCollateral,
  priority: t.priority, yearDays: t.yearDays, disabled: t.disabled, reason: '', dayCount: t.dayCount,
})

/** A titled group of fields inside the loan type form. Two columns of fields keep the whole form on one screen. */
function FormSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box component="fieldset" sx={{ border: 0, p: 0, m: 0, minWidth: 0 }}>
      <Typography component="legend" color="text.secondary" sx={{ fontSize: 11, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', mb: 0.75, p: 0 }}>{title}</Typography>
      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1.25 }}>{children}</Box>
    </Box>
  )
}

const LINK_KINDS: { event: number; status: number | null; label: string }[] = [
  { event: 11, status: 11, label: 'Principal · Current' }, { event: 11, status: 12, label: 'Principal · Past due' },
  { event: 11, status: 13, label: 'Principal · In litigation' }, { event: 11, status: 14, label: 'Principal · Restructured' },
  { event: 21, status: null, label: 'Accrued interest receivable' }, { event: 22, status: null, label: 'Unearned interest' },
  { event: 23, status: null, label: 'Interest paid (income)' }, { event: 24, status: null, label: 'Interest earned' },
  { event: 25, status: null, label: 'Interest waived' }, { event: 26, status: null, label: 'Interest rebate' },
  { event: 31, status: null, label: 'Service fee' }, { event: 36, status: null, label: 'Collection fee' }, { event: 37, status: null, label: 'Insurance' },
  { event: 41, status: null, label: 'Penalty / fines' }, { event: 42, status: null, label: 'Penalty waived' },
]

/** Where this loan type posts principal, interest, penalties, fees and the rest in the chart of accounts. An administrator can point a link at another account, add one that is missing or remove one that is not needed for posting. */
function WiredAccounts({ code }: { code: number }) {
  const qc = useQueryClient()
  const { data, error, isLoading } = useQuery({ queryKey: ['loan-type-accounts', code], queryFn: () => getLoanTypeAccounts(code), staleTime: 60_000 })
  const [reason, setReason] = useState('')
  const [editKey, setEditKey] = useState<string | null>(null)
  const [account, setAccount] = useState('')
  const [adding, setAdding] = useState('')
  const [fail, setFail] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const keyOf = (e: number, s: number | null) => `${e}-${s ?? 0}`
  const run = async (job: () => Promise<WiredAccount[]>) => {
    if (reason.trim().length < 3) { setFail('Give a reason for the change first.'); return }
    setBusy(true)
    try {
      qc.setQueryData(['loan-type-accounts', code], await job())
      setFail(null); setEditKey(null); setAccount(''); setAdding('')
    } catch (e) { setFail((e as Error).message) } finally { setBusy(false) }
  }
  const missing = LINK_KINDS.filter((k) => !data?.some((a) => keyOf(a.event, a.status) === keyOf(k.event, k.status)))
  return (
    <Box sx={{ mt: 1 }}>
      {error && <Alert severity="error" sx={{ py: 0 }}>{(error as Error).message}</Alert>}
      {fail && <Alert severity="error" sx={{ py: 0, mb: 1 }} onClose={() => setFail(null)}>{fail}</Alert>}
      {isLoading && <Skeleton variant="rounded" height={90} />}
      {data && data.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">No ledger accounts are set for this loan type, so nothing can be posted for it.</Typography>}
      {data && data.length > 0 && (
        <Table size="small" aria-label="Ledger accounts of this loan type" sx={{ '& td, & th': { py: 0.4 } }}>
          <TableHead><TableRow><TableCell>Posts</TableCell><TableCell>Account no.</TableCell><TableCell>Account</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {data.map((a) => {
              const k = keyOf(a.event, a.status)
              return (
                <TableRow key={k}>
                  <TableCell>{a.what}{a.statusName ? ` · ${a.statusName}` : ''}</TableCell>
                  <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {editKey === k
                      ? <TextField size="small" autoFocus value={account} onChange={(e) => setAccount(e.target.value)} sx={{ width: 130 }} slotProps={{ htmlInput: { 'aria-label': 'New account number', maxLength: 10 } }} />
                      : a.account}
                  </TableCell>
                  <TableCell sx={{ color: a.accountActive && a.accountName ? undefined : 'error.main' }}>
                    {a.accountName ?? 'Not in the chart of accounts'}{a.accountName && !a.accountActive ? ' (switched off)' : ''}
                  </TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {editKey === k ? (
                      <>
                        <Button size="small" disabled={busy || !account.trim()} onClick={() => void run(() => changeLoanTypeLink(code, { event: a.event, status: a.status, account, reason }))}>Save</Button>
                        <Button size="small" color="inherit" onClick={() => setEditKey(null)}>Cancel</Button>
                      </>
                    ) : (
                      <>
                        <Button size="small" disabled={busy} onClick={() => { setEditKey(k); setAccount(a.account) }}>Change</Button>
                        {a.removable && <Button size="small" color="error" disabled={busy} onClick={() => void run(() => removeLoanTypeLink(code, a.event, a.status, reason))}>Remove</Button>}
                      </>
                    )}
                  </TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      )}
      {missing.length > 0 && (
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', mt: 1.25 }}>
          <TextField select size="small" label="Add a link" value={adding} onChange={(e) => setAdding(e.target.value)} sx={{ minWidth: 230 }}>
            {missing.map((k) => <MenuItem key={keyOf(k.event, k.status)} value={keyOf(k.event, k.status)}>{k.label}</MenuItem>)}
          </TextField>
          {adding && <TextField size="small" label="Account no." value={account} onChange={(e) => setAccount(e.target.value)} sx={{ width: 140 }} slotProps={{ htmlInput: { maxLength: 10 } }} />}
          {adding && (
            <Button size="small" variant="outlined" disabled={busy || !account.trim()} onClick={() => {
              const k = missing.find((m) => keyOf(m.event, m.status) === adding)!
              void run(() => addLoanTypeLink(code, { event: k.event, status: k.status, account, reason }))
            }}>Add</Button>
          )}
        </Box>
      )}
      <Hinted tip="Kept in the audit log with the account before and after. New postings use the new account; entries already posted are not changed. Principal, interest paid, service fee and penalty can be changed but not removed."><TextField size="small" fullWidth label="Reason for a change (needed for Change, Remove and Add)" value={reason} onChange={(e) => setReason(e.target.value)} sx={{ mt: 1.5 }}
        slotProps={{ htmlInput: { maxLength: 255 } }} /></Hinted>
    </Box>
  )
}

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
  const [pane, setPane] = useState<'setup' | 'coa' | 'deductions'>('setup')
  // Fill the form once per opening (not on every render, which would undo typing).
  const key = editing ? `e${editing.code}` : creating ? 'new' : ''
  if (open && seeded !== key) {
    setSeeded(key)
    setForm(editing ? toForm(editing) : blank)
    setCopyFrom('')
    setError(null)
    setPane('setup')
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
    <TextField select size="small" label={label} value={form[k] ?? ''} onChange={(e) => set(k, e.target.value === '' ? null : Number(e.target.value))}>
      <MenuItem value="">—</MenuItem>
      {options.map((o) => <MenuItem key={o.id} value={o.id}>{o.name}</MenuItem>)}
    </TextField>
  )
  const rate = (label: string, k: 'interestRate' | 'penaltyRate' | 'serviceFeeRate') => (
    <TextField type="number" size="small" label={label} value={form[k] ?? ''} onChange={(e) => set(k, numberOrNull(e.target.value))}
      slotProps={{ htmlInput: { min: 0, max: 100, step: 0.01 }, input: { endAdornment: <InputAdornment position="end">%</InputAdornment> } }} />
  )

  const used = editing && (editing.loans > 0 || editing.applications > 0)

  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} fullWidth maxWidth="md" aria-labelledby="type-title">
      <form onSubmit={(e) => { e.preventDefault(); if (ready) void submit() }}>
        <DialogTitle id="type-title" sx={{ pb: 0.25 }}>{editing ? editing.name : 'New loan type'}</DialogTitle>
        {editing && (
          <Tabs value={pane} onChange={(_, v) => setPane(v)} aria-label="Loan type sections" sx={{ px: 2, minHeight: 38, borderBottom: 1, borderColor: 'divider', '& .MuiTab-root': { minHeight: 38, textTransform: 'none' } }}>
            <Tab value="setup" label="Setup" /><Tab value="coa" label="COA Links" /><Tab value="deductions" label="Deductions" />
          </Tabs>
        )}
        <DialogContent sx={{ pt: 0.5, pb: 1, overflow: 'visible' }}>
          <Box sx={{ display: pane !== 'setup' ? 'none' : undefined }}>
          {used && (
            <Typography color="text.secondary" sx={{ fontSize: 12, mb: 1.5 }}>
              {editing!.loans.toLocaleString()} loans and {editing!.applications.toLocaleString()} applications use this type. Changes apply to new applications only.
            </Typography>
          )}
          {error && <Alert severity="error" sx={{ mb: 1.25, py: 0 }}>{error}</Alert>}
          {!editing && (
            <Hinted tip="Brings that type's ledger accounts and deductions. Set the rest below."><TextField select fullWidth required size="small" label="Start from a copy of" value={copyFrom} onChange={(e) => copyChosen(e.target.value === '' ? '' : Number(e.target.value))} sx={{ mb: 1.75 }}
             >
              {types.map((t) => <MenuItem key={t.code} value={t.code}>{t.name}{t.disabled ? ' (disabled)' : ''}</MenuItem>)}
            </TextField></Hinted>
          )}
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' }, columnGap: 4, rowGap: 1.75, alignItems: 'start' }}>
            <Box sx={{ display: 'grid', gap: 1.75 }}>
              <FormSection title="Name">
                <TextField required size="small" label="Name" value={form.name} onChange={(e) => set('name', e.target.value)} slotProps={{ htmlInput: { maxLength: 20 } }} />
                <TextField size="small" label="Full name" value={form.fullName ?? ''} onChange={(e) => set('fullName', e.target.value)} slotProps={{ htmlInput: { maxLength: 30 } }} />
              </FormSection>
              <FormSection title="Term">
                <TextField type="number" size="small" label="Term" value={form.terms ?? ''} onChange={(e) => set('terms', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 1, max: 600 } }} />
                {select('Counted in', 'termUnit', lists?.termUnits)}
                {select('Amortization', 'amortization', lists?.amortizations)}
                <Hinted tip="Annuity: 30 days a month, or the real days"><TextField select size="small" label="Interest days" value={form.dayCount ?? ''} onChange={(e) => set('dayCount', e.target.value === '' ? null : Number(e.target.value))}
                 >
                  <MenuItem value="">As it is</MenuItem><MenuItem value={1}>30 days a month</MenuItem><MenuItem value={2}>Real days</MenuItem>
                </TextField></Hinted>
              </FormSection>
              <FormSection title="Payment">
                {select('Principal paid', 'principalMode', lists?.paymentModes)}
                {select('Interest paid', 'interestMode', lists?.paymentModes)}
              </FormSection>
            </Box>
            <Box sx={{ display: 'grid', gap: 1.75 }}>
              <FormSection title="Rates">
                {rate('Interest (a year)', 'interestRate')}
                {rate('Penalty', 'penaltyRate')}
                {rate('Service fee', 'serviceFeeRate')}
                <TextField select size="small" label="Days in a year" value={form.yearDays ?? ''} onChange={(e) => set('yearDays', e.target.value === '' ? null : Number(e.target.value))}>
                  <MenuItem value="">—</MenuItem><MenuItem value={365}>365</MenuItem><MenuItem value={360}>360</MenuItem>
                </TextField>
              </FormSection>
              <FormSection title="Rules">
                <TextField type="number" size="small" label="Minimum co-makers" value={form.minCoMakers ?? ''} onChange={(e) => set('minCoMakers', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 0, max: 20 } }} />
                <TextField type="number" size="small" label="Order in lists" value={form.priority ?? ''} onChange={(e) => set('priority', numberOrNull(e.target.value))} slotProps={{ htmlInput: { min: 0, max: 999 } }} />
                <Box sx={{ gridColumn: 'span 2', display: 'flex', flexWrap: 'wrap', columnGap: 3 }}>
                  <FormControlLabel control={<Switch size="small" checked={form.requireCollateral} onChange={(e) => set('requireCollateral', e.target.checked)} />} label={<Typography sx={{ fontSize: 13 }}>Collateral required</Typography>} />
                  {editing && <FormControlLabel control={<Switch size="small" checked={!form.disabled} onChange={(e) => set('disabled', !e.target.checked)} />} label={<Typography sx={{ fontSize: 13 }}>Available for new applications</Typography>} />}
                </Box>
              </FormSection>
            </Box>
          </Box>
          </Box>
          {editing && pane === 'coa' && <WiredAccounts code={editing.code} />}
          {editing && pane === 'deductions' && <LoanDeductions code={editing.code} />}
        </DialogContent>
        {pane !== 'setup' && editing ? (
          <DialogActions sx={{ px: 3, pb: 2, pt: 1 }}><Button onClick={onClose} color="inherit">Close</Button></DialogActions>
        ) : (
        <DialogActions sx={{ px: 3, pb: 2, pt: 1, gap: 1.5, alignItems: 'flex-start' }}>
          <Hinted tip="Kept in the audit log with the values before and after"><TextField required fullWidth size="small" label="Reason for this change" value={form.reason} onChange={(e) => set('reason', e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }}
            /></Hinted>
          <Button onClick={onClose} disabled={busy} color="inherit" sx={{ mt: 0.25 }}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={busy || !ready} sx={{ mt: 0.25 }}>{editing ? 'Save' : 'Create'}</Button>
        </DialogActions>
        )}
      </form>
    </Dialog>
  )
}

function TypesTab({ lists, onNotice }: { lists: LoanSetupLists | undefined; onNotice: (m: string) => void }) {
  const qc = useQueryClient()
  const { data, error, isLoading } = useQuery({ queryKey: ['loan-setup-types'], queryFn: getLoanSetupTypes })
  const [editing, setEditing] = useState<LoanSetupType | null>(null)
  const [creating, setCreating] = useState(false)
  const [q, setQ] = useState('')
  const [showOff, setShowOff] = useState(true)
  const unit = (id: number | null) => lists?.termUnits.find((u) => u.id === id)?.name ?? ''
  const close = () => { setEditing(null); setCreating(false) }

  const rows = useMemo(() => {
    const f = q.trim().toLowerCase()
    return (data ?? []).filter((t) => (showOff || !t.disabled) && (!f || `${t.name} ${t.fullName ?? ''}`.toLowerCase().includes(f)))
  }, [data, q, showOff])
  const counts = useMemo(() => {
    const all = data ?? []
    return { active: all.filter((t) => !t.disabled && !t.unavailable).length, pending: all.filter((t) => !t.disabled && t.unavailable).length, off: all.filter((t) => t.disabled).length }
  }, [data])
  const onKey = (e: KeyboardEvent, t: LoanSetupType) => { if (e.key === 'Enter' && e.target === e.currentTarget) setEditing(t) }

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 1.5, flexWrap: 'wrap' }}>
        <SearchField value={q} onChange={setQ} placeholder="Search loan types" />
        <FormControlLabel control={<Switch size="small" checked={showOff} onChange={(e) => setShowOff(e.target.checked)} />} label={<Typography sx={{ fontSize: 13 }}>Show disabled</Typography>} />
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" startIcon={<FiPlus />} onClick={() => setCreating(true)}>New loan type</Button>
      </Box>
      {error && <Alert severity="error" sx={{ mb: 1.5 }}>{(error as Error).message}</Alert>}
      <Surface>
        <Box sx={(t) => ({ overflow: 'auto', ...listTable(t) })}>
          <Table size="small" aria-label="Loan types">
            <TableHead><TableRow>
              <TableCell>Loan type</TableCell><TableCell>Term</TableCell><TableCell align="right">Interest</TableCell><TableCell align="right">Penalty</TableCell><TableCell align="right">Service fee</TableCell>
              <TableCell align="right">Co-makers</TableCell><TableCell align="right">Loans</TableCell><TableCell>Status</TableCell><TableCell width={32} />
            </TableRow></TableHead>
            <TableBody>
              {isLoading && Array.from({ length: 6 }, (_, i) => <TableRow key={i}><TableCell colSpan={9}><Skeleton /></TableCell></TableRow>)}
              {rows.map((t) => (
                <TableRow key={t.code} hover tabIndex={0} onClick={() => setEditing(t)} onKeyDown={(e) => onKey(e, t)} aria-label={`Edit ${t.name}`}
                  sx={{ cursor: 'pointer', opacity: t.disabled ? 0.55 : 1, '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: -2 } }}>
                  <TableCell>
                    <Typography sx={{ fontSize: 13, fontWeight: 600, lineHeight: 1.3 }}>{t.name}</Typography>
                    {t.fullName && t.fullName !== t.name && <Typography color="text.secondary" sx={{ fontSize: 12 }}>{t.fullName}</Typography>}
                  </TableCell>
                  <TableCell sx={num}>{t.terms == null ? '—' : `${t.terms} ${unit(t.termUnit).toLowerCase()}`}</TableCell>
                  <TableCell align="right" sx={num}>{pct(t.interestRate)}</TableCell>
                  <TableCell align="right" sx={num}>{pct(t.penaltyRate)}</TableCell>
                  <TableCell align="right" sx={num}>{pct(t.serviceFeeRate)}</TableCell>
                  <TableCell align="right" sx={num}>{t.minCoMakers ?? '—'}</TableCell>
                  <TableCell align="right" sx={num}>{t.loans.toLocaleString()}</TableCell>
                  <TableCell>
                    {t.disabled ? <Dot color="grey">Disabled</Dot>
                      : t.unavailable ? <Tooltip title={t.unavailable}><span><Dot color="warning">Not in application screen</Dot></span></Tooltip>
                      : <Dot color="success">Active</Dot>}
                  </TableCell>
                  <TableCell sx={{ color: 'text.disabled' }}><FiChevronRight aria-hidden /></TableCell>
                </TableRow>
              ))}
              {!isLoading && rows.length === 0 && <TableRow><TableCell colSpan={9} align="center" sx={{ py: 4, color: 'text.secondary' }}>No loan type matches.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Box>
      </Surface>
      <Typography color="text.secondary" sx={{ fontSize: 12, mt: 1.25, px: 0.5 }}>
        {counts.active} active · {counts.pending} not yet in the application screen · {counts.off} disabled. A change applies to new applications; loans already made keep the setup they were made with.
      </Typography>
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
  const [fail, setFail] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [q, setQ] = useState('')
  const className = (id: number | null) => lists?.loanClasses.find((c) => c.id === id)?.name ?? ''
  const open = (r: SetupNamed | 'new') => { setRow(r); setName(r === 'new' ? '' : r.name); setCls(r === 'new' ? 1 : r.loanClass ?? 1); setFail(null) }
  const done = (m: string) => { setRow(null); setDeleting(null); onNotice(m); void qc.invalidateQueries({ queryKey: ['loan-setup', kind] }); void qc.invalidateQueries({ queryKey: ['audit-log'] }) }
  const run = async (f: () => Promise<unknown>, message: string) => {
    setBusy(true)
    setFail(null)
    try { await f(); done(message) } catch (e) { setFail((e as Error).message) } finally { setBusy(false) }
  }
  const rows = (data ?? []).filter((r) => !q.trim() || r.name.toLowerCase().includes(q.trim().toLowerCase()))
  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, mb: 1.5, flexWrap: 'wrap' }}>
        <SearchField value={q} onChange={setQ} placeholder={`Search ${noun}s`} />
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" startIcon={<FiPlus />} onClick={() => open('new')}>Add {noun}</Button>
      </Box>
      {error && <Alert severity="error" sx={{ mb: 1.5 }}>{(error as Error).message}</Alert>}
      <Surface>
        <Box sx={(t) => ({ overflow: 'auto', ...listTable(t) })}>
          <Table size="small" aria-label={noun}>
            <TableHead><TableRow><TableCell>Name</TableCell>{kind === 'purposes' && <TableCell>Loan class</TableCell>}<TableCell align="right">Used by loans</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {isLoading && Array.from({ length: 5 }, (_, i) => <TableRow key={i}><TableCell colSpan={4}><Skeleton /></TableCell></TableRow>)}
              {rows.map((r) => (
                <TableRow key={r.id} hover>
                  <TableCell sx={{ fontWeight: 500 }}>{r.name}</TableCell>
                  {kind === 'purposes' && <TableCell>{className(r.loanClass)}</TableCell>}
                  <TableCell align="right" sx={num}>{r.uses.toLocaleString()}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    <Button size="small" onClick={() => open(r)}>Rename</Button>
                    <Tooltip title={r.uses > 0 ? 'Loans use this, so it cannot be deleted' : ''}>
                      <span><Button size="small" color="error" disabled={r.uses > 0} onClick={() => { setDeleting(r); setFail(null) }}>Delete</Button></span>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
              {!isLoading && rows.length === 0 && <TableRow><TableCell colSpan={4} align="center" sx={{ py: 4, color: 'text.secondary' }}>Nothing matches.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Box>
      </Surface>
      <Typography color="text.secondary" sx={{ fontSize: 12, mt: 1.25, px: 0.5 }}>Entries that loans use can be renamed but not deleted.</Typography>
      <ReasonDialog
        open={row != null} title={row === 'new' ? `Add ${noun}` : `Rename ${noun}`} confirm="Save" busy={busy} error={fail} canSubmit={name.trim() !== ''}
        onCancel={() => setRow(null)}
        onConfirm={(reason) => void run(() => saveSetupNamed(kind, row === 'new' || row == null ? null : row.id, { name, loanClass: kind === 'purposes' ? cls : null, reason }), `${name} was saved`)}
      >
        <TextField autoFocus required label="Name" value={name} onChange={(e) => setName(e.target.value)} slotProps={{ htmlInput: { maxLength: 45 } }} />
        {kind === 'purposes' && (
          <TextField select label="Loan class" value={cls} onChange={(e) => setCls(Number(e.target.value))}>
            {lists?.loanClasses.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}
          </TextField>
        )}
      </ReasonDialog>
      <ReasonDialog
        open={deleting != null} title={`Delete ${deleting?.name ?? ''}?`} intro="This cannot be undone." confirm="Delete" danger busy={busy} error={fail}
        onCancel={() => setDeleting(null)} onConfirm={(reason) => deleting && void run(() => deleteSetupNamed(kind, deleting.id, reason), `${deleting.name} was deleted`)}
      />
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
  const [fail, setFail] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const open = (row: HoldSetupRow, what: 'allow' | 'forbid' | 'default') => { setAsk({ row, what }); setFail(null) }
  const submit = async (reason: string) => {
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
  const groups = useMemo(() => {
    const m = new Map<string, HoldSetupRow[]>()
    for (const r of data ?? []) m.set(r.group, [...(m.get(r.group) ?? []), r])
    return [...m.entries()]
  }, [data])
  return (
    <>
      <AboutNote sx={{ mb: 2, px: 0.5 }}>
        When a co-maker agrees to cover an amount, it is held on one of their accounts and cannot be withdrawn. Choose the deposit types that can take such a hold, and the one suggested first.
        Holds already placed on a type stay when it is switched off.
      </AboutNote>
      {error && <Alert severity="error" sx={{ mb: 1.5 }}>{(error as Error).message}</Alert>}
      {isLoading && <Skeleton variant="rounded" height={180} />}
      {groups.map(([group, rows]) => (
        <Box key={group} sx={{ mb: 2.5 }}>
          <SectionLabel>{group}</SectionLabel>
          <Surface label={group}>
            {rows.map((r, i) => (
              <Box key={`${r.slc}-${r.slt}`} sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 2, px: 2, py: 1.25, borderTop: i ? `0.5px solid ${t.palette.divider}` : 0 })}>
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography sx={{ fontSize: 15, fontWeight: 500 }}>{r.name}</Typography>
                  <Typography color="text.secondary" sx={{ fontSize: 12 }}>{r.holds.toLocaleString()} {r.holds === 1 ? 'hold' : 'holds'} now</Typography>
                </Box>
                {r.isDefault ? (
                  <Box sx={(t) => ({ px: 1.25, py: 0.4, borderRadius: '999px', fontSize: 12, fontWeight: 600, color: t.palette.primary.main, bgcolor: t.palette.mode === 'dark' ? 'rgba(10,132,255,0.22)' : 'rgba(0,122,255,0.12)' })}>Suggested first</Box>
                ) : (
                  <Button size="small" disabled={!r.holdable} onClick={() => open(r, 'default')}>Suggest first</Button>
                )}
                <Switch checked={r.holdable} onChange={() => open(r, r.holdable ? 'forbid' : 'allow')} slotProps={{ input: { 'aria-label': `${r.name} can be held` } }} />
              </Box>
            ))}
          </Surface>
        </Box>
      ))}
      <ReasonDialog
        open={ask != null} busy={busy} error={fail} confirm="Save" onCancel={() => setAsk(null)} onConfirm={(r) => void submit(r)}
        title={ask?.what === 'default' ? `Suggest ${ask.row.name} first` : ask?.what === 'allow' ? `Allow holds on ${ask?.row.name}` : `Stop holds on ${ask?.row.name ?? ''}`}
      />
    </>
  )
}

const TABS = [
  { value: 'types', label: 'Loan types' },
  { value: 'amortization', label: 'Amortization' },
  { value: 'purposes', label: 'Purposes' },
  { value: 'collateral', label: 'Collateral' },
  { value: 'holds', label: 'Co-maker holds' },
] as const
type Tab = (typeof TABS)[number]['value']

/** Loan setup for administrators: loan types and the lists clerks choose from. Every change is audited. */
export default function LoanSetupPage({ initialTab = 'types', fullHeight = false }: { initialTab?: Tab; fullHeight?: boolean }) {
  const me = useMe()
  const [tab, setTab] = useState<Tab>(initialTab)
  const [notice, setNotice] = useState<string | null>(null)
  const lists = useQuery({ queryKey: ['loan-setup-lists'], queryFn: getLoanSetupLists, enabled: me.admin, staleTime: 5 * 60_000 })
  return (
    <Box component="main" sx={(t) => ({ minHeight: fullHeight ? '100dvh' : 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        {!fullHeight && (<PageTitle title="Loan Setup">The loan products and the lists behind the loan application. Each change asks for a reason and is kept in the audit log.</PageTitle>)}
        {!me.admin ? <Alert severity="info">Only administrators can change the loan setup.</Alert> : (
          <>
            <Box sx={{ mb: 2.5 }}><Segmented label="Loan setup sections" value={tab} onChange={setTab} options={[...TABS]} /></Box>
            <Box key={tab} sx={{
              animation: 'loanSetupIn 240ms cubic-bezier(.2,.8,.2,1) both', '@keyframes loanSetupIn': { from: { opacity: 0, transform: 'translateY(5px)' }, to: { opacity: 1, transform: 'none' } },
              '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
            }}>
              {tab === 'types' && <TypesTab lists={lists.data} onNotice={setNotice} />}
              {tab === 'amortization' && <AmortizationTab lists={lists.data} />}
              {tab === 'purposes' && <NamedTab kind="purposes" noun="loan purpose" lists={lists.data} onNotice={setNotice} />}
              {tab === 'collateral' && <NamedTab kind="collateral-types" noun="collateral type" lists={lists.data} onNotice={setNotice} />}
              {tab === 'holds' && <HoldsTab onNotice={setNotice} />}
            </Box>
          </>
        )}
      </Box>
      <Snackbar open={notice != null} autoHideDuration={4000} onClose={() => setNotice(null)} message={notice} />
    </Box>
  )
}

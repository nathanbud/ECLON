import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import {
  createJournal, createPeriod, getFinancialCondition, getJournal, getOperations, journalStep, listJournals, listPeriods, reverseJournal, setPeriodStatus,
  type JournalLineIn, type JournalSummary, type PeriodStatus, type StatementSection,
} from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import BanksTab from './BanksTab'
import TrialBalancePage from './TrialBalancePage'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const today = () => new Date().toLocaleDateString('en-CA')
const yearStart = () => `${new Date().getFullYear()}-01-01`

/** Reports and bookkeeping: trial balance for everyone; statements, journals and periods for accountants and administrators. */
export default function AccountingPage() {
  const me = useMe()
  const accountant = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  const [tab, setTab] = useState(0)
  const tabs = ['Trial balance', ...(accountant ? ['Statements', 'Journals', 'Periods', 'Banks'] : [])]
  const [branch, setBranch] = useState('1')
  const branchCode = Number(branch)
  const ok = Number.isInteger(branchCode) && branchCode > 0

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <Stack direction="row" spacing={2} sx={{ mb: 1, alignItems: "center" }}>
          <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', flex: 1 }}>Accounting</Typography>
          {tab > 0 && <TextField size="small" label="Branch code" value={branch} onChange={(e) => setBranch(e.target.value)} sx={{ width: 130 }} />}
        </Stack>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>{tabs.map((t) => <Tab key={t} label={t} />)}</Tabs>
        {tab === 0 && <Box sx={{ mx: -3, mt: -3 }}><TrialBalancePage /></Box>}
        {tab === 1 && ok && <Statements branchCode={branchCode} />}
        {tab === 2 && ok && <Journals branchCode={branchCode} />}
        {tab === 3 && ok && <Periods branchCode={branchCode} />}
        {tab === 4 && ok && <BanksTab branchCode={branchCode} />}
      </Box>
    </Box>
  )
}

// ---- statements ----------------------------------------------------------------------------------------------------

function SectionRows({ s }: { s: StatementSection }) {
  return (
    <>
      <TableRow>
        <TableCell colSpan={2} sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{s.name}</TableCell>
        <TableCell align="right" sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{money(s.total)}</TableCell>
      </TableRow>
      {s.groups.map((g) => (
        <GroupRows key={g.name} g={g} />
      ))}
    </>
  )
}

function GroupRows({ g }: { g: StatementSection['groups'][number] }) {
  return (
    <>
      <TableRow>
        <TableCell colSpan={2} sx={{ fontWeight: 600, pl: 3 }}>{g.name}</TableCell>
        <TableCell align="right" sx={{ fontWeight: 600 }}>{money(g.total)}</TableCell>
      </TableRow>
      {g.lines.map((l) => (
        <TableRow key={l.accountCode + l.description} hover>
          <TableCell sx={{ pl: 5, width: 110 }}>{l.accountCode}</TableCell>
          <TableCell>{l.description}</TableCell>
          <TableCell align="right">{money(l.amount)}</TableCell>
        </TableRow>
      ))}
    </>
  )
}

function SectionTable({ sections, children }: { sections: StatementSection[]; children?: React.ReactNode }) {
  return (
    <Table size="small">
      <TableBody>
        {sections.map((s) => (
          <SectionRows key={s.name} s={s} />
        ))}
        {children}
      </TableBody>
    </Table>
  )
}

function Statements({ branchCode }: { branchCode: number }) {
  const [kind, setKind] = useState<'fc' | 'ops'>('fc')
  const [asOf, setAsOf] = useState(today())
  const [from, setFrom] = useState(yearStart())
  const [to, setTo] = useState(today())
  const fc = useQuery({ queryKey: ['fs-fc', branchCode, asOf], queryFn: () => getFinancialCondition(branchCode, asOf), enabled: kind === 'fc' && !!asOf, retry: false })
  const ops = useQuery({ queryKey: ['fs-ops', branchCode, from, to], queryFn: () => getOperations(branchCode, from, to), enabled: kind === 'ops' && !!from && !!to, retry: false })
  const q = kind === 'fc' ? fc : ops
  return (
    <Box>
      <Stack direction="row" spacing={2} useFlexGap sx={{ mb: 2, alignItems: "center", flexWrap: "wrap" }}>
        <TextField select size="small" label="Statement" value={kind} onChange={(e) => setKind(e.target.value as 'fc' | 'ops')} sx={{ width: 260 }}>
          <MenuItem value="fc">Statement of Financial Condition</MenuItem>
          <MenuItem value="ops">Statement of Operations</MenuItem>
        </TextField>
        {kind === 'fc' ? (
          <TextField size="small" type="date" label="As at" value={asOf} onChange={(e) => setAsOf(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        ) : (
          <>
            <TextField size="small" type="date" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField size="small" type="date" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          </>
        )}
      </Stack>
      {q.error && <Alert severity="error" sx={{ mb: 2 }}>{(q.error as Error).message}</Alert>}
      {kind === 'fc' && fc.data && (
        <>
          <Alert severity={fc.data.balanced ? 'success' : 'warning'} sx={{ mb: 2 }}>
            {fc.data.balanced
              ? 'Assets equal liabilities plus equity.'
              : `Assets are ${money(fc.data.totalAssets)} but liabilities plus equity are ${money(fc.data.totalLiabilitiesAndEquity)} (difference ${money(Math.abs(fc.data.totalAssets - fc.data.totalLiabilitiesAndEquity))}). The legacy ledger does not balance for this date.`}
          </Alert>
          <Box sx={(t) => ({ overflow: 'auto', opacity: fc.isFetching ? 0.6 : 1, ...glass(t) })}><SectionTable sections={fc.data.sections} /></Box>
        </>
      )}
      {kind === 'ops' && ops.data && (
        <Box sx={(t) => ({ overflow: 'auto', opacity: ops.isFetching ? 0.6 : 1, ...glass(t) })}>
          <SectionTable sections={ops.data.sections}>
            <TableRow>
              <TableCell colSpan={2} sx={{ fontWeight: 700 }}>Net surplus</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700 }}>{money(ops.data.netSurplus)}</TableCell>
            </TableRow>
          </SectionTable>
        </Box>
      )}
    </Box>
  )
}

// ---- journals ------------------------------------------------------------------------------------------------------

const statusColor: Record<JournalSummary['status'], 'default' | 'info' | 'warning' | 'success'> = { DRAFT: 'default', FOR_REVIEW: 'info', APPROVED: 'warning', POSTED: 'success' }

function Journals({ branchCode }: { branchCode: number }) {
  const qc = useQueryClient()
  const me = useMe()
  const [openId, setOpenId] = useState<number | null>(null)
  const [creating, setCreating] = useState(false)
  const list = useQuery({ queryKey: ['journals', branchCode], queryFn: () => listJournals(branchCode) })
  const refresh = () => qc.invalidateQueries({ queryKey: ['journals'] })
  return (
    <Box>
      <Stack direction="row" sx={{ mb: 1.5 }}>
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" startIcon={<FiPlus />} onClick={() => setCreating(true)}>New journal</Button>
      </Stack>
      {list.error && <Alert severity="error" sx={{ mb: 2 }}>{(list.error as Error).message}</Alert>}
      {list.data && list.data.length === 0 && <Alert severity="info">No journals yet.</Alert>}
      {list.data && list.data.length > 0 && (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Journals">
            <TableHead>
              <TableRow><TableCell>Reference</TableCell><TableCell>Date</TableCell><TableCell>Description</TableCell><TableCell align="right">Amount</TableCell><TableCell>Status</TableCell><TableCell>By</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {list.data.map((j) => (
                <TableRow key={j.id} hover onClick={() => setOpenId(j.id)} sx={{ cursor: 'pointer' }}>
                  <TableCell>{j.reference}</TableCell>
                  <TableCell>{j.journalDate}</TableCell>
                  <TableCell>{j.description}</TableCell>
                  <TableCell align="right">{money(j.total)}</TableCell>
                  <TableCell><Chip size="small" label={j.status.replace('_', ' ')} color={statusColor[j.status]} /></TableCell>
                  <TableCell>{j.createdBy}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
      {creating && <NewJournal branchCode={branchCode} onClose={() => setCreating(false)} onDone={() => { setCreating(false); refresh() }} />}
      {openId !== null && <JournalView id={openId} user={me.username} onClose={() => setOpenId(null)} onChanged={refresh} />}
    </Box>
  )
}

interface DraftLine { account: string; debit: string; credit: string }
const blankLine = (): DraftLine => ({ account: '', debit: '', credit: '' })

function NewJournal({ branchCode, onClose, onDone }: { branchCode: number; onClose: () => void; onDone: () => void }) {
  const [reference, setReference] = useState('')
  const [date, setDate] = useState(today())
  const [description, setDescription] = useState('')
  const [lines, setLines] = useState<DraftLine[]>([blankLine(), blankLine()])
  const set = (i: number, k: keyof DraftLine, v: string) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, [k]: v } : l)))
  const dr = lines.reduce((s, l) => s + (Number(l.debit) || 0), 0)
  const cr = lines.reduce((s, l) => s + (Number(l.credit) || 0), 0)
  const balanced = dr === cr && dr > 0
  const m = useMutation({
    mutationFn: () => createJournal({
      branchCode, reference: reference.trim(), journalDate: date, description,
      lines: lines.filter((l) => l.account.trim()).map<JournalLineIn>((l) => ({ accountCode: l.account.trim(), debit: Number(l.debit) || 0, credit: Number(l.credit) || 0 })),
    }),
    onSuccess: onDone,
  })
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New journal</DialogTitle>
      <DialogContent>
        <Stack direction="row" spacing={2} sx={{ my: 1 }}>
          <TextField size="small" label="Reference" value={reference} onChange={(e) => setReference(e.target.value)} />
          <TextField size="small" type="date" label="Date" value={date} onChange={(e) => setDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" label="Description" value={description} onChange={(e) => setDescription(e.target.value)} sx={{ flex: 1 }} />
        </Stack>
        {lines.map((l, i) => (
          <Stack key={i} direction="row" spacing={1} sx={{ mb: 1 }}>
            <TextField size="small" label="Account" value={l.account} onChange={(e) => set(i, 'account', e.target.value)} sx={{ width: 160 }} />
            <TextField size="small" label="Debit" type="number" value={l.debit} onChange={(e) => set(i, 'debit', e.target.value)} sx={{ flex: 1 }} />
            <TextField size="small" label="Credit" type="number" value={l.credit} onChange={(e) => set(i, 'credit', e.target.value)} sx={{ flex: 1 }} />
            <IconButton aria-label="Remove line" disabled={lines.length <= 2} onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}><FiTrash2 /></IconButton>
          </Stack>
        ))}
        <Button size="small" onClick={() => setLines((ls) => [...ls, blankLine()])}>Add line</Button>
        <Alert severity={balanced ? 'success' : 'warning'} sx={{ mt: 2 }}>
          Debits {money(dr)} / credits {money(cr)}{balanced ? '' : '. They must be equal before the journal can be submitted.'}
        </Alert>
        {m.error && <Alert severity="error" sx={{ mt: 1 }}>{(m.error as Error).message}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={m.isPending || !reference.trim()} onClick={() => m.mutate()}>Save draft</Button>
      </DialogActions>
    </Dialog>
  )
}

function JournalView({ id, user, onClose, onChanged }: { id: number; user: string; onClose: () => void; onChanged: () => void }) {
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['journal', id], queryFn: () => getJournal(id) })
  const [reason, setReason] = useState('')
  const [reversing, setReversing] = useState(false)
  const done = () => { qc.invalidateQueries({ queryKey: ['journal', id] }); onChanged() }
  const step = useMutation({ mutationFn: (s: 'submit' | 'approve' | 'post') => journalStep(id, s), onSuccess: done })
  const rev = useMutation({ mutationFn: () => reverseJournal(id, reason), onSuccess: () => { setReversing(false); setReason(''); done() } })
  const s = data?.summary
  const error = (step.error ?? rev.error) as Error | null
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        {s?.reference ?? 'Journal'} {s && <Chip size="small" sx={{ ml: 1 }} label={s.status.replace('_', ' ')} color={statusColor[s.status]} />}
      </DialogTitle>
      <DialogContent>
        {s && (
          <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">
            {s.journalDate} - {s.description} - created by {s.createdBy}{s.approvedBy ? `, approved by ${s.approvedBy}` : ''}
          </Typography>
        )}
        <Table size="small">
          <TableHead><TableRow><TableCell>Account</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
          <TableBody>
            {data?.lines.map((l, i) => (
              <TableRow key={i}>
                <TableCell>{l.accountCode}</TableCell>
                <TableCell align="right">{l.debit ? money(l.debit) : ''}</TableCell>
                <TableCell align="right">{l.credit ? money(l.credit) : ''}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {s?.status === 'FOR_REVIEW' && s.createdBy === user && <Alert severity="info" sx={{ mt: 2 }}>Someone other than the creator has to approve this journal.</Alert>}
        {reversing && <TextField fullWidth size="small" label="Reason for reversal" value={reason} onChange={(e) => setReason(e.target.value)} sx={{ mt: 2 }} />}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error.message}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {s?.status === 'DRAFT' && <Button variant="contained" disabled={step.isPending} onClick={() => step.mutate('submit')}>Submit for review</Button>}
        {s?.status === 'FOR_REVIEW' && s.createdBy !== user && <Button variant="contained" disabled={step.isPending} onClick={() => step.mutate('approve')}>Approve</Button>}
        {s?.status === 'APPROVED' && <Button variant="contained" disabled={step.isPending} onClick={() => step.mutate('post')}>Post</Button>}
        {s?.status === 'POSTED' && !reversing && <Button color="warning" onClick={() => setReversing(true)}>Reverse...</Button>}
        {s?.status === 'POSTED' && reversing && <Button color="warning" variant="contained" disabled={rev.isPending || !reason.trim()} onClick={() => rev.mutate()}>Create reversal draft</Button>}
      </DialogActions>
    </Dialog>
  )
}

// ---- periods -------------------------------------------------------------------------------------------------------

const nextStatus: Record<PeriodStatus, PeriodStatus | null> = { OPEN: 'SOFT_CLOSED', SOFT_CLOSED: 'CLOSED', CLOSED: 'LOCKED', LOCKED: null }

function Periods({ branchCode }: { branchCode: number }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['periods', branchCode], queryFn: () => listPeriods(branchCode) })
  const [name, setName] = useState('')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const refresh = () => qc.invalidateQueries({ queryKey: ['periods'] })
  const add = useMutation({
    mutationFn: () => createPeriod({ branchCode, name: name.trim(), startDate: start, endDate: end }),
    onSuccess: () => { setName(''); setStart(''); setEnd(''); refresh() },
  })
  const move = useMutation({ mutationFn: (v: { id: number; status: PeriodStatus }) => setPeriodStatus(v.id, v.status), onSuccess: refresh })
  const error = (list.error ?? add.error ?? move.error) as Error | null
  return (
    <Box>
      <Stack direction="row" spacing={1.5} useFlexGap sx={{ mb: 2, flexWrap: "wrap" }}>
        <TextField size="small" label="Name" value={name} onChange={(e) => setName(e.target.value)} />
        <TextField size="small" type="date" label="Start" value={start} onChange={(e) => setStart(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField size="small" type="date" label="End" value={end} onChange={(e) => setEnd(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <Button variant="contained" disabled={add.isPending || !name.trim() || !start || !end} onClick={() => add.mutate()}>Add period</Button>
      </Stack>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert>}
      {list.data && list.data.length === 0 && <Alert severity="info">No periods yet. Posting is refused for dates without a period.</Alert>}
      {list.data && list.data.length > 0 && (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Accounting periods">
            <TableHead>
              <TableRow><TableCell>Name</TableCell><TableCell>From</TableCell><TableCell>To</TableCell><TableCell>Status</TableCell><TableCell /></TableRow>
            </TableHead>
            <TableBody>
              {list.data.map((p) => {
                const next = nextStatus[p.status]
                return (
                  <TableRow key={p.id}>
                    <TableCell>{p.name}</TableCell>
                    <TableCell>{p.startDate}</TableCell>
                    <TableCell>{p.endDate}</TableCell>
                    <TableCell><Chip size="small" label={p.status.replace('_', ' ')} color={p.status === 'OPEN' ? 'success' : 'default'} /></TableCell>
                    <TableCell align="right">
                      {p.status === 'SOFT_CLOSED' && <Button size="small" onClick={() => move.mutate({ id: p.id, status: 'OPEN' })}>Reopen</Button>}
                      {next && <Button size="small" disabled={move.isPending} onClick={() => move.mutate({ id: p.id, status: next })}>{next.replace('_', ' ').toLowerCase()}</Button>}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </Box>
      )}
    </Box>
  )
}

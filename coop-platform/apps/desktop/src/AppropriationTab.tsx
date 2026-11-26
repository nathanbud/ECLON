import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiTrash2 } from 'react-icons/fi'
import {
  FUND_KINDS, appropriationStep, cancelAppropriation, createAppropriation, getAppropriation, listAppropriations, returnAppropriation, saveRegistrationDate, searchPostingAccounts,
  startAppropriation, updateAppropriation, type AppropriationDetail, type AppropriationKind, type AppropriationLineIn, type AppropriationSettings, type AppropriationStart,
  type AppropriationSummary,
} from './api'
import { AboutNote, ReasonDialog } from './appleUi'
import { glass } from './glass'
import { useLeaveGuard } from './leaveGuard'
import { useMe } from './session'
import { AccountPicker } from './ui/AccountPicker'
import { ConfirmButton } from './ui/dialogs'
import { money } from './ui/format'
import { labelOf } from './ui/labels'
import { LoadingBlock, LoadingRow } from './ui/Loading'

const KIND_WORDS: Record<AppropriationKind, string> = {
  RESERVE: 'Reserve fund', EDUCATION: 'Education and training fund', COMMUNITY: 'Community development fund', OPTIONAL: 'Optional fund', OTHER: 'Other fund',
  ISC: 'Interest on share capital', PR: 'Patronage refund',
}
const isFund = (k: AppropriationKind) => FUND_KINDS.includes(k)
const pct = (n: number) => `${Number(n.toFixed(4))}%`
const today = () => new Date().toLocaleDateString('en-CA')
const num = (s: string) => (s.trim() === '' ? null : Number(s.replace(/,/g, '')))
const share = (net: number, p: number | null) => (p == null || !Number.isFinite(p) ? 0 : Math.round(net * p) / 100)

/**
 * Accounting > Dividends > Appropriation: once a year is closed, its net surplus is set aside to the statutory funds (RA 9520 Article 86: reserve, education and
 * training, community development, optional) and allocated to interest on share capital and patronage refund, as the general assembly resolved. Prepared by one
 * person, approved by another and posted by a third as one General Journal; the year's dividend distribution then pays members no more than was appropriated.
 */
export default function AppropriationTab() {
  const me = useMe()
  const branchCode = me.branchCode ?? 1
  const canEdit = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  const list = useQuery({ queryKey: ['appropriations', branchCode], queryFn: () => listAppropriations(branchCode) })
  const [thisYear] = useState(() => new Date().getFullYear())
  const [year, setYear] = useState(thisYear - 1)
  const start = useQuery({ queryKey: ['appropriation-start', branchCode, year], queryFn: () => startAppropriation(branchCode, year) })
  const [open, setOpen] = useState<number | null>(null)
  const [editing, setEditing] = useState<{ id: number | null; start: AppropriationStart; detail?: AppropriationDetail } | null>(null)
  const years = Array.from({ length: 8 }, (_, i) => thisYear - 1 - i)

  return (
    <Stack spacing={2}>
      <AboutNote>
        After a year is closed, its net surplus is first set aside to the statutory funds the Cooperative Code (RA 9520, Article 86) requires: at least 10% to the reserve
        fund (at least 50% in the first five years after registration), at most 10% to the education and training fund, at least 3% to the community development fund and
        at most 7% to the optional fund. The rest goes to interest on share capital and patronage refund, as the general assembly resolved; what is not allocated stays
        in the undivided net surplus. Confirm the percents against your bylaws and the CDA rules. One person prepares it, another approves it and a third posts it.
      </AboutNote>
      {start.data && <Registration settings={start.data.settings} branchCode={branchCode} />}
      <Box sx={(t) => ({ ...glass(t), p: 2 })}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
          <Typography sx={{ fontWeight: 600, flex: 1 }}>Appropriations</Typography>
          <TextField select size="small" label="Year" value={year} onChange={(e) => setYear(Number(e.target.value))} sx={{ width: 110 }}>
            {years.map((y) => <MenuItem key={y} value={y}>{y}</MenuItem>)}
          </TextField>
          {start.data && (
            <Typography color="text.secondary" sx={{ fontSize: 12 }}>
              {start.data.netSurplus == null ? `${year} is not closed` : `Net surplus of ${year}: ${money(start.data.netSurplus)}`}
            </Typography>
          )}
          {canEdit && (
            <Button variant="contained" disabled={!start.data || start.data.problems.length > 0} onClick={() => start.data && setEditing({ id: null, start: start.data })}>
              Prepare the {year} appropriation
            </Button>
          )}
        </Stack>
        {start.data && start.data.problems.length > 0 && <Alert severity="info" sx={{ mb: 1 }}>{start.data.problems.join(' ')}</Alert>}
        <Table size="small" aria-label="Appropriations of the net surplus">
          <TableHead>
            <TableRow>
              <TableCell>Reference</TableCell><TableCell>Year</TableCell><TableCell>Dated</TableCell><TableCell>Resolution</TableCell><TableCell align="right">Net surplus</TableCell>
              <TableCell align="right">Appropriated</TableCell><TableCell>Status</TableCell><TableCell>Prepared by</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            <LoadingRow q={list} what="the appropriations" />
            {list.data?.length === 0 && <TableRow><TableCell colSpan={8}><Typography color="text.secondary">No appropriation yet.</Typography></TableCell></TableRow>}
            {list.data?.map((a) => (
              <TableRow key={a.id} hover selected={open === a.id} onClick={() => setOpen(a.id)} sx={{ cursor: 'pointer' }}>
                <TableCell>{a.reference}</TableCell>
                <TableCell>{a.year}</TableCell>
                <TableCell>{a.entryDate}</TableCell>
                <TableCell>{a.resolution}</TableCell>
                <TableCell align="right">{money(a.netSurplus)}</TableCell>
                <TableCell align="right">{money(a.allocated)}</TableCell>
                <TableCell><StatusChip s={a.status} /></TableCell>
                <TableCell>{a.createdBy}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
      {open != null && (
        <AppropriationView id={open} canEdit={canEdit}
          onEdit={(d) => start.data && setEditing({ id: d.summary.id, detail: d, start: { ...start.data, year: d.summary.year, netSurplus: d.summary.netSurplus, reserveMinimum: d.reserveMinimum } })} />
      )}
      {editing && (
        <Editor branchCode={branchCode} id={editing.id} start={editing.start} detail={editing.detail} onClose={() => setEditing(null)}
          onSaved={(s) => { setEditing(null); setOpen(s.id) }} />
      )}
    </Stack>
  )
}

function StatusChip({ s }: { s: AppropriationSummary['status'] }) {
  const color = s === 'POSTED' ? 'success' : s === 'APPROVED' ? 'info' : s === 'FOR_REVIEW' ? 'warning' : 'default'
  return <Chip size="small" color={color} label={labelOf(s)} />
}

/** The cooperative's registration date, which decides whether the reserve fund must be at least half of the net surplus. */
function Registration({ settings, branchCode }: { settings: AppropriationSettings; branchCode: number }) {
  const qc = useQueryClient()
  const [changing, setChanging] = useState(false)
  const [date, setDate] = useState(settings.registeredOn ?? '')
  const save = useMutation({
    mutationFn: (why: string) => saveRegistrationDate(branchCode, date || null, why),
    onSuccess: () => { setChanging(false); void qc.invalidateQueries({ queryKey: ['appropriation-start'] }) },
    meta: { done: 'Registration date saved.' },
  })
  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      <Typography sx={{ fontSize: 13 }}>
        {settings.registeredOn
          ? `Registered with the CDA on ${settings.registeredOn}. Its first five years need a reserve fund of at least 50% of the net surplus.`
          : 'The cooperative’s registration date is not recorded, so the reserve fund is checked at 10%. Record it if the cooperative is in its first five years.'}
      </Typography>
      {settings.mayEdit && <Button size="small" onClick={() => setChanging(true)}>{settings.registeredOn ? 'Change' : 'Record it'}</Button>}
      <ReasonDialog open={changing} title="The cooperative’s registration date" intro="The date on the CDA certificate of registration." confirm="Save" busy={save.isPending}
        error={save.error?.message ?? null} onCancel={() => setChanging(false)} onConfirm={(why) => save.mutate(why)}>
        <TextField type="date" label="Registered on" value={date} onChange={(e) => setDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
      </ReasonDialog>
    </Stack>
  )
}

// ---- one appropriation ---------------------------------------------------------------------------------------------

/** The Article 86 checks, each kind of fund against its limit. */
function LimitChecks({ d }: { d: AppropriationDetail }) {
  const rows: [string, number, string, boolean][] = [
    ['Reserve fund', d.percents.RESERVE ?? 0, `at least ${d.reserveMinimum}%`, (d.percents.RESERVE ?? 0) >= d.reserveMinimum],
    ['Education and training fund', d.percents.EDUCATION ?? 0, 'at most 10%', (d.percents.EDUCATION ?? 0) <= 10],
    ['Community development fund', d.percents.COMMUNITY ?? 0, 'at least 3%', (d.percents.COMMUNITY ?? 0) >= 3],
    ['Optional fund', d.percents.OPTIONAL ?? 0, 'at most 7%', (d.percents.OPTIONAL ?? 0) <= 7],
  ]
  return (
    <Table size="small" aria-label="Article 86 limits">
      <TableHead><TableRow><TableCell>Fund (Article 86)</TableCell><TableCell align="right">Set aside</TableCell><TableCell>The law asks</TableCell><TableCell /></TableRow></TableHead>
      <TableBody>
        {rows.map(([name, p, rule, ok]) => (
          <TableRow key={name}>
            <TableCell>{name}</TableCell><TableCell align="right">{pct(p)}</TableCell><TableCell>{rule}</TableCell>
            <TableCell><Chip size="small" color={ok ? 'success' : 'warning'} label={ok ? 'Within the limit' : 'Outside the limit'} /></TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

function AppropriationView({ id, canEdit, onEdit }: { id: number; canEdit: boolean; onEdit: (d: AppropriationDetail) => void }) {
  const me = useMe()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['appropriation', id], queryFn: () => getAppropriation(id) })
  const [asking, setAsking] = useState<'return' | 'cancel' | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['appropriation', id] }); void qc.invalidateQueries({ queryKey: ['appropriations'] }); void qc.invalidateQueries({ queryKey: ['appropriation-start'] }) }
  const done: Record<'submit' | 'approve' | 'post', string> = { submit: 'Sent for review.', approve: 'Approved. A third person posts it.', post: 'Appropriation posted to the ledger.' }
  const step = useMutation({ mutationFn: (s: 'submit' | 'approve' | 'post') => appropriationStep(id, s), onSuccess: refresh, meta: { done: (_r: unknown, s: unknown) => done[s as 'submit' | 'approve' | 'post'] } })
  const reason = useMutation({
    mutationFn: ({ kind, why }: { kind: 'return' | 'cancel'; why: string }) => (kind === 'return' ? returnAppropriation(id, why) : cancelAppropriation(id, why)),
    onSuccess: () => { setAsking(null); refresh() },
    meta: { done: (_r: unknown, v: unknown) => ((v as { kind: string }).kind === 'return' ? 'Sent back to the person who prepared it.' : 'Appropriation cancelled.') },
  })
  if (!q.data) return <LoadingBlock q={q} what="the appropriation" />
  const d = q.data
  const s = d.summary
  const mine = s.createdBy.toLowerCase() === me.username.toLowerCase()
  const t = d.totals

  return (
    <Box sx={(th) => ({ ...glass(th), p: 2 })}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
        <Typography sx={{ fontWeight: 600 }}>{s.reference}</Typography>
        <StatusChip s={s.status} />
        <Typography color="text.secondary" sx={{ flex: 1 }}>
          {s.year} net surplus · dated {s.entryDate} · prepared by {s.createdBy}{s.approvedBy ? ` · approved by ${s.approvedBy}` : ''}{s.postedBy ? ` · posted by ${s.postedBy} as General Journal ${s.documentNo}` : ''}
        </Typography>
        {canEdit && s.status === 'DRAFT' && (mine || me.admin) && <Button onClick={() => onEdit(d)}>Change</Button>}
        {canEdit && s.status === 'DRAFT' && (mine || me.admin) && <Button variant="contained" disabled={d.problems.length > 0 || step.isPending} onClick={() => step.mutate('submit')}>Send for review</Button>}
        {canEdit && s.status === 'FOR_REVIEW' && !mine && <Button variant="contained" disabled={d.problems.length > 0 || step.isPending} onClick={() => step.mutate('approve')}>Approve</Button>}
        {canEdit && (s.status === 'FOR_REVIEW' || s.status === 'APPROVED') && !mine && <Button onClick={() => setAsking('return')}>Send back</Button>}
        {canEdit && s.status === 'APPROVED' && !mine && s.approvedBy?.toLowerCase() !== me.username.toLowerCase() && (
          <ConfirmButton variant="contained" disabled={step.isPending} title="Post the appropriation?"
            message={`This writes one General Journal dated ${s.entryDate}: ${money(t.allocated)} out of the undivided net surplus, ${money(t.funds)} to the statutory funds and ${money(t.isc + t.pr)} to interest on share capital and patronage refund. Once posted it cannot be edited or cancelled.`}
            onConfirm={() => step.mutate('post')}>Post</ConfirmButton>
        )}
        {canEdit && s.status !== 'POSTED' && s.status !== 'CANCELLED' && (mine || me.admin) && <Button color="error" onClick={() => setAsking('cancel')}>Cancel</Button>}
      </Stack>
      <Typography sx={{ mb: 1 }}>Per {s.resolution}</Typography>
      {step.error && <Alert severity="error" sx={{ mb: 1 }}>{step.error.message}</Alert>}
      {s.status === 'CANCELLED' && d.cancelReason && <Alert severity="info" sx={{ mb: 1 }}>Cancelled: {d.cancelReason}</Alert>}
      {s.status === 'FOR_REVIEW' && mine && <Alert severity="info" sx={{ mb: 1 }}>Waiting for another accountant or an administrator to review and approve it.</Alert>}
      {s.status === 'APPROVED' && (mine || s.approvedBy?.toLowerCase() === me.username.toLowerCase()) && <Alert severity="info" sx={{ mb: 1 }}>Approved. A third person, not the one who prepared or approved it, posts it.</Alert>}
      {s.status === 'POSTED' && <Alert severity="success" sx={{ mb: 1 }}>Posted. The {s.year} dividend distribution pays members at most {money(t.isc)} interest on share capital and {money(t.pr)} patronage refund.</Alert>}
      {d.problems.length > 0 && (
        <Alert severity="warning" sx={{ mb: 1 }}>
          <Typography sx={{ fontWeight: 600 }}>To fix before it can be sent on ({d.problems.length})</Typography>
          <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{d.problems.map((p) => <li key={p}>{p}</li>)}</Box>
        </Alert>
      )}

      <Table size="small" aria-label="Lines of the appropriation">
        <TableHead>
          <TableRow><TableCell>Fund or allocation</TableCell><TableCell>Kind</TableCell><TableCell align="right">% of net surplus</TableCell><TableCell align="right">Amount</TableCell><TableCell>Credited to</TableCell></TableRow>
        </TableHead>
        <TableBody>
          <TableRow><TableCell colSpan={3} sx={{ fontWeight: 600 }}>Net surplus of {s.year}</TableCell><TableCell align="right" sx={{ fontWeight: 600 }}>{money(t.netSurplus)}</TableCell><TableCell /></TableRow>
          {d.lines.map((l) => (
            <TableRow key={l.lineNo}>
              <TableCell>{l.name}</TableCell>
              <TableCell>{KIND_WORDS[l.kind]}</TableCell>
              <TableCell align="right">{l.percent != null ? pct(l.percent) : t.netSurplus ? pct((l.amount / t.netSurplus) * 100) : ''}</TableCell>
              <TableCell align="right">{money(l.amount)}</TableCell>
              <TableCell>{l.account} {l.accountTitle}</TableCell>
            </TableRow>
          ))}
          <TableRow><TableCell colSpan={3} sx={{ fontWeight: 600 }}>Appropriated</TableCell><TableCell align="right" sx={{ fontWeight: 600 }}>{money(t.allocated)}</TableCell><TableCell /></TableRow>
          <TableRow>
            <TableCell colSpan={3}>{t.undivided < 0 ? 'More than the net surplus by' : 'Left in the undivided net surplus'}</TableCell>
            <TableCell align="right" sx={{ color: t.undivided < 0 ? 'error.main' : undefined }}>{money(Math.abs(t.undivided))}</TableCell><TableCell />
          </TableRow>
        </TableBody>
      </Table>

      <Typography sx={{ fontWeight: 600, mt: 2, mb: 0.5 }}>Article 86 limits</Typography>
      <LimitChecks d={d} />

      <Typography sx={{ fontWeight: 600, mt: 2, mb: 0.5 }}>The General Journal it posts</Typography>
      <Table size="small" aria-label="General Journal of the appropriation">
        <TableHead><TableRow><TableCell>Account</TableCell><TableCell>Title</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
        <TableBody>
          {d.entry.map((e) => (
            <TableRow key={e.account}>
              <TableCell>{e.account}</TableCell><TableCell>{e.title}</TableCell>
              <TableCell align="right">{e.debit ? money(e.debit) : ''}</TableCell><TableCell align="right">{e.credit ? money(e.credit) : ''}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <ReasonDialog open={asking != null} title={asking === 'return' ? 'Send the appropriation back' : 'Cancel the appropriation'}
        intro={asking === 'return' ? 'It goes back to the person who prepared it as a draft, with your reason.' : 'Nothing has been posted. It is kept, marked cancelled, with your reason.'}
        confirm={asking === 'return' ? 'Send back' : 'Cancel appropriation'} danger={asking === 'cancel'} busy={reason.isPending} error={reason.error?.message ?? null}
        onCancel={() => setAsking(null)} onConfirm={(why) => asking && reason.mutate({ kind: asking, why })} />
    </Box>
  )
}

// ---- preparing one --------------------------------------------------------------------------------------------------

interface Draft { kind: AppropriationKind; name: string; percent: string; amount: string; account: string; accountName: string }

const fromIn = (l: AppropriationLineIn): Draft => ({
  kind: l.kind, name: l.name ?? '', percent: l.percent == null ? '' : String(l.percent), amount: l.amount == null || l.amount === 0 ? '' : String(l.amount), account: l.account ?? '', accountName: '',
})

function Editor({ branchCode, id, start, detail, onClose, onSaved }: {
  branchCode: number; id: number | null; start: AppropriationStart; detail?: AppropriationDetail; onClose: () => void; onSaved: (s: AppropriationSummary) => void
}) {
  const qc = useQueryClient()
  const net = start.netSurplus ?? 0
  const payout = start.settings.payoutAccount
  const [date, setDate] = useState(detail?.summary.entryDate ?? today())
  const [resolution, setResolution] = useState(detail?.summary.resolution ?? '')
  const [lines, setLines] = useState<Draft[]>(() => detail
    ? detail.lines.map((l) => ({ kind: l.kind, name: l.name, percent: l.percent == null ? '' : String(l.percent), amount: isFund(l.kind) ? '' : String(l.amount), account: l.account ?? '', accountName: l.accountTitle ?? '' }))
    : start.lines.map(fromIn))
  const [dirty, setDirty] = useState(false)
  useLeaveGuard(dirty ? 'The appropriation you are preparing is not saved.' : null)
  const set = (i: number, patch: Partial<Draft>) => { setDirty(true); setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l))) }
  const amountOf = (l: Draft) => (isFund(l.kind) ? share(net, num(l.percent)) : num(l.amount) ?? 0)
  const allocated = lines.reduce((s, l) => s + amountOf(l), 0)
  const left = Math.round((net - allocated) * 100) / 100

  const save = useMutation({
    mutationFn: () => {
      const body = {
        branchCode, year: start.year, entryDate: date, resolution: resolution.trim(),
        lines: lines.map((l): AppropriationLineIn => ({ kind: l.kind, name: l.name.trim(), percent: isFund(l.kind) ? num(l.percent) : null, amount: isFund(l.kind) ? null : num(l.amount) ?? 0, account: isFund(l.kind) ? l.account.trim() || null : payout })),
      }
      return id == null ? createAppropriation(body) : updateAppropriation(id, body)
    },
    onSuccess: (s) => {
      void qc.invalidateQueries({ queryKey: ['appropriations'] })
      void qc.invalidateQueries({ queryKey: ['appropriation', s.id] })
      void qc.invalidateQueries({ queryKey: ['appropriation-start'] })
      onSaved(s)
    },
    meta: { done: 'Appropriation saved as a draft. Check the list of things to fix, then send it for review.' },
  })

  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth aria-labelledby="appropriation-title">
      <DialogTitle id="appropriation-title">{id == null ? `Appropriate the ${start.year} net surplus` : `Change ${detail?.summary.reference ?? ''}`}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Typography>Net surplus of {start.year}, from the year’s closing: <b>{money(net)}</b>. The reserve fund must take at least {start.reserveMinimum}%.</Typography>
          <Stack direction="row" spacing={1.5}>
            <TextField type="date" label="Dated" value={date} onChange={(e) => { setDirty(true); setDate(e.target.value) }} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 170 }}
              helperText="The general assembly’s date or later" />
            <TextField label="General assembly resolution" value={resolution} onChange={(e) => { setDirty(true); setResolution(e.target.value) }} sx={{ flex: 1 }}
              placeholder={`GA Resolution No. 3, s. ${start.year + 1}`} slotProps={{ htmlInput: { maxLength: 200 } }} />
          </Stack>
          <Table size="small" aria-label="Funds and allocations">
            <TableHead>
              <TableRow><TableCell sx={{ width: 220 }}>Kind</TableCell><TableCell>Name</TableCell><TableCell sx={{ width: 120 }} align="right">% of net surplus</TableCell><TableCell sx={{ width: 150 }} align="right">Amount</TableCell><TableCell>Credited to</TableCell><TableCell sx={{ width: 40 }} /></TableRow>
            </TableHead>
            <TableBody>
              {lines.map((l, i) => (
                <TableRow key={i}>
                  <TableCell>
                    <TextField select size="small" fullWidth value={l.kind} aria-label={`Kind of line ${i + 1}`}
                      onChange={(e) => { const k = e.target.value as AppropriationKind; set(i, { kind: k, name: l.name || KIND_WORDS[k], account: isFund(k) ? (isFund(l.kind) ? l.account : '') : payout }) }}>
                      {(Object.keys(KIND_WORDS) as AppropriationKind[]).map((k) => <MenuItem key={k} value={k}>{KIND_WORDS[k]}</MenuItem>)}
                    </TextField>
                  </TableCell>
                  <TableCell><TextField size="small" fullWidth value={l.name} onChange={(e) => set(i, { name: e.target.value })} slotProps={{ htmlInput: { 'aria-label': `Name of line ${i + 1}`, maxLength: 80 } }} /></TableCell>
                  <TableCell>
                    {isFund(l.kind) && <TextField size="small" value={l.percent} onChange={(e) => set(i, { percent: e.target.value })} slotProps={{ htmlInput: { 'aria-label': `Percent of line ${i + 1}`, inputMode: 'decimal', style: { textAlign: 'right' } } }} />}
                  </TableCell>
                  <TableCell align="right">
                    {isFund(l.kind) ? money(amountOf(l))
                      : <TextField size="small" value={l.amount} onChange={(e) => set(i, { amount: e.target.value })} slotProps={{ htmlInput: { 'aria-label': `Amount of line ${i + 1}`, inputMode: 'decimal', style: { textAlign: 'right' } } }} />}
                  </TableCell>
                  <TableCell>
                    {isFund(l.kind)
                      ? <AccountPicker code={l.account} desc={l.accountName} label={`Account of line ${i + 1}`} search={(q) => searchPostingAccounts(branchCode, q)} recentKey="appropriation"
                          width={300} onPick={(a) => set(i, { account: a?.code ?? '', accountName: a?.description ?? '' })} />
                      : <Typography sx={{ fontSize: 13 }}>{payout} {start.settings.payoutTitle} <Typography component="span" color="text.secondary" sx={{ fontSize: 12 }}>(paid to members by the dividend vouchers)</Typography></Typography>}
                  </TableCell>
                  <TableCell><IconButton size="small" aria-label={`Remove line ${i + 1}`} onClick={() => { setDirty(true); setLines((ls) => ls.filter((_, j) => j !== i)) }}><FiTrash2 /></IconButton></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Button onClick={() => { setDirty(true); setLines((ls) => [...ls, { kind: 'OTHER', name: '', percent: '', amount: '', account: '', accountName: '' }]) }}>Add fund</Button>
            <Box sx={{ flex: 1 }} />
            <Typography>Appropriated {money(allocated)}</Typography>
            <Typography color={left < 0 ? 'error.main' : 'text.secondary'} sx={{ fontWeight: 600 }}>{left < 0 ? `More than the net surplus by ${money(-left)}` : `Left undivided ${money(left)}`}</Typography>
          </Stack>
          {save.error && <Alert severity="error">{save.error.message}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button color="inherit" onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={save.isPending || lines.length === 0 || resolution.trim().length < 5 || !date} onClick={() => save.mutate()}>Save draft</Button>
      </DialogActions>
    </Dialog>
  )
}

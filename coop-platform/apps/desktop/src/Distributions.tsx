import {
  Alert, Box, Button, ButtonBase, Checkbox, Chip, Dialog, DialogActions, DialogContent, FormControlLabel, GlobalStyles, IconButton, Stack, Switch, Table, TableBody,
  TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiDownload, FiFileText, FiPlus, FiPrinter, FiTrash2 } from 'react-icons/fi'
import {
  approveDistribution, approveDividend, downloadDistributionRegister, encodeDistribution, getDistribution, getDistributionReport, getDistributions, getDistributionSettings,
  getDistributionStatement, getDividendVouchers, postDividend, rejectDistribution, saveDistributionPolicy, saveStatutoryFunds, submitDistribution,
  type Distribution, type DistributionReport, type DistributionStatement, type DistributionStatus, type DividendVoucher, type ReportKind, type StatutoryFund,
} from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'
import { ConfirmButton, ReasonDialog } from './ui/dialogs'
import { labelOf } from './ui/labels'
import { LoadingBlock } from './ui/Loading'
import { AboutNote } from './appleUi'

const pct = (n: number) => `${Number(n.toFixed(6))}%`
const STATUS_WORDS: Record<DistributionStatus, string> = {
  DRAFT: 'Draft', SUBMITTED: 'Waiting for review', APPROVED: 'Approved', ENCODED: 'Vouchers made', REJECTED: 'Rejected', SUPERSEDED: 'Replaced',
}
const STATUS_COLOR: Record<DistributionStatus, 'default' | 'warning' | 'info' | 'success' | 'error'> = {
  DRAFT: 'default', SUBMITTED: 'warning', APPROVED: 'info', ENCODED: 'success', REJECTED: 'error', SUPERSEDED: 'default',
}
const REPORTS: [ReportKind, string][] = [
  ['net-surplus', 'Net surplus'], ['funds', 'Statutory funds'], ['isc', 'Interest on share capital'], ['pr', 'Patronage refund'], ['ledger', 'Ledger posting'], ['audit', 'Audit and reconciliation'],
]
const KIND_WORDS: Record<string, string> = { SAVINGS: 'To savings', SHARE_CAPITAL: 'To share capital', MUTUAL_BENEFIT: 'To mutual benefit', HOSPITALIZATION: 'To hospitalization' }
const label = (t: string) => <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">{t}</Typography>
const numeric = (v: string) => /^-?[\d,]+(\.\d+)?%?$/.test(v.trim())

/** Prints only the element with this id. */
const printOnly = (id: string) => (
  <GlobalStyles styles={{ '@media print': { 'body *': { visibility: 'hidden' }, [`#${id}, #${id} *`]: { visibility: 'visible' }, [`#${id}`]: { position: 'absolute', left: 0, top: 0, width: '100%', padding: '12mm', color: '#000', background: '#fff' } } }} />
)

/** One of the specification's reports, read from what was stored, printable. */
function ReportDialog({ r, onClose }: { r: DistributionReport; onClose: () => void }) {
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      {printOnly('distribution-report')}
      <DialogContent>
        <Box id="distribution-report">
          <Typography sx={{ fontSize: 17, fontWeight: 700 }}>{r.title}</Typography>
          <Typography sx={{ fontSize: 12, mb: 2 }} color="text.secondary">{r.subtitle}</Typography>
          {r.sections.map((s) => (
            <Box key={s.heading} sx={{ mb: 2.5 }}>
              <Typography sx={{ fontSize: 13, fontWeight: 700, mb: 0.5 }}>{s.heading}</Typography>
              <Table size="small" aria-label={s.heading}>
                {s.columns.some((c) => c) && <TableHead><TableRow>{s.columns.map((c, i) => <TableCell key={i} align={i > 0 ? 'right' : 'left'}>{c}</TableCell>)}</TableRow></TableHead>}
                <TableBody>
                  {s.rows.map((row, i) => (
                    <TableRow key={i}>
                      {row.map((c, j) => <TableCell key={j} align={j > 0 && (numeric(c) || c === '') ? 'right' : 'left'} sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: /^Total|^Net surplus per|^Available/.test(row[0]) ? 700 : 400 }}>{c}</TableCell>)}
                    </TableRow>
                  ))}
                  {s.rows.length === 0 && <TableRow><TableCell colSpan={Math.max(1, s.columns.length)}><Typography sx={{ fontSize: 13 }} color="text.secondary">Nothing to show.</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
              {s.notes.map((n) => <Typography key={n} sx={{ fontSize: 12, mt: 0.5 }} color="text.secondary">{n}</Typography>)}
            </Box>
          ))}
        </Box>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button><Button variant="contained" startIcon={<FiPrinter />} onClick={() => window.print()}>Print</Button></DialogActions>
    </Dialog>
  )
}

/** A member's combined distribution statement, from the stored figures. */
function StatementDialog({ s, onClose }: { s: DistributionStatement; onClose: () => void }) {
  const row = (k: string, v: string, bold = false) => <Box key={k} sx={{ display: 'flex', justifyContent: 'space-between', py: 0.4, fontWeight: bold ? 700 : 400 }}><span>{k}</span><span>{v}</span></Box>
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      {printOnly('distribution-statement')}
      <DialogContent>
        <Box id="distribution-statement" sx={{ fontSize: 13 }}>
          <Typography sx={{ fontWeight: 700, fontSize: 15, mb: 0.25 }}>Combined member distribution statement</Typography>
          <Typography sx={{ mb: 1.5 }} color="text.secondary">Financial year {s.year}{s.status !== 'ENCODED' ? ` · ${STATUS_WORDS[s.status].toLowerCase()}, not final` : ''}</Typography>
          {row('Member', `${s.name ?? ''} (${s.clientId})`, true)}
          <Box sx={{ borderTop: '1px solid', borderColor: 'divider', my: 1 }} />
          {row('Average share-month', money(s.averageShareMonth))}
          {row('Interest on share capital rate', pct(s.dividendRate))}
          {row('Interest on share capital', money(s.dividend))}
          {row('Qualified patronage', money(s.qualifiedPatronage))}
          {row('Patronage refund rate', pct(s.patronageRate))}
          {row('Patronage refund', money(s.refund))}
          <Box sx={{ borderTop: '1px solid', borderColor: 'divider', my: 1 }} />
          {row('Total distribution', money(s.total), true)}
          {(s.mutualBenefit > 0 || s.hospitalization > 0) && <>
            {row('Of it, to the mutual benefit fund', money(s.mutualBenefit))}
            {row('Of it, to hospitalization assistance', money(s.hospitalization))}
            {row('Of it, to savings or share capital', money(s.rest))}
          </>}
        </Box>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button><Button variant="contained" onClick={() => window.print()}>Print</Button></DialogActions>
    </Dialog>
  )
}

/** The vouchers made from a distribution: a second person approves each, a third posts it. */
function Vouchers({ cutoff }: { cutoff: string }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['dividend-vouchers', cutoff], queryFn: () => getDividendVouchers(cutoff), retry: false, refetchInterval: 20_000 })
  const act = useMutation({
    meta: { done: (_, v) => ((v as { kind: string }).kind === 'approve' ? 'Voucher approved. Someone else can now post it.' : 'Dividend voucher posted.') },
    mutationFn: (v: { kind: 'approve' | 'post'; r: DividendVoucher }) => (v.kind === 'approve' ? approveDividend : postDividend)(v.r.year, v.r.controlNo),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['dividend-vouchers'] }); void qc.invalidateQueries({ queryKey: ['distribution'] }) },
  })
  return (
    <Box>
      {label('Vouchers')}
      <LoadingBlock q={q} what="the vouchers" />
      {act.error && <Alert severity="error" sx={{ mb: 1 }}>{(act.error as Error).message}</Alert>}
      {(q.data ?? []).map((v) => (
        <Box key={`${v.year}-${v.controlNo}`} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider' }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontSize: 13 }}>{v.documentNo} · {money(v.total)}</Typography>
            <Typography sx={{ fontSize: 12 }} color="text.secondary">{v.lines.toLocaleString()} lines</Typography>
          </Box>
          <Chip size="small" variant="outlined" color={v.status === 'POSTED' ? 'success' : v.status === 'APPROVED' ? 'info' : 'warning'} label={v.status === 'WAITING FOR APPROVAL' ? 'Waiting for approval' : v.status === 'APPROVED' ? 'Approved' : 'Posted'} />
          {v.status === 'WAITING FOR APPROVAL' && <Button size="small" variant="contained" disabled={act.isPending} onClick={() => act.mutate({ kind: 'approve', r: v })}>Approve</Button>}
          {v.status === 'APPROVED' && (
            <ConfirmButton size="small" variant="contained" color="success" disabled={act.isPending} onConfirm={() => act.mutate({ kind: 'post', r: v })} title={`Post dividend voucher ${v.documentNo}?`}
              message={<>It credits <b>{money(v.total)}</b> to members’ accounts in {v.lines.toLocaleString()} lines. A posted voucher cannot be edited.</>}>Post</ConfirmButton>
          )}
        </Box>
      ))}
    </Box>
  )
}

function DistributionDetail({ id, onBack }: { id: number; onBack: () => void }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['distribution', id], queryFn: () => getDistribution(id) })
  const [report, setReport] = useState<DistributionReport | null>(null)
  const [statementOf, setStatementOf] = useState('')
  const [statement, setStatement] = useState<DistributionStatement | null>(null)
  const [rejecting, setRejecting] = useState(false)
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const done = () => { setError(null); void qc.invalidateQueries({ queryKey: ['distribution', id] }); void qc.invalidateQueries({ queryKey: ['distributions'] }) }
  const fail = (e: unknown) => setError((e as Error).message)
  const submit = useMutation({ meta: { done: 'Submitted for review. Someone else approves it.' }, mutationFn: () => submitDistribution(id), onSuccess: done, onError: fail })
  const approve = useMutation({ meta: { done: 'Distribution approved. The vouchers can now be made.' }, mutationFn: () => approveDistribution(id, note.trim() || null), onSuccess: done, onError: fail })
  const encode = useMutation({ meta: { done: 'Vouchers made. A second person approves each, a third posts it.' }, mutationFn: () => encodeDistribution(id), onSuccess: () => { done(); void qc.invalidateQueries({ queryKey: ['dividend-vouchers'] }) }, onError: fail })
  const openReport = useMutation({ mutationFn: (k: ReportKind) => getDistributionReport(id, k), onSuccess: setReport, onError: fail })
  const register = useMutation({ mutationFn: () => downloadDistributionRegister(id), onError: fail })
  const showStatement = useMutation({ mutationFn: () => getDistributionStatement(id, Number(statementOf)), onSuccess: setStatement, onError: fail })
  const d = q.data
  const b = d?.batch
  const fig = (k: string, v: string) => <><span>{k}</span><span style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{v}</span></>
  return (
    <Box>
      <Button size="small" onClick={onBack} sx={{ mb: 1 }}>All distributions</Button>
      <LoadingBlock q={q} what="the distribution" />
      {d && b && (
        <Stack spacing={2}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
            <Typography sx={{ fontSize: 17, fontWeight: 700, flex: 1 }}>Distribution of {b.year} · cut-off {b.cutoff}</Typography>
            <Chip color={STATUS_COLOR[b.status]} label={STATUS_WORDS[b.status]} />
          </Box>
          <Alert severity={b.status === 'REJECTED' ? 'error' : 'info'} icon={false}>{d.rights.note}</Alert>
          {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
            <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.5 }}>
              {label('Interest on share capital')}
              <Box sx={{ display: 'grid', gridTemplateColumns: '1fr auto', rowGap: 0.4, fontSize: 13 }}>
                {fig('Total average share-month', money(b.totalAverageShare))}
                {fig('Rate', pct(b.dividendRate))}
                {b.iscAllocation != null && fig('Allocated', money(b.iscAllocation))}
                {fig('Distributed', money(b.totalIsc))}
              </Box>
            </Box>
            <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.5 }}>
              {label('Patronage refund')}
              <Box sx={{ display: 'grid', gridTemplateColumns: '1fr auto', rowGap: 0.4, fontSize: 13 }}>
                {d.patronage.map((e) => fig(`${e.name} (${e.members.toLocaleString()} members)`, money(e.amount)))}
                {fig('Total qualified patronage', money(b.totalPatronage))}
                {fig('Rate', pct(b.patronageRate))}
                {b.prAllocation != null && fig('Allocated', money(b.prAllocation))}
                {fig('Distributed', money(b.totalPr))}
              </Box>
            </Box>
          </Box>

          <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.5 }}>
            {label(`${b.members.toLocaleString()} members · ${money(b.total)}`)}
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr auto', sm: 'repeat(2, 1fr auto)' }, columnGap: 3, rowGap: 0.4, fontSize: 13 }}>
              {Object.entries(d.credited).map(([k, v]) => fig(KIND_WORDS[k] ?? labelOf(k), money(v)))}
              {b.netSurplus != null && fig('Net surplus', money(b.netSurplus))}
              {b.available != null && fig('Available after statutory funds', money(b.available))}
              {d.funds.map((f) => fig(`${f.name} (${pct(f.percent)})`, money(f.amount)))}
            </Box>
            {b.approvalRef && <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">Allocation approved by {b.approvalRef}.</Typography>}
            <Typography sx={{ fontSize: 12, mt: 0.5 }} color="text.secondary">Settings used: {b.policy}</Typography>
            {d.controls.map((c) => <Typography key={c} sx={{ fontSize: 12, mt: 0.5 }}>{c}</Typography>)}
          </Box>

          {d.leftOut.length > 0 && (
            <Box>
              {label(`Left out: ${b.held} without an account to credit, ${b.excluded} not eligible`)}
              <Table size="small" aria-label="Members left out">
                <TableBody>
                  {d.leftOut.map((m) => (
                    <TableRow key={m.clientId}><TableCell>{m.clientId}</TableCell><TableCell>{m.name}</TableCell><TableCell align="right">{money(m.total)}</TableCell><TableCell sx={{ color: 'text.secondary' }}>{m.reason}</TableCell></TableRow>
                  ))}
                </TableBody>
              </Table>
              {b.held + b.excluded > d.leftOut.length && <Typography sx={{ fontSize: 12, mt: 0.5 }} color="text.secondary">The {d.leftOut.length} largest are shown.</Typography>}
            </Box>
          )}

          <Box>
            {label('Steps')}
            <Typography sx={{ fontSize: 13 }}>Prepared by {b.preparedBy} on {b.preparedAt}{b.submittedBy && `; submitted by ${b.submittedBy} on ${b.submittedAt}`}
              {b.decidedBy && `; ${b.status === 'REJECTED' ? 'rejected' : 'approved'} by ${b.decidedBy} on ${b.decidedAt}`}{b.decisionNote && ` (${b.decisionNote})`}
              {b.encodedBy && `; vouchers made by ${b.encodedBy} on ${b.encodedAt}`}.</Typography>
            <Stack direction="row" spacing={1} sx={{ mt: 1, flexWrap: 'wrap', rowGap: 1 }}>
              {d.rights.maySubmit && <Button variant="contained" disabled={submit.isPending} onClick={() => submit.mutate()}>Submit for review</Button>}
              {d.rights.mayApprove && <>
                <TextField label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} sx={{ width: 260 }} />
                <ConfirmButton variant="contained" disabled={approve.isPending} title={`Approve the ${b.year} distribution?`} onConfirm={() => approve.mutate()}
                  message={<>{b.members.toLocaleString()} members receive <b>{money(b.total)}</b> ({money(b.totalIsc)} interest on share capital, {money(b.totalPr)} patronage refund). Once approved the figures cannot change; the vouchers are made from them.</>}>
                  Approve
                </ConfirmButton>
              </>}
              {d.rights.mayReject && <Button color="error" onClick={() => setRejecting(true)}>Reject</Button>}
              {d.rights.mayEncode && (
                <ConfirmButton variant="contained" disabled={encode.isPending} title={`Make the vouchers of ${b.year}?`} onConfirm={() => encode.mutate()}
                  message={<>Writes the dividend vouchers dated {b.cutoff}, crediting <b>{money(b.total)}</b> to {b.members.toLocaleString()} members from the fund account. They then wait for a second person to approve and a third to post.</>}>
                  Make the vouchers
                </ConfirmButton>
              )}
            </Stack>
          </Box>

          {b.status === 'ENCODED' && <Vouchers cutoff={b.cutoff} />}

          <Box>
            {label('Reports')}
            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1, mb: 1 }}>
              {REPORTS.map(([k, t]) => <Button key={k} variant="outlined" size="small" startIcon={<FiFileText />} disabled={openReport.isPending} onClick={() => openReport.mutate(k)}>{t}</Button>)}
              <Button variant="outlined" size="small" startIcon={<FiDownload />} disabled={register.isPending} onClick={() => register.mutate()}>Distribution register (CSV)</Button>
            </Stack>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
              <TextField label="Member no." value={statementOf} onChange={(e) => setStatementOf(e.target.value.replace(/\D/g, ''))} sx={{ width: 140 }} />
              <Button variant="outlined" size="small" disabled={!statementOf || showStatement.isPending} onClick={() => showStatement.mutate()}>Member statement</Button>
            </Stack>
          </Box>
        </Stack>
      )}
      {report && <ReportDialog r={report} onClose={() => setReport(null)} />}
      {statement && <StatementDialog s={statement} onClose={() => setStatement(null)} />}
      {rejecting && <ReasonDialog title="Reject the distribution" label="Why it is rejected" onClose={() => setRejecting(false)} onRun={(why) => rejectDistribution(id, why).then(done)} />}
    </Box>
  )
}

/** Every distribution, newest year first, with replaced and rejected versions kept; one opens its figures, steps and reports. */
export function DistributionList({ openId, onOpen }: { openId: number | null; onOpen: (id: number | null) => void }) {
  const q = useQuery({ queryKey: ['distributions'], queryFn: getDistributions })
  if (openId != null) return <DistributionDetail id={openId} onBack={() => onOpen(null)} />
  return (
    <Box>
      <LoadingBlock q={q} what="the distributions" />
      {q.data?.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">No distribution yet. Prepare one in Prepare.</Typography>}
      {(q.data ?? []).map((b: Distribution) => (
        <ButtonBase key={b.id} onClick={() => onOpen(b.id)} sx={{ display: 'flex', width: '100%', textAlign: 'left', gap: 1.5, alignItems: 'center', p: 1.25, borderTop: 1, borderColor: 'divider', '&:hover': { bgcolor: 'action.hover' } }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{b.year} · {money(b.total)} to {b.members.toLocaleString()} members</Typography>
            <Typography sx={{ fontSize: 12 }} color="text.secondary">
              Cut-off {b.cutoff} · {b.method === 'ALLOCATION' ? 'from the allocation' : 'typed rates'} · interest {pct(b.dividendRate)}, refund {pct(b.patronageRate)} · prepared by {b.preparedBy} {b.preparedAt}
            </Typography>
          </Box>
          <Chip size="small" color={STATUS_COLOR[b.status]} variant={b.status === 'SUPERSEDED' ? 'outlined' : 'filled'} label={STATUS_WORDS[b.status]} />
        </ButtonBase>
      ))}
    </Box>
  )
}

/** Choose which ids from a list, or everyone. */
function Chooser({ title, all, everyone, chosen, onChange, disabled }: {
  title: string; all: { id: number; name: string }[]; everyone: string; chosen: number[] | null; onChange: (v: number[] | null) => void; disabled: boolean
}) {
  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.5 }}>
      <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{title}</Typography>
      <FormControlLabel disabled={disabled} control={<Switch checked={chosen == null} onChange={(e) => onChange(e.target.checked ? null : all.map((x) => x.id))} />}
        label={<Typography sx={{ fontSize: 13 }}>{everyone}</Typography>} />
      {chosen != null && (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, mt: 0.5 }}>
          {all.map((x) => (
            <FormControlLabel key={x.id} disabled={disabled} label={<Typography sx={{ fontSize: 13 }}>{x.name} ({x.id})</Typography>}
              control={<Checkbox size="small" checked={chosen.includes(x.id)} onChange={(e) => onChange(e.target.checked ? [...chosen, x.id] : chosen.filter((y) => y !== x.id))} />} />
          ))}
        </Box>
      )}
    </Box>
  )
}

/**
 * The settings each year's distribution starts from (spec sections 2 and 11): which loan charges count as patronage, who takes part, which member types earn interest
 * and refunds, a minimum average share, and the statutory funds. Nothing statutory is built in; administrators change them with a reason.
 */
export function DistributionSettingsView() {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['distribution-settings'], queryFn: getDistributionSettings })
  const [draft, setDraft] = useState<{ events: number[]; statuses: number[] | null; isc: number[] | null; pr: number[] | null; min: string } | null>(null)
  const [funds, setFunds] = useState<StatutoryFund[] | null>(null)
  const [saving, setSaving] = useState<'policy' | 'funds' | null>(null)
  const s = q.data
  const may = !!s?.mayEdit
  const p = draft ?? (s ? { events: s.policy.patronageEvents, statuses: s.policy.eligibleStatuses, isc: s.policy.iscTypes, pr: s.policy.prTypes, min: String(s.policy.minAverageShare ?? 0) } : null)
  const f = funds ?? s?.funds.filter((x) => x.active) ?? []
  const set = (patch: Partial<NonNullable<typeof p>>) => setDraft({ ...p!, ...patch })
  const totalPct = f.reduce((t, x) => t + (Number(x.percent) || 0), 0)
  const save = (reason: string) => (saving === 'policy'
    ? saveDistributionPolicy({ patronageEvents: p!.events, eligibleStatuses: p!.statuses, iscTypes: p!.isc, prTypes: p!.pr, minAverageShare: Number(p!.min) || 0 }, reason)
    : saveStatutoryFunds(f.map((x) => ({ ...x, percent: Number(x.percent) })), reason)
  ).then((r) => { qc.setQueryData(['distribution-settings'], r); setDraft(null); setFunds(null) })
  return (
    <Box>
      <LoadingBlock q={q} what="the settings" />
      {s && p && (
        <Stack spacing={2}>
          <Typography sx={{ fontSize: 13 }} color="text.secondary">
            Each year&apos;s distribution starts from these. They are the cooperative&apos;s choices, not built into the system: check them against RA 9520, the CDA rules and your bylaws.
            {!may && ' Only an administrator can change them.'}{s.policy.updatedBy && ` Last changed by ${s.policy.updatedBy} on ${s.policy.updatedAt}.`}
          </Typography>
          <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.5 }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600 }}>Qualified patronage: what members paid on their loans that counts</Typography>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' } }}>
              {s.events.map((e) => (
                <FormControlLabel key={e.id} disabled={!may} label={<Typography sx={{ fontSize: 13 }}>{e.name} ({e.id})</Typography>}
                  control={<Checkbox size="small" checked={p.events.includes(e.id)} onChange={(ev) => set({ events: ev.target.checked ? [...p.events, e.id] : p.events.filter((x) => x !== e.id) })} />} />
              ))}
            </Box>
            <AboutNote>The old system counted Interest Paid (23), Interest Earned (24) and Service Fee (31). Only amounts paid count, not billed or accrued ones.</AboutNote>
          </Box>
          <Chooser title="Who takes part, by member status" all={s.statuses} everyone="Every member status" chosen={p.statuses} onChange={(v) => set({ statuses: v })} disabled={!may} />
          <Chooser title="Who earns interest on share capital, by member type" all={s.types} everyone="Every member type" chosen={p.isc} onChange={(v) => set({ isc: v })} disabled={!may} />
          <Chooser title="Who earns a patronage refund, by member type" all={s.types} everyone="Every member type" chosen={p.pr} onChange={(v) => set({ pr: v })} disabled={!may} />
          <TextField label="Minimum average share-month for interest on share capital" value={p.min} disabled={!may} onChange={(e) => set({ min: e.target.value })} sx={{ maxWidth: 380 }} helperText="0 for none" />
          {may && <Box><Button variant="contained" disabled={!draft} onClick={() => setSaving('policy')}>Save these settings</Button></Box>}

          <Box sx={{ border: 1, borderColor: 'divider', borderRadius: '8px', p: 1.5 }}>
            <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1 }}>Statutory funds set aside from the net surplus</Typography>
            {f.length === 0 && <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">None yet. Add the reserve fund, education and training fund, community development fund and optional fund with the percents your bylaws set.</Typography>}
            {f.map((x, i) => (
              <Box key={x.id ?? `new-${i}`} sx={{ display: 'flex', gap: 1, mb: 1, alignItems: 'center', flexWrap: 'wrap' }}>
                <TextField label="Fund" value={x.name} disabled={!may} onChange={(e) => setFunds(f.map((y, k) => (k === i ? { ...y, name: e.target.value } : y)))} sx={{ width: 280 }} />
                <TextField label="% of net surplus" value={String(x.percent)} disabled={!may} onChange={(e) => setFunds(f.map((y, k) => (k === i ? { ...y, percent: e.target.value as unknown as number } : y)))} sx={{ width: 150 }} />
                {may && <IconButton aria-label={`Remove ${x.name || 'fund'}`} onClick={() => setFunds(f.filter((_, k) => k !== i))}><FiTrash2 /></IconButton>}
              </Box>
            ))}
            <Typography sx={{ fontSize: 12, mb: 1 }} color={totalPct > 100 ? 'error' : 'text.secondary'}>Together {Number(totalPct.toFixed(4))}% of the net surplus.</Typography>
            {may && <Stack direction="row" spacing={1}>
              <Button startIcon={<FiPlus />} onClick={() => setFunds([...f, { id: null, name: '', percent: 0, account: null, active: true }])}>Add fund</Button>
              <Button variant="contained" disabled={!funds} onClick={() => setSaving('funds')}>Save the funds</Button>
            </Stack>}
          </Box>
        </Stack>
      )}
      {saving && <ReasonDialog title={saving === 'policy' ? 'Save the dividend settings' : 'Save the statutory funds'} label="Reason for the change" onClose={() => setSaving(null)} onRun={save} />}
    </Box>
  )
}

/** The frame around the three parts, so DividendsPage stays small. */
export function DistributionsFrame({ children }: { children: React.ReactNode }) {
  return <Box sx={(t) => ({ p: 1.5, ...glass(t) })}>{children}</Box>
}

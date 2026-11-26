import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField,
  Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useRef, useState } from 'react'
import { FiTrash2 } from 'react-icons/fi'
import {
  cancelOpeningBatch, createOpeningBatch, downloadOpeningTemplate, getMemoAccounts, getOpeningBatch, getOpeningTypes, importOpeningFile, listOpeningBatches, LOAN_STATUSES,
  openingStep, returnOpeningBatch, searchPostingAccounts, updateOpeningBatch, type OpeningDetail, type OpeningKind, type OpeningLine, type OpeningLineIn, type OpeningSummary,
  type OpeningType,
} from './api'
import { AboutNote, ReasonDialog } from './appleUi'
import { glass } from './glass'
import { useMe } from './session'
import { AccountPicker } from './ui/AccountPicker'
import { ConfirmButton } from './ui/dialogs'
import { money } from './ui/format'
import { labelOf } from './ui/labels'
import { LoadingBlock, LoadingRow } from './ui/Loading'
import { useLeaveGuard } from './leaveGuard'

const KIND_WORDS: Record<OpeningKind, string> = { GL: 'Ledger account', SAVINGS: 'Savings', SHARE: 'Share capital', LOAN: 'Loan', TD: 'Time deposit' }
/** Kinds that open their account when the batch is posted. */
const opens = (k: OpeningKind) => k === 'LOAN' || k === 'TD'

/** A loan's or time deposit's terms in a line of words, for the list of lines. */
function termsText(l: OpeningLine): string | null {
  const t = l.line
  if (!t) return null
  if (l.kind === 'LOAN') {
    return [LOAN_STATUSES[t.status ?? 11], t.terms != null ? `${t.terms} months to run` : null, t.startDate ? `next due ${t.startDate}` : null,
      t.rate != null ? `${t.rate}% a year` : 'the loan type’s rate', t.originalAmount ? `granted ${money(t.originalAmount)}${t.grantedOn ? ` on ${t.grantedOn}` : ''}` : null]
      .filter(Boolean).join(' · ')
  }
  return [t.startDate ? `placed ${t.startDate}` : null, t.terms != null ? `${t.terms} days` : null, l.maturity ? `matures ${l.maturity}` : null,
    t.rate != null ? `${t.rate}% a year` : null, t.accrued ? `accrued interest ${money(t.accrued)}${l.accrualAccount ? ` to ${l.accrualAccount}` : ''}` : null]
    .filter(Boolean).join(' · ')
}
/** Above this many lines a batch is shown, not edited line by line here: change the file and import it again. */
const EDITABLE = 200

/**
 * Accounting > Opening balances: a new cooperative's balances on the day it starts on this system. A batch is typed or imported from a file, checked against the
 * chart and the members' accounts, sent for review, approved by another person and posted by a third as one General Journal. The members' savings and share
 * capital go to their control accounts, so the members' totals and the books agree from the first day.
 */
export default function OpeningBalancesTab({ branchCode, canEdit }: { branchCode: number; canEdit: boolean }) {
  const list = useQuery({ queryKey: ['opening-batches', branchCode], queryFn: () => listOpeningBatches(branchCode) })
  const [open, setOpen] = useState<number | null>(null)
  const [editing, setEditing] = useState<{ id: number | null; detail?: OpeningDetail } | null>(null)
  const posted = (list.data ?? []).filter((b) => b.status === 'POSTED')

  return (
    <Stack spacing={2}>
      <AboutNote>
        Enter what the cooperative has on the day it starts on this system: cash, banks, fixed assets, payables and equity as ledger lines, and each member’s savings,
        share capital, loans still being paid and time deposits still running as member lines. Do not type the control accounts (members’ savings, paid-up share capital,
        loans receivable, time deposits): they come out as the sum of the members, so the members’ accounts and the books agree from the first day. A loan line is what the
        member still owes, with the months still to run and the next due date: posting opens the loan with its remaining schedule. A time deposit line is the
        certificate’s principal with its placement date, term and rate, and the interest it earned up to the opening date: posting opens the certificate, numbered by
        the system. Prepare a batch, send it for review; another person approves it and a third posts it as one General Journal. Large lists are easiest from a file:
        download the template, fill it in, and import it.
      </AboutNote>
      {posted.length > 0 && (
        <Alert severity="success">
          Opening balances posted: {posted.map((b) => `${b.reference} as of ${b.openingDate} (General Journal ${b.documentNo})`).join('; ')}. Correct a member’s balance with a
          debit/credit memo and a ledger balance with a journal.
        </Alert>
      )}
      <Box sx={(t) => ({ ...glass(t), p: 2 })}>
        <Stack direction="row" spacing={1} sx={{ mb: 1, alignItems: 'center' }}>
          <Typography sx={{ fontWeight: 600, flex: 1 }}>Batches</Typography>
          <Button onClick={downloadOpeningTemplate}>Download template</Button>
          {canEdit && <Button variant="contained" onClick={() => setEditing({ id: null })}>New opening balances</Button>}
        </Stack>
        <Table size="small" aria-label="Opening balance batches">
          <TableHead>
            <TableRow>
              <TableCell>Reference</TableCell><TableCell>As of</TableCell><TableCell>Description</TableCell><TableCell align="right">Lines</TableCell>
              <TableCell align="right">Debits</TableCell><TableCell align="right">Credits</TableCell><TableCell>Status</TableCell><TableCell>Prepared by</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            <LoadingRow q={list} what="the opening balances" />
            {list.data?.length === 0 && <TableRow><TableCell colSpan={8}><Typography color="text.secondary">No opening balances yet.</Typography></TableCell></TableRow>}
            {list.data?.map((b) => (
              <TableRow key={b.id} hover selected={open === b.id} onClick={() => setOpen(b.id)} sx={{ cursor: 'pointer' }}>
                <TableCell>{b.reference}</TableCell>
                <TableCell>{b.openingDate}</TableCell>
                <TableCell>{b.description}</TableCell>
                <TableCell align="right">{b.lines}</TableCell>
                <TableCell align="right">{money(b.debits)}</TableCell>
                <TableCell align="right">{money(b.credits)}</TableCell>
                <TableCell><StatusChip s={b.status} /></TableCell>
                <TableCell>{b.createdBy}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
      {open != null && <BatchView id={open} canEdit={canEdit} onEdit={(d) => setEditing({ id: d.summary.id, detail: d })} />}
      {editing && (
        <Editor branchCode={branchCode} id={editing.id} detail={editing.detail} onClose={() => setEditing(null)} onSaved={(s) => { setEditing(null); setOpen(s.id) }} />
      )}
    </Stack>
  )
}

function StatusChip({ s }: { s: OpeningSummary['status'] }) {
  const color = s === 'POSTED' ? 'success' : s === 'APPROVED' ? 'info' : s === 'FOR_REVIEW' ? 'warning' : 'default'
  return <Chip size="small" color={color} label={labelOf(s)} />
}

// ---- one batch ------------------------------------------------------------------------------------------------------

function BatchView({ id, canEdit, onEdit }: { id: number; canEdit: boolean; onEdit: (d: OpeningDetail) => void }) {
  const me = useMe()
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['opening-batch', id], queryFn: () => getOpeningBatch(id) })
  const [asking, setAsking] = useState<'return' | 'cancel' | null>(null)
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['opening-batch', id] }); void qc.invalidateQueries({ queryKey: ['opening-batches'] }) }
  const done: Record<'submit' | 'approve' | 'post', string> = { submit: 'Sent for review.', approve: 'Approved. A third person posts it.', post: 'Opening balances posted to the ledger.' }
  const step = useMutation({ mutationFn: (s: 'submit' | 'approve' | 'post') => openingStep(id, s), onSuccess: refresh, meta: { done: (_r: unknown, s: unknown) => done[s as 'submit' | 'approve' | 'post'] } })
  const reason = useMutation({
    mutationFn: ({ kind, why }: { kind: 'return' | 'cancel'; why: string }) => (kind === 'return' ? returnOpeningBatch(id, why) : cancelOpeningBatch(id, why)),
    onSuccess: () => { setAsking(null); refresh() },
    meta: { done: (_r: unknown, v: unknown) => ((v as { kind: string }).kind === 'return' ? 'Sent back to the person who prepared it.' : 'Opening balances cancelled.') },
  })
  if (!q.data) return <LoadingBlock q={q} what="the opening balances" />
  const d = q.data
  const s = d.summary
  const mine = s.createdBy.toLowerCase() === me.username.toLowerCase()
  const dr = d.trialBalance.reduce((t, l) => t + l.debit, 0)
  const cr = d.trialBalance.reduce((t, l) => t + l.credit, 0)
  const loans = d.lines.filter((l) => l.kind === 'LOAN').length
  const deposits = d.lines.filter((l) => l.kind === 'TD').length
  const opened = [loans ? `${loans} loan${loans === 1 ? '' : 's'}` : null, deposits ? `${deposits} time deposit${deposits === 1 ? '' : 's'}` : null].filter(Boolean).join(' and ')

  return (
    <Box sx={(t) => ({ ...glass(t), p: 2 })}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
        <Typography sx={{ fontWeight: 600 }}>{s.reference}</Typography>
        <StatusChip s={s.status} />
        <Typography color="text.secondary" sx={{ flex: 1 }}>
          as of {s.openingDate} · prepared by {s.createdBy}{s.approvedBy ? ` · approved by ${s.approvedBy}` : ''}{s.postedBy ? ` · posted by ${s.postedBy} as General Journal ${s.documentNo}` : ''}
        </Typography>
        {canEdit && s.status === 'DRAFT' && (mine || me.admin) && <Button onClick={() => onEdit(d)}>Change</Button>}
        {canEdit && s.status === 'DRAFT' && (mine || me.admin) && (
          <Button variant="contained" disabled={d.problems.length > 0 || step.isPending} onClick={() => step.mutate('submit')}>Send for review</Button>
        )}
        {canEdit && s.status === 'FOR_REVIEW' && !mine && <Button variant="contained" disabled={d.problems.length > 0 || step.isPending} onClick={() => step.mutate('approve')}>Approve</Button>}
        {canEdit && (s.status === 'FOR_REVIEW' || s.status === 'APPROVED') && !mine && <Button onClick={() => setAsking('return')}>Send back</Button>}
        {canEdit && s.status === 'APPROVED' && !mine && s.approvedBy?.toLowerCase() !== me.username.toLowerCase() && (
          <ConfirmButton variant="contained" disabled={step.isPending} title="Post the opening balances?"
            message={`This writes one General Journal dated ${s.openingDate}: ${s.lines} lines, debits and credits of ${money(s.debits)} each. The members’ accounts get their first entry${opened ? `, and ${opened} ${loans + deposits === 1 ? 'is' : 'are'} opened` : ''}. Once posted it cannot be edited or cancelled; corrections are made with memos and journals.`}
            onConfirm={() => step.mutate('post')}>Post</ConfirmButton>
        )}
        {canEdit && s.status !== 'POSTED' && s.status !== 'CANCELLED' && (mine || me.admin) && <Button color="error" onClick={() => setAsking('cancel')}>Cancel batch</Button>}
      </Stack>
      <Typography sx={{ mb: 1 }}>{s.description}</Typography>
      {step.error && <Alert severity="error" sx={{ mb: 1 }}>{step.error.message}</Alert>}
      {s.status === 'CANCELLED' && d.cancelReason && <Alert severity="info" sx={{ mb: 1 }}>Cancelled: {d.cancelReason}</Alert>}
      {s.status === 'FOR_REVIEW' && mine && <Alert severity="info" sx={{ mb: 1 }}>Waiting for another accountant or an administrator to review and approve it.</Alert>}
      {s.status === 'APPROVED' && (mine || s.approvedBy?.toLowerCase() === me.username.toLowerCase()) && <Alert severity="info" sx={{ mb: 1 }}>Approved. A third person, not the one who prepared or approved it, posts it.</Alert>}
      {d.problems.length > 0 && (
        <Alert severity="warning" sx={{ mb: 1 }}>
          <Typography sx={{ fontWeight: 600 }}>To fix before it can be sent on ({d.problems.length})</Typography>
          <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{d.problems.slice(0, 30).map((p) => <li key={p}>{p}</li>)}</Box>
          {d.problems.length > 30 && <Typography>And {d.problems.length - 30} more.</Typography>}
        </Alert>
      )}

      <Typography sx={{ fontWeight: 600, mt: 2, mb: 0.5 }}>Trial balance it makes</Typography>
      <Table size="small" aria-label="Trial balance of the opening balances">
        <TableHead>
          <TableRow><TableCell>Account</TableCell><TableCell>Title</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell><TableCell>From</TableCell></TableRow>
        </TableHead>
        <TableBody>
          {d.trialBalance.map((t) => (
            <TableRow key={t.account}>
              <TableCell>{t.account}</TableCell>
              <TableCell>{t.title}</TableCell>
              <TableCell align="right">{t.debit ? money(t.debit) : ''}</TableCell>
              <TableCell align="right">{t.credit ? money(t.credit) : ''}</TableCell>
              <TableCell>{t.memberAccounts > 0 ? `${t.memberAccounts} member account${t.memberAccounts === 1 ? '' : 's'}` : 'Ledger line'}</TableCell>
            </TableRow>
          ))}
          <TableRow>
            <TableCell colSpan={2} sx={{ fontWeight: 600 }}>Total{Math.round((dr - cr) * 100) !== 0 ? ` (difference ${money(Math.abs(dr - cr))})` : ''}</TableCell>
            <TableCell align="right" sx={{ fontWeight: 600 }}>{money(dr)}</TableCell>
            <TableCell align="right" sx={{ fontWeight: 600 }}>{money(cr)}</TableCell>
            <TableCell />
          </TableRow>
        </TableBody>
      </Table>

      <Typography sx={{ fontWeight: 600, mt: 2, mb: 0.5 }}>Lines ({d.lines.length})</Typography>
      <Table size="small" aria-label="Lines of the opening balances">
        <TableHead>
          <TableRow><TableCell>#</TableCell><TableCell>Kind</TableCell><TableCell>On</TableCell><TableCell>Posts to</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell><TableCell>Note</TableCell></TableRow>
        </TableHead>
        <TableBody>
          {d.lines.slice(0, EDITABLE).map((l) => (
            <TableRow key={l.lineNo}>
              <TableCell>{l.lineNo}</TableCell>
              <TableCell>{KIND_WORDS[l.kind]}</TableCell>
              <TableCell>
                {l.kind === 'GL' ? l.accountTitle || l.accountCode
                  : `${l.memberName ?? `Member ${l.clientId}`} · ${l.typeName ?? `type ${l.typeCode}`}${opens(l.kind) ? (l.refNo ? ` ${l.refNo}` : ' (opened when posted)') : l.accountNo ? ` ${l.accountNo}` : ''}`}
                {termsText(l) && <Typography component="div" color="text.secondary" sx={{ fontSize: 12 }}>{termsText(l)}</Typography>}
              </TableCell>
              <TableCell>{l.accountCode}</TableCell>
              <TableCell align="right">{l.debit ? money(l.debit) : ''}</TableCell>
              <TableCell align="right">{l.credit ? money(l.credit) : ''}</TableCell>
              <TableCell>{l.note}</TableCell>
            </TableRow>
          ))}
          {d.lines.length > EDITABLE && <TableRow><TableCell colSpan={7}><Typography color="text.secondary">And {d.lines.length - EDITABLE} more lines; the trial balance above covers them all.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>

      <ReasonDialog open={asking != null} title={asking === 'return' ? 'Send the opening balances back' : 'Cancel the opening balances'}
        intro={asking === 'return' ? 'They go back to the person who prepared them as a draft, with your reason.' : 'Nothing has been posted. The batch is kept, marked cancelled, with your reason.'}
        confirm={asking === 'return' ? 'Send back' : 'Cancel batch'} danger={asking === 'cancel'} busy={reason.isPending} error={reason.error?.message ?? null}
        onCancel={() => setAsking(null)} onConfirm={(why) => asking && reason.mutate({ kind: asking, why })} />
    </Box>
  )
}

// ---- preparing a batch ----------------------------------------------------------------------------------------------

/** A loan's or time deposit's terms as typed: see OpeningLineIn. `start` is a loan's next due date or a certificate's placement date. */
interface DraftTerms { status: number; rate: string; terms: string; start: string; granted: string; grantedOn: string; accrued: string }
interface Draft {
  kind: OpeningKind; account: string; accountName: string; client: string; typeCode: number | null; refNo: number | null; debit: string; credit: string; note: string; t: DraftTerms
}
const noTerms = (): DraftTerms => ({ status: 11, rate: '', terms: '', start: '', granted: '', grantedOn: '', accrued: '' })
const blank = (kind: OpeningKind = 'GL'): Draft => ({ kind, account: '', accountName: '', client: '', typeCode: null, refNo: null, debit: '', credit: '', note: '', t: noTerms() })
const today = () => new Date().toLocaleDateString('en-CA')
const num = (s: string) => (s.trim() === '' ? null : Number(s.replace(/,/g, '')))
const text = (v: number | string | null | undefined) => (v == null ? '' : String(v))

function fromIn(l: OpeningLineIn): Draft {
  return { kind: l.kind, account: l.accountCode ?? '', accountName: '', client: l.clientId == null ? '' : String(l.clientId), typeCode: l.typeCode, refNo: l.refNo,
    debit: l.debit ? String(l.debit) : '', credit: l.credit ? String(l.credit) : '', note: l.note ?? '',
    t: { status: l.status ?? 11, rate: text(l.rate), terms: text(l.terms), start: l.startDate ?? '', granted: text(l.originalAmount), grantedOn: l.grantedOn ?? '', accrued: text(l.accrued) } }
}

function toIn(l: Draft): OpeningLineIn {
  if (l.kind === 'GL') return { kind: 'GL', clientId: null, typeCode: null, refNo: null, accountCode: l.account.trim() || null, debit: num(l.debit), credit: num(l.credit), note: l.note.trim() || null }
  const base = { kind: l.kind, clientId: Number(l.client) || null, typeCode: l.typeCode, accountCode: null, debit: num(l.debit), credit: num(l.credit), note: l.note.trim() || null }
  if (l.kind === 'LOAN') {
    return { ...base, refNo: null, credit: null, status: l.t.status, rate: num(l.t.rate), terms: num(l.t.terms), startDate: l.t.start || null, originalAmount: num(l.t.granted), grantedOn: l.t.grantedOn || null }
  }
  if (l.kind === 'TD') return { ...base, refNo: null, debit: null, rate: num(l.t.rate), terms: num(l.t.terms), startDate: l.t.start || null, accrued: num(l.t.accrued) }
  return { ...base, refNo: l.refNo }
}

function Editor({ branchCode, id, detail, onClose, onSaved }: { branchCode: number; id: number | null; detail?: OpeningDetail; onClose: () => void; onSaved: (s: OpeningSummary) => void }) {
  const qc = useQueryClient()
  const [date, setDate] = useState(detail?.summary.openingDate ?? today())
  const [description, setDescription] = useState(detail?.summary.description ?? '')
  const [lines, setLines] = useState<Draft[]>(() => detail
    ? detail.lines.map((l) => ({ ...(l.line ? fromIn(l.line) : blank(l.kind)), kind: l.kind, account: l.kind === 'GL' ? l.accountCode ?? '' : '', accountName: l.kind === 'GL' ? l.accountTitle ?? '' : '',
      client: l.clientId == null ? '' : String(l.clientId), typeCode: l.typeCode, refNo: opens(l.kind) ? null : l.refNo, debit: l.debit ? String(l.debit) : '', credit: l.credit ? String(l.credit) : '',
      note: l.note ?? '' }))
    : [blank('GL'), blank('SHARE')])
  const types = useQuery({ queryKey: ['opening-types', branchCode], queryFn: () => getOpeningTypes(branchCode), staleTime: 300_000 })
  const [dirty, setDirty] = useState(false)
  const [fileProblems, setFileProblems] = useState<string[]>([])
  const fileInput = useRef<HTMLInputElement>(null)
  useLeaveGuard(dirty ? 'The opening balances you are preparing are not saved.' : null)
  const set = (i: number, patch: Partial<Draft>) => { setDirty(true); setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l))) }
  const dr = lines.reduce((s, l) => s + (num(l.debit) || 0), 0)
  const cr = lines.reduce((s, l) => s + (num(l.credit) || 0) + (l.kind === 'TD' ? num(l.t.accrued) || 0 : 0), 0)
  const setTerms = (i: number, patch: Partial<DraftTerms>) => set(i, { t: { ...lines[i].t, ...patch } })
  const difference = Math.round((dr - cr) * 100) / 100
  const big = lines.length > EDITABLE

  const read = useMutation({
    mutationFn: (f: File) => importOpeningFile(f),
    onSuccess: (r) => { setDirty(true); setLines(r.lines.map(fromIn)); setFileProblems(r.problems) },
  })
  const save = useMutation({
    mutationFn: () => {
      const body = { branchCode, openingDate: date, description: description.trim(), lines: lines.map(toIn) }
      return id == null ? createOpeningBatch(body) : updateOpeningBatch(id, body)
    },
    onSuccess: (s) => {
      void qc.invalidateQueries({ queryKey: ['opening-batches'] })
      void qc.invalidateQueries({ queryKey: ['opening-batch', s.id] })
      onSaved(s)
    },
    meta: { done: 'Opening balances saved as a draft. Check the list of things to fix, then send it for review.' },
  })

  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth aria-labelledby="opening-title">
      <DialogTitle id="opening-title">{id == null ? 'New opening balances' : `Change ${detail?.summary.reference ?? ''}`}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ pt: 1 }}>
          <Stack direction="row" spacing={1.5}>
            <TextField type="date" label="As of" value={date} onChange={(e) => { setDirty(true); setDate(e.target.value) }} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 170 }}
              helperText="The first day on this system" />
            <TextField label="Description" value={description} onChange={(e) => { setDirty(true); setDescription(e.target.value) }} sx={{ flex: 1 }}
              placeholder="Opening balances as of 1 January 2027 per the audited statements" slotProps={{ htmlInput: { maxLength: 200 } }} />
          </Stack>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Button onClick={() => fileInput.current?.click()} disabled={read.isPending}>{read.isPending ? 'Reading…' : 'Import from a file'}</Button>
            <input ref={fileInput} type="file" accept=".csv,.xlsx" hidden aria-label="Opening balances file"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) read.mutate(f); e.target.value = '' }} />
            <Button onClick={downloadOpeningTemplate}>Download template</Button>
            <Typography color="text.secondary" sx={{ fontSize: 12 }}>Importing replaces the lines below.</Typography>
          </Stack>
          {read.error && <Alert severity="error">{read.error.message}</Alert>}
          {fileProblems.length > 0 && (
            <Alert severity="warning">
              {fileProblems.length} row{fileProblems.length === 1 ? ' was' : 's were'} left out of the file:
              <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{fileProblems.slice(0, 15).map((p) => <li key={p}>{p}</li>)}</Box>
            </Alert>
          )}
          {big ? (
            <Alert severity="info">
              {lines.length} lines read. A list this long is not edited line by line here: correct the file and import it again. Saving checks every line against the chart and the members’ accounts.
            </Alert>
          ) : (
            <Table size="small" aria-label="Lines">
              <TableHead>
                <TableRow><TableCell sx={{ width: 150 }}>Kind</TableCell><TableCell>Account</TableCell><TableCell sx={{ width: 130 }} align="right">Debit</TableCell><TableCell sx={{ width: 130 }} align="right">Credit</TableCell><TableCell sx={{ width: 170 }}>Note</TableCell><TableCell sx={{ width: 40 }} /></TableRow>
              </TableHead>
              <TableBody>
                {lines.map((l, i) => (
                  <Fragment key={i}>
                  <TableRow sx={opens(l.kind) ? { '& > td': { borderBottom: 0 } } : undefined}>
                    <TableCell>
                      <TextField select size="small" fullWidth value={l.kind} aria-label={`Kind of line ${i + 1}`}
                        onChange={(e) => {
                          const k = e.target.value as OpeningKind
                          set(i, { ...blank(k), debit: k === 'GL' || k === 'LOAN' ? l.debit : '', credit: k === 'LOAN' ? '' : l.credit, note: l.note })
                        }}>
                        {(Object.keys(KIND_WORDS) as OpeningKind[]).map((k) => <MenuItem key={k} value={k}>{KIND_WORDS[k]}</MenuItem>)}
                      </TextField>
                    </TableCell>
                    <TableCell>
                      {l.kind === 'GL'
                        ? <AccountPicker code={l.account} desc={l.accountName} label={`Account of line ${i + 1}`} search={(q) => searchPostingAccounts(branchCode, q)} recentKey="opening"
                            width={320} onPick={(a) => set(i, { account: a?.code ?? '', accountName: a?.description ?? '' })} />
                        : opens(l.kind)
                          ? <OpenedAccount line={l} index={i} types={(types.data ?? []).filter((t) => t.kind === l.kind)}
                              onChange={(p, terms) => set(i, { ...p, ...(terms ? { t: { ...l.t, ...terms } } : {}) })} />
                          : <MemberAccount branchCode={branchCode} line={l} onChange={(p) => set(i, p)} />}
                    </TableCell>
                    <TableCell><TextField size="small" value={l.debit} disabled={l.kind !== 'GL' && l.kind !== 'LOAN'} onChange={(e) => set(i, { debit: e.target.value })} slotProps={{ htmlInput: { 'aria-label': `Debit of line ${i + 1}`, inputMode: 'decimal', style: { textAlign: 'right' } } }} /></TableCell>
                    <TableCell><TextField size="small" value={l.credit} disabled={l.kind === 'LOAN'} onChange={(e) => set(i, { credit: e.target.value })} slotProps={{ htmlInput: { 'aria-label': `Credit of line ${i + 1}`, inputMode: 'decimal', style: { textAlign: 'right' } } }} /></TableCell>
                    <TableCell><TextField size="small" value={l.note} onChange={(e) => set(i, { note: e.target.value })} slotProps={{ htmlInput: { 'aria-label': `Note of line ${i + 1}`, maxLength: 100 } }} /></TableCell>
                    <TableCell><IconButton size="small" aria-label={`Remove line ${i + 1}`} onClick={() => { setDirty(true); setLines((ls) => ls.filter((_, j) => j !== i)) }}><FiTrash2 /></IconButton></TableCell>
                  </TableRow>
                  {opens(l.kind) && (
                    <TableRow>
                      <TableCell />
                      <TableCell colSpan={5}><TermsFields line={l} index={i} onChange={(p) => setTerms(i, p)} /></TableCell>
                    </TableRow>
                  )}
                  </Fragment>
                ))}
              </TableBody>
            </Table>
          )}
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            {!big && <Button onClick={() => { setDirty(true); setLines((ls) => [...ls, blank(ls.at(-1)?.kind ?? 'GL')]) }}>Add line</Button>}
            <Box sx={{ flex: 1 }} />
            <Typography>Debits {money(dr)} · Credits {money(cr)}</Typography>
            <Typography color={difference === 0 ? 'success.main' : 'warning.main'} sx={{ fontWeight: 600 }}>{difference === 0 ? 'Balanced' : `Difference ${money(Math.abs(difference))}`}</Typography>
          </Stack>
          {save.error && <Alert severity="error">{save.error.message}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button color="inherit" onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={save.isPending || lines.length === 0 || description.trim().length < 5 || !date} onClick={() => save.mutate()}>Save draft</Button>
      </DialogActions>
    </Dialog>
  )
}

/**
 * A loan or time deposit line: the member's number and the type (there is no account yet: posting opens it). Choosing a type fills in its rate, and for a loan
 * its term in months, where those are still empty.
 */
function OpenedAccount({ line, index, types, onChange }: {
  line: Draft; index: number; types: OpeningType[]; onChange: (patch: Partial<Draft>, terms?: Partial<DraftTerms>) => void
}) {
  const what = line.kind === 'LOAN' ? 'Loan type' : 'Time deposit type'
  const pick = (code: number) => {
    const t = types.find((x) => x.typeCode === code)
    onChange({ typeCode: code }, {
      ...(line.t.rate === '' && t?.rate != null ? { rate: String(t.rate) } : {}),
      ...(line.kind === 'LOAN' && line.t.terms === '' && t?.termMonths != null ? { terms: String(t.termMonths) } : {}),
    })
  }
  const chosen = types.find((t) => t.typeCode === line.typeCode)
  return (
    <Stack direction="row" spacing={1} sx={{ minWidth: 0 }}>
      <TextField size="small" label="Member ID" value={line.client} onChange={(e) => onChange({ client: e.target.value.replace(/\D/g, '') })} sx={{ width: 110 }}
        slotProps={{ htmlInput: { 'aria-label': `Member of line ${index + 1}` } }} />
      <TextField select size="small" label={what} value={chosen ? String(chosen.typeCode) : ''} onChange={(e) => pick(Number(e.target.value))} sx={{ flex: 1, minWidth: 200 }}
        helperText={line.typeCode != null && !chosen ? `Type ${line.typeCode} is not set up or is switched off` : chosen?.paymentMode ? `Paid ${chosen.paymentMode.toLowerCase()}` : undefined}
        slotProps={{ htmlInput: { 'aria-label': `${what} of line ${index + 1}` } }}>
        {types.map((t) => <MenuItem key={t.typeCode} value={String(t.typeCode)}>{t.name ?? `Type ${t.typeCode}`}</MenuItem>)}
      </TextField>
    </Stack>
  )
}

/** The terms of a loan or time deposit line, on a row of their own under it. */
function TermsFields({ line, index, onChange }: { line: Draft; index: number; onChange: (patch: Partial<DraftTerms>) => void }) {
  const t = line.t
  const n = index + 1
  const field = (label: string, key: keyof DraftTerms, width: number, opts: { date?: boolean; decimal?: boolean; tip?: string } = {}) => (
    <TextField size="small" label={label} type={opts.date ? 'date' : 'text'} value={t[key]} sx={{ width }} helperText={opts.tip}
      onChange={(e) => onChange({ [key]: e.target.value } as Partial<DraftTerms>)}
      slotProps={{ inputLabel: { shrink: true }, htmlInput: { 'aria-label': `${label} of line ${n}`, inputMode: opts.date ? undefined : opts.decimal ? 'decimal' : 'numeric' } }} />
  )
  if (line.kind === 'LOAN') {
    return (
      <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
        <TextField select size="small" label="Status" value={String(t.status)} onChange={(e) => onChange({ status: Number(e.target.value) })} sx={{ width: 140 }}
          slotProps={{ htmlInput: { 'aria-label': `Status of line ${n}` } }}>
          {Object.entries(LOAN_STATUSES).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}
        </TextField>
        {field('Months to run', 'terms', 120)}
        {field('Next due', 'start', 160, { date: true, tip: 'After the opening date' })}
        {field('Rate % a year', 'rate', 120, { decimal: true })}
        {field('Amount granted', 'granted', 140, { decimal: true })}
        {field('Granted on', 'grantedOn', 160, { date: true })}
      </Stack>
    )
  }
  return (
    <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
      {field('Placed on', 'start', 160, { date: true })}
      {field('Term in days', 'terms', 120)}
      {field('Rate % a year', 'rate', 120, { decimal: true })}
      {field('Accrued interest', 'accrued', 150, { decimal: true, tip: 'Earned up to the opening date' })}
    </Stack>
  )
}

/** A member line: the member's number, then a choice among that member's accounts of the line's kind (savings or share capital). */
function MemberAccount({ branchCode, line, onChange }: { branchCode: number; line: Draft; onChange: (patch: Partial<Draft>) => void }) {
  const [looked, setLooked] = useState(line.client)
  const id = Number(looked)
  const found = useQuery({ queryKey: ['memo-accounts', branchCode, id], queryFn: () => getMemoAccounts(branchCode, id), enabled: Number.isInteger(id) && id > 0, retry: false })
  const mine = (found.data?.accounts ?? []).filter((a) => a.kind === line.kind && a.status !== 17)
  const value = line.typeCode != null && line.refNo != null ? `${line.typeCode}/${line.refNo}` : ''
  const look = () => { if (line.client.trim() !== looked) { setLooked(line.client.trim()); onChange({ typeCode: null, refNo: null }) } }
  const pick = (v: string) => { const [t, r] = v.split('/'); onChange({ typeCode: Number(t), refNo: Number(r) }) }
  return (
    <Stack direction="row" spacing={1} sx={{ minWidth: 0 }}>
      <TextField size="small" label="Member ID" value={line.client} onChange={(e) => onChange({ client: e.target.value.replace(/\D/g, '') })} onBlur={look}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); look() } }} sx={{ width: 110 }} />
      <TextField select size="small" label={found.data ? found.data.clientName : 'Account'} value={mine.some((a) => `${a.typeCode}/${a.refNo}` === value) ? value : ''}
        onChange={(e) => pick(e.target.value)} sx={{ flex: 1, minWidth: 200 }} error={found.isError}
        helperText={found.isError ? 'No such member' : found.data && mine.length === 0 ? `No ${KIND_WORDS[line.kind].toLowerCase()} account` : line.typeCode != null && line.refNo == null ? `Type ${line.typeCode} from the file` : undefined}
        disabled={!found.data || mine.length === 0}>
        {mine.map((a) => <MenuItem key={`${a.typeCode}/${a.refNo}`} value={`${a.typeCode}/${a.refNo}`}>{a.type ?? 'Account'} {a.refText ?? a.refNo}</MenuItem>)}
      </TextField>
    </Stack>
  )
}

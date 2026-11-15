import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import {
  cancelMemo, createMemo, getMemo, getMemoAccounts, listMemos, memoStep, reverseMemo, type MemoKind, type MemoLineIn, type MemoStatus, type MemoSummary,
} from './api'
import { glass } from './glass'
import MoneyField from './MoneyField'
import { useMe } from './session'
import { fmtDate } from './profile/format'
import { Gated } from './ui/Gated'
import { money } from './ui/format'
import { ConfirmButton } from './ui/dialogs'
import { labelOf } from './ui/labels'

const today = () => new Date().toLocaleDateString('en-CA')
const statusColor: Record<MemoStatus, 'default' | 'info' | 'warning' | 'success' | 'error'> = { DRAFT: 'default', FOR_REVIEW: 'info', APPROVED: 'warning', POSTED: 'success', CANCELLED: 'error' }
const statusLabel = (s: MemoStatus) => labelOf(s)
const KINDS: [MemoKind, string][] = [['SAVINGS', 'Member savings'], ['SHARE', 'Member share capital'], ['LEDGER', 'Ledger account']]

/**
 * Debit/credit memos: one balanced entry between members' savings or share capital accounts and ledger accounts, for adjustments and transfers (for example from a deceased
 * member to a beneficiary). A debit takes money out of a member's account, a credit puts it in. The maker, the approver and the poster must be three different people.
 */
export default function MemosTab({ branchCode }: { branchCode: number }) {
  const qc = useQueryClient()
  const me = useMe()
  const [status, setStatus] = useState<MemoStatus | ''>('')
  const [openId, setOpenId] = useState<number | null>(null)
  const [creating, setCreating] = useState(false)
  const list = useQuery({ queryKey: ['memos', branchCode, status], queryFn: () => listMemos(branchCode, status || undefined) })
  const refresh = () => qc.invalidateQueries({ queryKey: ['memos'] })
  return (
    <Box>
      <Stack direction="row" spacing={1.5} sx={{ mb: 1.5, alignItems: 'center' }}>
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value as MemoStatus | '')} sx={{ width: 170 }}>
          <MenuItem value="">All</MenuItem>
          {(['DRAFT', 'FOR_REVIEW', 'APPROVED', 'POSTED', 'CANCELLED'] as MemoStatus[]).map((s) => <MenuItem key={s} value={s}>{statusLabel(s)}</MenuItem>)}
        </TextField>
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" startIcon={<FiPlus />} onClick={() => setCreating(true)}>New memo</Button>
      </Stack>
      {list.error && <Alert severity="error" sx={{ mb: 2 }}>{(list.error as Error).message}</Alert>}
      {list.data && list.data.length === 0 && <Alert severity="info">No memos{status ? ` that are ${statusLabel(status).toLowerCase()}` : ' yet'}.</Alert>}
      {list.data && list.data.length > 0 && (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Memos">
            <TableHead>
              <TableRow><TableCell>Reference</TableCell><TableCell>Date</TableCell><TableCell>Reason</TableCell><TableCell align="right">Amount</TableCell><TableCell>Status</TableCell><TableCell>Made by</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {list.data.map((m: MemoSummary) => (
                <TableRow key={m.id} hover onClick={() => setOpenId(m.id)} sx={{ cursor: 'pointer' }}>
                  <TableCell>{m.reference}{m.reverses ? ' (reversal)' : ''}</TableCell>
                  <TableCell>{fmtDate(m.memoDate)}</TableCell>
                  <TableCell>{m.description}</TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(m.total)}</TableCell>
                  <TableCell><Chip size="small" label={statusLabel(m.status)} color={statusColor[m.status]} /></TableCell>
                  <TableCell>{m.createdBy}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
      {creating && <NewMemo branchCode={branchCode} onClose={() => setCreating(false)} onDone={() => { setCreating(false); void refresh() }} />}
      {openId !== null && <MemoView id={openId} user={me.username} admin={me.admin} onClose={() => setOpenId(null)} onChanged={() => void refresh()} />}
    </Box>
  )
}

// ---- making a memo -------------------------------------------------------------------------------------------------

interface DraftLine { kind: MemoKind; client: string; account: string; ledger: string; debit: string; credit: string }
const blankLine = (): DraftLine => ({ kind: 'SAVINGS', client: '', account: '', ledger: '', debit: '', credit: '' })

/** A member line: the member's number, then a choice among that member's accounts of the line's kind. */
function MemberAccount({ branchCode, line, onChange }: { branchCode: number; line: DraftLine; onChange: (patch: Partial<DraftLine>) => void }) {
  const [looked, setLooked] = useState('')
  const id = Number(looked)
  const found = useQuery({ queryKey: ['memo-accounts', branchCode, id], queryFn: () => getMemoAccounts(branchCode, id), enabled: Number.isInteger(id) && id > 0, retry: false })
  const mine = (found.data?.accounts ?? []).filter((a) => a.kind === line.kind && a.status !== 17)
  const look = () => { if (line.client.trim() !== looked) { setLooked(line.client.trim()); onChange({ account: '' }) } }
  return (
    <Stack direction="row" spacing={1} sx={{ flex: 2, minWidth: 0 }}>
      <TextField size="small" label="Member no." value={line.client} onChange={(e) => onChange({ client: e.target.value.replace(/\D/g, '') })} onBlur={look} onKeyDown={(e) => { if (e.key === 'Enter') look() }} sx={{ width: 120 }} />
      <TextField
        select size="small" label={found.data ? `${found.data.clientName}` : 'Account'} value={line.account} onChange={(e) => onChange({ account: e.target.value })} sx={{ flex: 1, minWidth: 0 }}
        error={found.isError} helperText={found.isError ? 'No such member' : found.data && mine.length === 0 ? 'No account of this kind' : undefined} disabled={!found.data || mine.length === 0}
      >
        {mine.map((a) => <MenuItem key={`${a.typeCode}/${a.refNo}`} value={`${a.typeCode}/${a.refNo}`}>{a.type ?? 'Account'} {a.refText ?? a.refNo} · {money(a.balance)}</MenuItem>)}
      </TextField>
    </Stack>
  )
}

function NewMemo({ branchCode, onClose, onDone }: { branchCode: number; onClose: () => void; onDone: () => void }) {
  const [date, setDate] = useState(today())
  const [description, setDescription] = useState('')
  const [lines, setLines] = useState<DraftLine[]>([blankLine(), blankLine()])
  const set = (i: number, patch: Partial<DraftLine>) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, ...patch } : l)))
  const dr = lines.reduce((s, l) => s + (Number(l.debit) || 0), 0)
  const cr = lines.reduce((s, l) => s + (Number(l.credit) || 0), 0)
  const balanced = Math.abs(dr - cr) < 0.005 && dr > 0
  const toLine = (l: DraftLine): MemoLineIn => {
    const [type, ref] = l.account.split('/')
    return l.kind === 'LEDGER'
      ? { kind: 'LEDGER', clientId: null, typeCode: null, refNo: null, accountCode: l.ledger.trim(), debit: Number(l.debit) || 0, credit: Number(l.credit) || 0, note: null }
      : { kind: l.kind, clientId: Number(l.client) || null, typeCode: type ? Number(type) : null, refNo: ref ? Number(ref) : null, accountCode: null, debit: Number(l.debit) || 0, credit: Number(l.credit) || 0, note: null }
  }
  const m = useMutation({ meta: { done: 'Memo saved as a draft.' }, mutationFn: () => createMemo({ branchCode, memoDate: date, description: description.trim(), lines: lines.map(toLine) }), onSuccess: onDone })
  const ready = description.trim().length >= 5 && balanced && lines.every((l) => (l.kind === 'LEDGER' ? l.ledger.trim() : l.account) && (Number(l.debit) > 0) !== (Number(l.credit) > 0))
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>New debit/credit memo</DialogTitle>
      <DialogContent>
        <Stack direction="row" spacing={2} sx={{ my: 1 }}>
          <TextField size="small" type="date" label="Date" value={date} onChange={(e) => setDate(e.target.value)} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today() } }} />
          <TextField size="small" label="Reason and who authorised it" value={description} onChange={(e) => setDescription(e.target.value)} slotProps={{ htmlInput: { maxLength: 200 } }} sx={{ flex: 1 }} />
        </Stack>
        <Typography sx={{ fontSize: 12, mb: 1 }} color="text.secondary">A debit takes money out of a member&rsquo;s account; a credit puts money in. Debits and credits must be equal.</Typography>
        {lines.map((l, i) => (
          <Stack key={i} direction="row" spacing={1} sx={{ mb: 1, alignItems: 'flex-start' }}>
            <TextField select size="small" label="Applies to" value={l.kind} onChange={(e) => set(i, { kind: e.target.value as MemoKind, account: '' })} sx={{ width: 190 }}>
              {KINDS.map(([k, label]) => <MenuItem key={k} value={k}>{label}</MenuItem>)}
            </TextField>
            {l.kind === 'LEDGER'
              ? <TextField size="small" label="Ledger account" value={l.ledger} onChange={(e) => set(i, { ledger: e.target.value })} sx={{ flex: 2 }} />
              : <MemberAccount key={l.kind} branchCode={branchCode} line={l} onChange={(patch) => set(i, patch)} />}
            <MoneyField size="small" label="Debit" value={l.debit} onValueChange={(v) => set(i, { debit: v })} sx={{ flex: 1, minWidth: 110 }} />
            <MoneyField size="small" label="Credit" value={l.credit} onValueChange={(v) => set(i, { credit: v })} sx={{ flex: 1, minWidth: 110 }} />
            <IconButton aria-label="Remove line" disabled={lines.length <= 2} onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))}><FiTrash2 /></IconButton>
          </Stack>
        ))}
        <Button size="small" onClick={() => setLines((ls) => [...ls, blankLine()])}>Add line</Button>
        <Alert severity={balanced ? 'success' : 'warning'} sx={{ mt: 2 }}>Debits {money(dr)} / credits {money(cr)}{balanced ? '' : '. They must be equal.'}</Alert>
        {m.error && <Alert severity="error" sx={{ mt: 1 }}>{(m.error as Error).message}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={m.isPending || !ready} onClick={() => m.mutate()}>Save draft</Button>
      </DialogActions>
    </Dialog>
  )
}

// ---- one memo ------------------------------------------------------------------------------------------------------

function MemoView({ id, user, admin, onClose, onChanged }: { id: number; user: string; admin: boolean; onClose: () => void; onChanged: () => void }) {
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['memo', id], queryFn: () => getMemo(id) })
  const [asking, setAsking] = useState<'cancel' | 'reverse' | null>(null)
  const [reason, setReason] = useState('')
  const done = () => { void qc.invalidateQueries({ queryKey: ['memo', id] }); onChanged() }
  const step = useMutation({ meta: { done: (_, s) => ({ submit: 'Memo sent for review.', approve: 'Memo approved. A third person can now post it.', post: 'Memo posted.' } as Record<string, string>)[s as string] ?? null }, mutationFn: (s: 'submit' | 'approve' | 'post') => memoStep(id, s), onSuccess: done })
  const ask = useMutation({
    mutationFn: () => (asking === 'cancel' ? cancelMemo(id, reason.trim()) : reverseMemo(id, reason.trim())),
    onSuccess: () => { setAsking(null); setReason(''); done() },
  })
  const s = data?.summary
  const me = user.toLowerCase()
  const maker = s?.createdBy.toLowerCase() === me
  const approver = s?.approvedBy?.toLowerCase() === me
  const error = (step.error ?? ask.error) as Error | null
  const debit = data?.lines.reduce((t, l) => t + l.debit, 0) ?? 0
  const credit = data?.lines.reduce((t, l) => t + l.credit, 0) ?? 0
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>
        {s?.reference ?? 'Memo'} {s && <Chip size="small" sx={{ ml: 1 }} label={statusLabel(s.status)} color={statusColor[s.status]} />}
        {s?.documentNo && <Typography component="span" color="text.secondary" sx={{ ml: 1.5, fontSize: 13 }}>Document {s.documentNo}</Typography>}
      </DialogTitle>
      <DialogContent>
        {s && (
          <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">
            {fmtDate(s.memoDate)} · {s.description} · made by {s.createdBy}{s.approvedBy ? `, approved by ${s.approvedBy}` : ''}{s.postedBy ? `, posted by ${s.postedBy}` : ''}
          </Typography>
        )}
        <Table size="small" aria-label="Memo lines">
          <TableHead><TableRow><TableCell>Account</TableCell><TableCell>Member / title</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
          <TableBody>
            {data?.lines.map((l) => (
              <TableRow key={l.lineNo}>
                <TableCell>{l.kind === 'LEDGER' ? l.accountCode : `${l.type ?? (l.kind === 'SHARE' ? 'Share capital' : 'Savings')} ${l.refText ?? l.refNo ?? ''}`}</TableCell>
                <TableCell>{l.kind === 'LEDGER' ? l.accountTitle : `${l.clientId} · ${l.clientName ?? ''}`}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.debit ? money(l.debit) : ''}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.credit ? money(l.credit) : ''}</TableCell>
              </TableRow>
            ))}
            {data && <TableRow><TableCell colSpan={2} sx={{ fontWeight: 700 }}>Total</TableCell><TableCell align="right" sx={{ fontWeight: 700 }}>{money(debit)}</TableCell><TableCell align="right" sx={{ fontWeight: 700 }}>{money(credit)}</TableCell></TableRow>}
          </TableBody>
        </Table>
        {data?.cancelReason && <Alert severity="info" sx={{ mt: 2 }}>Cancelled: {data.cancelReason}</Alert>}
        {s?.status === 'FOR_REVIEW' && maker && <Alert severity="info" sx={{ mt: 2 }}>Someone other than the person who made this memo has to approve it.</Alert>}
        {s?.status === 'APPROVED' && (maker || approver) && <Alert severity="info" sx={{ mt: 2 }}>A third person, who neither made nor approved it, has to post it.</Alert>}
        {asking && <TextField fullWidth autoFocus size="small" label={asking === 'cancel' ? 'Reason for cancelling' : 'Reason for the reversal'} value={reason} onChange={(e) => setReason(e.target.value)} sx={{ mt: 2 }} />}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error.message}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {asking && <Button onClick={() => { setAsking(null); setReason('') }}>Back</Button>}
        {s && ['DRAFT', 'FOR_REVIEW', 'APPROVED'].includes(s.status) && !asking && (maker || admin) && <Button color="warning" onClick={() => setAsking('cancel')}>Cancel memo</Button>}
        {s?.status === 'DRAFT' && (maker || admin) && <Button variant="contained" disabled={step.isPending} onClick={() => step.mutate('submit')}>Send for review</Button>}
        {s?.status === 'FOR_REVIEW' && (
          <Gated allowed={!maker} reason="You made this memo. Someone else has to approve it.">
            <Button variant="contained" disabled={step.isPending} onClick={() => step.mutate('approve')}>Approve</Button>
          </Gated>
        )}
        {s?.status === 'APPROVED' && (
          <Gated allowed={!maker && !approver} reason="You made or approved this memo. A third person has to post it.">
            <ConfirmButton variant="contained" disabled={step.isPending} onConfirm={() => step.mutate('post')} title={`Post memo ${s.reference}?`}
              message={<>It moves <b>{money(s.total)}</b> between members’ accounts and ledger accounts, dated {s.memoDate}. A posted memo cannot be edited; it is corrected with a reversing memo.</>}>Post</ConfirmButton>
          </Gated>
        )}
        {s?.status === 'POSTED' && !asking && <Button color="warning" onClick={() => setAsking('reverse')}>Reverse…</Button>}
        {asking && <Button variant="contained" color="warning" disabled={ask.isPending || reason.trim().length < 3} onClick={() => ask.mutate()}>{asking === 'cancel' ? 'Cancel the memo' : 'Create reversal draft'}</Button>}
      </DialogActions>
    </Dialog>
  )
}

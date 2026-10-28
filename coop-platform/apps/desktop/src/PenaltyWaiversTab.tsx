import { Alert, Box, Button, MenuItem, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  askPenaltyWaiver, checkPenaltyWaiver, decidePenaltyWaiver, getLoans, getPenaltyWaiverReport, getPenaltyWaiversPending, getWaiverExpenseAccounts, postPenaltyWaiver,
  type PenaltyWaiverReq,
} from './api'
import { glass } from './glass'
import MoneyField from './MoneyField'
import { money } from './ui/format'

const today = () => new Date().toLocaleDateString('en-CA')
const daysAgo = (n: number) => new Date(Date.now() - n * 86_400_000).toLocaleDateString('en-CA')

/**
 * Penalty waivers. The report lists payments where less penalty was charged than the program suggested (read from the audit log). The journal is
 * the accountant's entry for a penalty that was waived: the expense account is debited and the loan credited as event 42. It does not change what
 * the member owes. A different accountant or administrator has to approve it before it posts.
 */
export default function PenaltyWaiversTab() {
  const qc = useQueryClient()
  const [from, setFrom] = useState(daysAgo(90))
  const [to, setTo] = useState(today())
  const report = useQuery({ queryKey: ['penalty-waiver-report', from, to], queryFn: () => getPenaltyWaiverReport(from, to), retry: false })
  const accounts = useQuery({ queryKey: ['waiver-expense-accounts'], queryFn: getWaiverExpenseAccounts, retry: false })
  const pending = useQuery({ queryKey: ['penalty-waiver-pending'], queryFn: getPenaltyWaiversPending, refetchInterval: 15_000, retry: false })

  const [clientText, setClientText] = useState('')
  const [loanKey, setLoanKey] = useState('')
  const [amount, setAmount] = useState('')
  const [account, setAccount] = useState('')
  const [reason, setReason] = useState('')
  const [note, setNote] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const clientId = Number(clientText) || 0
  const loans = useQuery({ queryKey: ['waiver-loans', clientId], queryFn: () => getLoans(1, clientId, false), enabled: clientId > 0, retry: false })
  const loan = (loans.data?.loans ?? []).find((l) => `${l.typeCode}/${l.refNo}` === loanKey)
  const req: PenaltyWaiverReq | null = loan && Number(amount) > 0 && account ? { clientId, typeCode: loan.typeCode, refNo: loan.refNo, amount: Number(amount), expenseAccount: account, reason } : null
  const state = useQuery({ queryKey: ['penalty-waiver-check', req], queryFn: () => checkPenaltyWaiver(req!), enabled: req !== null && reason.trim().length >= 5, refetchInterval: 8_000, retry: false })
  const s = state.data

  const ask = useMutation({
    mutationFn: () => askPenaltyWaiver(req!),
    onSuccess: () => { setError(null); void qc.invalidateQueries({ queryKey: ['penalty-waiver-check'] }) },
    onError: (e) => setError((e as Error).message),
  })
  const post = useMutation({
    mutationFn: () => postPenaltyWaiver(req!),
    onSuccess: (p) => {
      setError(null); setNote(`Posted. Journal ${p.documentNo}: ${money(p.amount)} penalty waived.`); setAmount(''); setReason(''); setLoanKey('')
      void qc.invalidateQueries({ queryKey: ['penalty-waiver-check'] })
    },
    onError: (e) => setError((e as Error).message),
  })
  const decide = useMutation({
    mutationFn: (v: { id: number; approve: boolean }) => decidePenaltyWaiver(v.id, v.approve, v.approve ? 'approved' : 'rejected from the waiver list'),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: ['penalty-waiver-pending'] }) },
    onError: (e) => setError((e as Error).message),
  })

  const rows = report.data?.rows ?? []
  return (
    <Box sx={{ display: 'grid', gap: 2 }}>
      {note && <Alert severity="success" onClose={() => setNote(null)}>{note}</Alert>}
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

      {(pending.data ?? []).length > 0 && (
        <Box sx={(t) => ({ p: 1.5, ...glass(t) })}>
          <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Waivers waiting for your approval</Typography>
          {(pending.data ?? []).map((p) => (
            <Box key={p.id} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.5 }}>
              <Typography sx={{ fontSize: 13.5, flex: 1 }}>{p.requested_by}: {p.description} · <b>{money(p.amount)}</b></Typography>
              <Button size="small" variant="contained" disabled={decide.isPending} onClick={() => decide.mutate({ id: p.id, approve: true })}>Approve</Button>
              <Button size="small" disabled={decide.isPending} onClick={() => decide.mutate({ id: p.id, approve: false })}>Reject</Button>
            </Box>
          ))}
        </Box>
      )}

      <Box sx={(t) => ({ p: 1.5, ...glass(t) })}>
        <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Penalties waived at the window</Typography>
        <Box sx={{ display: 'flex', gap: 1, mb: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <TextField size="small" type="date" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="date" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          {report.data && <Typography sx={{ fontSize: 13.5 }}>{rows.length} payment{rows.length === 1 ? '' : 's'} · waived <b>{money(report.data.total)}</b></Typography>}
        </Box>
        {report.isError && <Alert severity="error">{(report.error as Error).message}</Alert>}
        {report.isSuccess && rows.length === 0 && <Typography sx={{ fontSize: 13.5 }} color="text.secondary">No payment in these dates charged less than the suggested penalty.</Typography>}
        {rows.length > 0 && (
          <Box sx={{ maxHeight: 300, overflow: 'auto' }}>
            <Table size="small" stickyHeader aria-label="Penalties waived">
              <TableHead><TableRow>
                <TableCell>When</TableCell><TableCell>Member</TableCell><TableCell>Loan</TableCell><TableCell align="right">Suggested</TableCell><TableCell align="right">Charged</TableCell>
                <TableCell align="right">Waived</TableCell><TableCell>Cashier</TableCell><TableCell>Allowed by</TableCell>
              </TableRow></TableHead>
              <TableBody>
                {rows.map((w) => (
                  <TableRow key={w.receipt} hover>
                    <TableCell>{w.at}</TableCell><TableCell>{w.client}</TableCell><TableCell>{w.typeCode}/{w.refNo}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(w.suggested)}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(w.charged)}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: 700 }}>{money(w.waived)}</TableCell>
                    <TableCell>{w.cashier}</TableCell><TableCell>{w.allowedBy ?? ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </Box>

      <Box sx={(t) => ({ p: 1.5, ...glass(t) })}>
        <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Record a waiver in the books</Typography>
        <Typography sx={{ fontSize: 12.5, mb: 1 }} color="text.secondary">
          This is the accountant&apos;s journal for a penalty that was waived. It does not change what the member owes; a different accountant or administrator has to approve it.
        </Typography>
        <Box sx={{ display: 'grid', gap: 1, maxWidth: 560 }}>
          <TextField size="small" label="Client ID" value={clientText} onChange={(e) => { setClientText(e.target.value.replace(/[^0-9]/g, '')); setLoanKey('') }} />
          <TextField select size="small" label="Loan" value={loanKey} onChange={(e) => setLoanKey(e.target.value)} disabled={!loans.data}
            helperText={clientId > 0 && loans.isError ? (loans.error as Error).message : loans.data && loans.data.loans.length === 0 ? 'This client has no open loans.' : undefined}>
            {(loans.data?.loans ?? []).map((l) => <MenuItem key={`${l.typeCode}/${l.refNo}`} value={`${l.typeCode}/${l.refNo}`}>{l.type ?? l.typeCode} · {l.refNo} · outstanding {money(l.outstanding)}</MenuItem>)}
          </TextField>
          <MoneyField size="small" label="Penalty waived" value={amount} onValueChange={(v) => setAmount(v)}
            helperText={s?.suggestedPenalty != null ? `The program would suggest ${money(s.suggestedPenalty)} today.` : undefined} />
          <TextField select size="small" label="Expense account to debit" value={account} onChange={(e) => setAccount(e.target.value)}>
            {(accounts.data ?? []).map((a) => <MenuItem key={a.code} value={a.code}>{a.code} · {a.name}</MenuItem>)}
          </TextField>
          <TextField size="small" label="Reason (for the journal)" value={reason} onChange={(e) => setReason(e.target.value)} />
          {s?.problems.map((p) => <Alert key={p} severity="warning">{p}</Alert>)}
          {s?.approval === 'PENDING' && <Alert severity="info">Waiting for another accountant or administrator to approve.</Alert>}
          {s?.approval === 'REJECTED' && <Alert severity="error">This was rejected. You can ask again.</Alert>}
          <Box>
            {s?.approval === 'APPROVED'
              ? <Button variant="contained" disabled={!s.ready || post.isPending} onClick={() => post.mutate()}>{post.isPending ? 'Posting…' : 'Post the waiver'}</Button>
              : <Button variant="contained" disabled={!s || s.problems.length > 0 || s.approval === 'PENDING' || ask.isPending} onClick={() => ask.mutate()}>Ask for approval</Button>}
          </Box>
        </Box>
      </Box>
    </Box>
  )
}

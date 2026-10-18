import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Skeleton, MenuItem, Switch, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import LoanApplicationDialog from './LoanApplicationDialog'
import LoanProfileDialog from './LoanProfileDialog'
import { checkLoanPayment, getLoanDetail, getLoans, postLoanPayment, type LoanItem, type LoanPaymentBreakdown, type LoanPaymentReceipt, type LoanScheduleRow } from './api'

const money = (n: number | null | undefined) => (n == null ? '—' : n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
const num = { fontVariantNumeric: 'tabular-nums' } as const

const stateLabel: Record<LoanScheduleRow['state'], string> = { PAID: 'Paid', PARTIAL: 'Part paid', PAST_DUE: 'Past due', UPCOMING: 'Upcoming' }
const stateColor: Record<LoanScheduleRow['state'], 'success' | 'warning' | 'error' | 'default'> = { PAID: 'success', PARTIAL: 'warning', PAST_DUE: 'error', UPCOMING: 'default' }

/** A member's loans: what is owed, what is late, and the schedule and ledger behind each. Read-only. */
export default function LoansTab({ branchCode, clientId }: { branchCode: number; clientId: number }) {
  const [paid, setPaid] = useState(false)
  const [open, setOpen] = useState<LoanItem | null>(null)
  const [paying, setPaying] = useState<LoanItem | null>(null)
  const [profile, setProfile] = useState<LoanItem | null>(null)
  const [applyType, setApplyType] = useState<number | null>(null)
  const { data, error, isLoading } = useQuery({ queryKey: ['member-loans', branchCode, clientId, paid], queryFn: () => getLoans(branchCode, clientId, paid), staleTime: 60_000 })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={160} />
  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
        <Box sx={{ flex: 1, fontSize: 13 }}>
          {data && <>Outstanding principal <strong>{money(data.totalOutstanding)}</strong>{data.totalPastDue > 0 && <> · past due <strong style={{ color: 'var(--mui-palette-error-main, #d32f2f)' }}>{money(data.totalPastDue)}</strong></>}</>}
        </Box>
        <TextField size="small" select label="New application" value="" onChange={(e) => setApplyType(Number(e.target.value))} sx={{ minWidth: 190, mr: 1 }}>
          <MenuItem value="48">Back to back loan</MenuItem>
          <MenuItem value="49">Co-maker loan</MenuItem>
        </TextField>
        <FormControlLabel control={<Switch size="small" checked={paid} onChange={(e) => setPaid(e.target.checked)} />} label="Show paid loans" />
      </Box>
      {data && data.loans.length === 0 && <Alert severity="info">{paid ? 'This client has no loans.' : 'This client has no loans with a balance.'}</Alert>}
      {data && data.loans.length > 0 && (
        <Table size="small" aria-label="Loans">
          <TableHead>
            <TableRow>
              <TableCell>Loan</TableCell><TableCell>Status</TableCell><TableCell>Released</TableCell><TableCell align="right">Amount</TableCell><TableCell>Terms</TableCell><TableCell align="right">Rate %</TableCell>
              <TableCell align="right">Outstanding</TableCell><TableCell align="right">Past due</TableCell><TableCell>Next due</TableCell><TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {data.loans.map((l) => (
              <TableRow key={`${l.typeCode}-${l.refNo}`} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(l)}>
                <TableCell>{l.type ?? `Type ${l.typeCode}`} #{l.refNo}{l.restructured && <Chip size="small" label="Restructured" sx={{ ml: 1 }} />}</TableCell>
                <TableCell><Chip size="small" label={l.statusName} color={l.status === 11 ? 'success' : l.status === 12 || l.status === 13 ? 'error' : 'default'} variant="outlined" /></TableCell>
                <TableCell>{l.date ?? '—'}</TableCell>
                <TableCell align="right" sx={num}>{money(l.amount)}</TableCell>
                <TableCell>{l.terms} {l.termUnit?.toLowerCase() ?? ''}{l.paymentMode ? ` · ${l.paymentMode.toLowerCase()}` : ''}</TableCell>
                <TableCell align="right" sx={num}>{l.interestRate == null ? '—' : l.interestRate.toFixed(2)}</TableCell>
                <TableCell align="right" sx={num}>{money(l.outstanding)}</TableCell>
                <TableCell align="right" sx={{ color: l.pastDuePrincipal > 0 ? 'error.main' : undefined, ...num }}>{l.pastDuePrincipal > 0 ? `${money(l.pastDuePrincipal)} (${l.daysPastDue} d)` : '—'}</TableCell>
                <TableCell>{l.nextDueDate ? `${l.nextDueDate} · ${money((l.nextDuePrincipal ?? 0) + (l.nextDueInterest ?? 0))}` : '—'}</TableCell>
                <TableCell align="right">
                  <Button size="small" color="inherit" onClick={(e) => { e.stopPropagation(); setProfile(l) }}>Profile</Button>
                  {l.outstanding > 0 && l.status !== 13 && <Button size="small" onClick={(e) => { e.stopPropagation(); setPaying(l) }}>Pay</Button>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {open && <LoanDialog branchCode={branchCode} clientId={clientId} loan={open} onClose={() => setOpen(null)} />}
      {paying && <LoanPaymentDialog clientId={clientId} loan={paying} onClose={() => setPaying(null)} />}
      {applyType != null && <LoanApplicationDialog branchCode={branchCode} clientId={clientId} typeCode={applyType} onClose={() => setApplyType(null)} />}
      {profile && <LoanProfileDialog branchCode={branchCode} clientId={clientId} typeCode={profile.typeCode} refNo={profile.refNo} onClose={() => setProfile(null)} />}
    </>
  )
}

function LoanPaymentDialog({ clientId, loan, onClose }: { clientId: number; loan: LoanItem; onClose: () => void }) {
  const qc = useQueryClient()
  const [amount, setAmount] = useState('')
  const [receiptNo, setReceiptNo] = useState('')
  const [explanation, setExplanation] = useState('')
  const [check, setCheck] = useState<LoanPaymentBreakdown | null>(null)
  const [receipt, setReceipt] = useState<LoanPaymentReceipt | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [askConfirm, setAskConfirm] = useState(false)
  const value = Number(amount)
  const valid = amount.trim() !== '' && Number.isFinite(value) && value > 0
  const body = { clientId, typeCode: loan.typeCode, refNo: loan.refNo, amount: value, explanation, receiptNo }

  // the server works the split out (interest first, on the days since interest was last paid); look again as the amount changes
  useEffect(() => {
    if (!valid || receipt) { setCheck(null); return }
    let live = true
    const t = setTimeout(() => { checkLoanPayment(body).then((c) => { if (live) setCheck(c) }).catch((e: Error) => { if (live) setError(e.message) }) }, 400)
    return () => { live = false; clearTimeout(t) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [valid, value, receipt])

  const submit = async (confirmDuplicate: boolean) => {
    setBusy(true); setError(null)
    try {
      setReceipt(await postLoanPayment({ ...body, confirmDuplicate }))
      qc.invalidateQueries({ queryKey: ['member-loans'] })
      qc.invalidateQueries({ queryKey: ['member-loan'] })
    } catch (e) {
      const msg = (e as Error).message
      setError(msg)
      setAskConfirm(msg.includes('confirm it'))
    } finally { setBusy(false) }
  }

  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{receipt ? 'Payment posted' : 'Take a loan payment'}</DialogTitle>
      <DialogContent>
        {!receipt && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <Box sx={{ fontSize: 13 }}>{loan.type ?? `Type ${loan.typeCode}`} #{loan.refNo} · owes {money(loan.outstanding)} · {loan.interestRate?.toFixed(2)}% a year</Box>
            <TextField label="Amount" type="number" autoFocus value={amount} onChange={(e) => { setAmount(e.target.value); setAskConfirm(false); setError(null) }} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
            {check && (
              <Table size="small" aria-label="How the payment is split">
                <TableBody>
                  <TableRow><TableCell>Interest due ({check.days} days since {check.interestFrom})</TableCell><TableCell align="right" sx={num}>{money(check.interestDue)}</TableCell></TableRow>
                  <TableRow><TableCell>Goes to interest</TableCell><TableCell align="right" sx={num}>{money(check.interest)}</TableCell></TableRow>
                  <TableRow><TableCell>Goes to principal</TableCell><TableCell align="right" sx={num}>{money(check.principal)}</TableCell></TableRow>
                  <TableRow><TableCell sx={{ fontWeight: 700 }}>Principal left</TableCell><TableCell align="right" sx={{ fontWeight: 700, ...num }}>{money(check.balanceAfter)}</TableCell></TableRow>
                  <TableRow><TableCell>To pay it off today</TableCell><TableCell align="right" sx={num}>{money(check.payoff)}</TableCell></TableRow>
                </TableBody>
              </Table>
            )}
            {check && check.problems.map((p) => <Alert key={p} severity="error">{p}</Alert>)}
            <TextField label="Receipt number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} helperText="Up to 8 letters or digits. Left empty, the system numbers it." />
            <TextField label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder="LOAN PAYMENT" />
            <Box sx={{ fontSize: 12 }}>Cash goes into your own drawer and is posted to the ledger at once.</Box>
          </Box>
        )}
        {receipt && (
          <Box sx={{ display: 'grid', gap: 0.5, pt: 1, fontSize: 14 }}>
            <Box>Receipt <strong>{receipt.documentNo}</strong> (control {receipt.controlNo}) dated {receipt.date}</Box>
            <Box>{receipt.clientName}</Box>
            <Box>{receipt.loan}</Box>
            <Box>Received <strong>{money(receipt.amount)}</strong>: interest {money(receipt.interest)}, principal {money(receipt.principal)}</Box>
            <Box>Principal left <strong>{money(receipt.balanceAfter)}</strong></Box>
            <Box>By {receipt.cashier}</Box>
          </Box>
        )}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        {receipt ? <Button onClick={onClose}>Close</Button> : (
          <>
            <Button onClick={onClose} disabled={busy}>Cancel</Button>
            {askConfirm
              ? <Button variant="contained" color="warning" disabled={busy} onClick={() => submit(true)}>Post anyway</Button>
              : <Button variant="contained" disabled={busy || !valid || !check || !check.ready} onClick={() => submit(false)}>Post payment</Button>}
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}

function LoanDialog({ branchCode, clientId, loan, onClose }: { branchCode: number; clientId: number; loan: LoanItem; onClose: () => void }) {
  const [tab, setTab] = useState(0)
  const { data, error } = useQuery({ queryKey: ['member-loan', branchCode, clientId, loan.typeCode, loan.refNo], queryFn: () => getLoanDetail(branchCode, clientId, loan.typeCode, loan.refNo) })
  const l = data?.loan ?? loan
  const facts: [string, string][] = [
    ['Released', l.date ?? '—'], ['First due', l.firstDue ?? '—'], ['Matures', l.maturity ?? '—'], ['Interest', `${l.interestRate == null ? '—' : l.interestRate.toFixed(2)}% · ${l.amortization ?? '—'}`],
    ['Purpose', l.purpose ?? '—'], ['Collateral', l.collateral ? `${l.collateral}${l.collateralAmount ? ` · ${money(l.collateralAmount)}` : ''}` : '—'],
    ['Principal paid', money(l.principalPaid)], ['Interest paid', money(l.interestPaid)], ['Last payment', l.lastPayment ?? '—'], ['Renewals', String(l.renewals)],
  ]
  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle>{l.type ?? `Type ${l.typeCode}`} #{l.refNo} · {l.statusName} · {money(l.amount)} · owes {money(l.outstanding)}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: '4px 16px', mb: 1.5, fontSize: 13 }}>
          {facts.map(([k, v]) => <Box key={k}><Typography component="span" sx={{ fontSize: 12 }} color="text.secondary">{k}: </Typography>{v}</Box>)}
        </Box>
        {l.pastDuePrincipal > 0 && <Alert severity="warning" sx={{ mb: 1 }}>Principal past due {money(l.pastDuePrincipal)}; the oldest unpaid instalment is {l.daysPastDue} days late.</Alert>}
        {data && data.coMakers.length > 0 && <Box sx={{ fontSize: 13, mb: 1 }}>Co-makers: {data.coMakers.map((c) => c.name ?? `client ${c.clientId}`).join('; ')}</Box>}
        {data?.remarks && <Box sx={{ fontSize: 13, mb: 1 }}>Remarks: {data.remarks}</Box>}
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 1 }}><Tab label="Schedule" /><Tab label="Ledger" /></Tabs>
        {data && tab === 0 && (
          <Table size="small" aria-label="Repayment schedule">
            <TableHead><TableRow><TableCell>Due</TableCell><TableCell align="right">Principal</TableCell><TableCell align="right">Interest</TableCell><TableCell align="right">Balance after</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
            <TableBody>
              {data.schedule.map((r) => (
                <TableRow key={r.dueDate}>
                  <TableCell>{r.dueDate}</TableCell>
                  <TableCell align="right" sx={num}>{money(r.principalDue)}</TableCell><TableCell align="right" sx={num}>{money(r.interestDue)}</TableCell><TableCell align="right" sx={num}>{money(r.principalBalance)}</TableCell>
                  <TableCell>
                    <Chip size="small" label={stateLabel[r.state]} color={stateColor[r.state]} variant={r.state === 'UPCOMING' ? 'outlined' : 'filled'} />
                    {r.state === 'PARTIAL' || (r.state === 'PAST_DUE' && r.principalPaid > 0) ? ` ${money(r.principalPaid)} paid` : ''}
                  </TableCell>
                </TableRow>
              ))}
              {data.schedule.length === 0 && <TableRow><TableCell colSpan={5}>There is no repayment schedule for this loan.</TableCell></TableRow>}
            </TableBody>
          </Table>
        )}
        {data && tab === 1 && (
          <>
            {data.entriesTruncated && <Alert severity="warning" sx={{ mb: 1 }}>Only the first 1,000 entries are shown.</Alert>}
            <Table size="small" aria-label="Loan ledger">
              <TableHead>
                <TableRow><TableCell>Date</TableCell><TableCell>Entry</TableCell><TableCell>Transaction</TableCell><TableCell>Document</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell><TableCell align="right">Principal balance</TableCell></TableRow>
              </TableHead>
              <TableBody>
                {data.entries.map((e, i) => (
                  <TableRow key={`${e.documentNo}-${e.event}-${i}`}>
                    <TableCell>{e.date}</TableCell><TableCell>{e.eventName ?? `Event ${e.event}`}</TableCell><TableCell>{e.transaction ?? `Type ${e.transactionCode}`}</TableCell><TableCell>{e.documentNo ?? '—'}</TableCell>
                    <TableCell align="right" sx={num}>{e.debit ? money(e.debit) : ''}</TableCell><TableCell align="right" sx={num}>{e.credit ? money(e.credit) : ''}</TableCell>
                    <TableCell align="right" sx={num}>{e.principalBalance == null ? '' : money(e.principalBalance)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  )
}

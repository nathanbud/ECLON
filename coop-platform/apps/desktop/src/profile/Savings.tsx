import { type Id } from '../clients'
import TransactionDialog, { txRowProps, type TxKey } from '../TransactionDialog'
import { useEffect, useState } from 'react'
import { askFoOverride, checkSavingsWithdrawal, getSavings, getSavingsHistory, postSavingsDeposit, postSavingsWithdrawal, type SavingsAccount, type SavingsReceipt, type SavingsWithdrawalCheck } from '../api'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, TextField, FormControlLabel, Switch } from '@mui/material'
import { DetailPanel, EmptyState, TableCard } from './fields'
import { FiInfo } from 'react-icons/fi'
import { CoMakerNote } from './CoMakerNote'
import { fmtDate, peso } from './format'
import ChequeFields, { chequeSum, chequesToApi, type ChequeRow } from '../ChequeFields'
import MoneyField from '../MoneyField'
import PrintInvoice from '../ServiceInvoice'
import { useRequestKey } from '../ui/requestKey'

export function SavingsTab({ id, teller = false }: { id: Id; teller?: boolean }) {
  const [open, setOpen] = useState<{ typeCode: number; refNo: number } | null>(null)
  const [showClosed, setShowClosed] = useState(false)
  const { data, error, isLoading } = useQuery({ queryKey: ['member-savings', id], queryFn: () => getSavings(id.branchCode, id.clientId), staleTime: 60_000 })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={160} />
  if (!data || data.accounts.length === 0) return <EmptyState icon={<FiInfo />} title="No savings" hint="This client has no savings accounts." />
  const num = { fontVariantNumeric: 'tabular-nums' } as const
  const closedCount = data.accounts.filter((a) => a.status !== 15).length
  const shown = showClosed ? data.accounts : data.accounts.filter((a) => a.status === 15)
  return (
    <>
      <CoMakerNote id={id} />
      {closedCount > 0 && <FormControlLabel sx={{ mb: 0.5 }} control={<Switch size="small" checked={showClosed} onChange={(_, v) => setShowClosed(v)} />} label={`Show closed and dormant accounts (${closedCount})`} />}
      <TableCard>
        <Table size="small" aria-label="Savings accounts">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell>
              {!teller && <><TableCell>Account no.</TableCell><TableCell>Opened</TableCell><TableCell>Status</TableCell><TableCell>Last activity</TableCell>
              <TableCell align="right">Rate %</TableCell><TableCell align="right">Balance</TableCell><TableCell align="right">Uncleared</TableCell><TableCell align="right">On hold</TableCell></>}
              <TableCell align="right">Available</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((a) => (
              <TableRow key={`${a.typeCode}-${a.refNo}`} hover selected={open?.typeCode === a.typeCode && open.refNo === a.refNo} sx={{ cursor: 'pointer' }} onClick={() => setOpen(open?.typeCode === a.typeCode && open.refNo === a.refNo ? null : { typeCode: a.typeCode, refNo: a.refNo })}>
                <TableCell>{a.type ?? `Type ${a.typeCode}`}</TableCell>
                {!teller && (<>
                  <TableCell>{a.refNoText ?? a.refNo}</TableCell>
                  <TableCell>{fmtDate(a.opened) ?? '—'}</TableCell><TableCell>{a.statusName}</TableCell><TableCell>{fmtDate(a.lastActivity) ?? '—'}</TableCell>
                  <TableCell align="right" sx={num}>{a.interestRate == null ? '—' : a.interestRate.toFixed(2)}</TableCell>
                  <TableCell align="right" sx={num}>{peso(a.balance)}</TableCell>
                  <TableCell align="right" sx={num}>{a.uncleared ? peso(a.uncleared) : '—'}</TableCell>
                  <TableCell align="right" sx={num}>{a.held ? peso(a.held) : '—'}</TableCell>
                </>)}
                <TableCell align="right" sx={num}>{peso(a.available)}</TableCell>
              </TableRow>
            ))}
            <TableRow>
              {teller ? (
                <>
                  <TableCell sx={{ fontWeight: 700 }}>Total available</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{peso(shown.reduce((n, a) => n + a.available, 0))}</TableCell>
                </>
              ) : (
                <>
                  <TableCell colSpan={6} sx={{ fontWeight: 700 }}>Total savings</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{peso(data.totalBalance)}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{data.accounts.some((a) => a.uncleared) ? peso(data.accounts.reduce((n, a) => n + a.uncleared, 0)) : '—'}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{data.totalHeld ? peso(data.totalHeld) : '—'}</TableCell>
                  <TableCell />
                </>
              )}
            </TableRow>
          </TableBody>
        </Table>
      </TableCard>
      {open && <SavingsHistory key={`${open.typeCode}-${open.refNo}`} id={id} typeCode={open.typeCode} refNo={open.refNo} onClose={() => setOpen(null)} />}
    </>
  )
}

export function SavingsCashDialog({ id, account, mode, initialAmount = '', initialExplanation = '', onClose }: { id: Id; account: SavingsAccount; mode: 'deposit' | 'withdraw'; initialAmount?: string; initialExplanation?: string; onClose: () => void }) {
  const requestKey = useRequestKey() // one key for this posting: a second press, a retry or a confirm does not post twice
  const qc = useQueryClient()
  const withdraw = mode === 'withdraw'
  const [amount, setAmount] = useState(initialAmount)
  const [receiptNo, setReceiptNo] = useState('')
  const [explanation, setExplanation] = useState(initialExplanation)
  const [receipt, setReceipt] = useState<SavingsReceipt | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [askConfirm, setAskConfirm] = useState(false)
  const [check, setCheck] = useState<SavingsWithdrawalCheck | null>(null)
  const [cheques, setCheques] = useState<ChequeRow[]>([])
  const value = Number(amount)
  const valid = amount.trim() !== '' && Number.isFinite(value) && value > 0 && chequeSum(cheques) <= value + 0.001
  const body = { clientId: id.clientId, typeCode: account.typeCode, refNo: account.refNo, amount: value, explanation, receiptNo }

  // For a withdrawal, ask the server what stands in the way and what a supervisor has to allow; look again while a request waits.
  const waiting = !!check?.needs.some((n) => n.status === 'PENDING')
  useEffect(() => {
    if (!withdraw || !valid || receipt) { setCheck(null); return }
    let live = true
    const run = () => { checkSavingsWithdrawal(body).then((c) => { if (live) setCheck(c) }).catch((e: Error) => { if (live) setError(e.message) }) }
    const t = setTimeout(run, 400)
    const poll = waiting ? setInterval(run, 4000) : undefined
    return () => { live = false; clearTimeout(t); if (poll) clearInterval(poll) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [withdraw, valid, value, waiting, receipt])

  const ask = async () => {
    setBusy(true); setError(null)
    try {
      for (const n of check?.needs ?? []) {
        if (n.status === 'NONE' || n.status === 'REJECTED') await askFoOverride({ draftId: null, kind: n.kind, description: n.description, amount: n.amount, limitAmount: n.limit })
      }
      setCheck(await checkSavingsWithdrawal(body))
    } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  const submit = async (confirmDuplicate: boolean) => {
    setBusy(true); setError(null)
    try {
      const r = await (withdraw ? postSavingsWithdrawal : postSavingsDeposit)({ ...body, confirmDuplicate, ...(withdraw ? {} : { cheques: chequesToApi(cheques) }) }, requestKey)
      setReceipt(r)
      qc.invalidateQueries({ queryKey: ['member-savings'] })
      qc.invalidateQueries({ queryKey: ['member-savings-history'] })
    } catch (e) {
      const msg = (e as Error).message
      setError(msg)
      setAskConfirm(msg.includes('confirm it'))
    } finally { setBusy(false) }
  }

  const needsAsking = withdraw && !!check && check.needs.some((n) => n.status === 'NONE' || n.status === 'REJECTED')
  const blocked = withdraw && (!check || !check.ready)
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{receipt ? (withdraw ? 'Withdrawal posted' : 'Deposit posted') : withdraw ? 'Withdraw cash' : 'Deposit cash'}</DialogTitle>
      <DialogContent>
        {!receipt && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <Box sx={{ fontSize: 13 }}>{account.type ?? 'Savings'} {account.refNoText ?? account.refNo} · balance {peso(account.balance)}{account.held ? ` · on hold ${peso(account.held)}` : ''} · available {peso(account.available)}</Box>
            <MoneyField label="Amount" autoFocus value={amount} onValueChange={(v) => { setAmount(v); setAskConfirm(false); setError(null) }} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
            {!withdraw && <ChequeFields rows={cheques} onChange={(r) => { setCheques(r); setAskConfirm(false); setError(null) }} total={value > 0 ? value : 0} />}
            <TextField label="Receipt number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} helperText="Up to 8 letters or digits. Left empty, the system numbers it." />
            <TextField label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder={withdraw ? 'SD WTH' : 'SD DEP'} />
            <Box sx={{ fontSize: 12 }}>{withdraw ? 'Cash is paid out of your own drawer and posted to the ledger at once.' : 'Cash goes into your own drawer and is posted to the ledger at once.'}</Box>
            {withdraw && check && check.problems.map((p) => <Alert key={p} severity="error">{p}</Alert>)}
            {withdraw && check && check.needs.map((n) => (
              <Alert key={n.kind} severity={n.status === 'APPROVED' ? 'success' : n.status === 'REJECTED' ? 'error' : 'warning'}>
                A supervisor has to allow this: {n.title}.{' '}
                <strong>{n.status === 'APPROVED' ? 'Allowed.' : n.status === 'PENDING' ? 'Waiting for a supervisor...' : n.status === 'REJECTED' ? 'Refused. You can ask again.' : 'Not asked yet.'}</strong>
              </Alert>
            ))}
          </Box>
        )}
        {receipt && (
          <Box sx={{ display: 'grid', gap: 0.5, pt: 1, fontSize: 13 }}>
            <Box>Receipt <strong>{receipt.documentNo}</strong> (control {receipt.controlNo}) dated {receipt.date}</Box>
            <Box>{receipt.clientName}</Box>
            <Box>{receipt.account}</Box>
            <Box>{withdraw ? 'Paid out' : 'Received'} <strong>{peso(receipt.amount)}</strong>, new balance <strong>{peso(receipt.balanceAfter)}</strong></Box>
            <Box>By {receipt.cashier}</Box>
          </Box>
        )}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        {receipt ? (
          <>
            <PrintInvoice documentNo={receipt.documentNo} controlNo={receipt.controlNo} date={receipt.date} member={receipt.clientName} cashier={receipt.cashier}
              lines={[{ label: 'Account', value: receipt.account }, { label: withdraw ? 'Withdrawal' : 'Deposit', value: peso(receipt.amount), bold: true }, { label: 'New balance', value: peso(receipt.balanceAfter) }]} />
            <Button onClick={onClose}>Close</Button>
          </>
        ) : (
          <>
            <Button onClick={onClose} disabled={busy}>Cancel</Button>
            {askConfirm
              ? <Button variant="contained" color="warning" disabled={busy} onClick={() => submit(true)}>Post anyway</Button>
              : needsAsking
                ? <Button variant="contained" disabled={busy || (check?.problems.length ?? 0) > 0} onClick={ask}>Ask a supervisor</Button>
                : <Button variant="contained" disabled={busy || !valid || blocked} onClick={() => submit(false)}>{withdraw ? 'Post withdrawal' : 'Post deposit'}</Button>}
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}

function SavingsHistory({ id, typeCode, refNo, onClose }: { id: Id; typeCode: number; refNo: number; onClose: () => void }) {
  const [page, setPage] = useState(0)
  const [tx, setTx] = useState<TxKey | null>(null)
  const size = 25
  const { data, error } = useQuery({ queryKey: ['member-savings-history', id, typeCode, refNo, page], queryFn: () => getSavingsHistory(id.branchCode, id.clientId, typeCode, refNo, page, size) })
  const num = { fontVariantNumeric: 'tabular-nums' } as const
  return (
    <DetailPanel
      title={data ? `${data.account.type ?? 'Savings'} ${data.account.refNoText ?? ''}` : 'Savings'} onClose={onClose}
      footer={<>
        {data && <Box sx={{ flex: 1, fontSize: 13 }}>{data.totalEntries} entries</Box>}
        <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</Button>
        <Button disabled={!data || (page + 1) * size >= data.totalEntries} onClick={() => setPage(page + 1)}>Older</Button>
      </>}
    >
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {data && (
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: '4px 16px', mb: 1.5, fontSize: 13 }}>
            {([['Ledger balance', data.account.balance], ['Uncleared cheques', data.account.uncleared], ['Cleared balance', data.account.balance - data.account.uncleared],
              ['On hold', data.account.held], ['Available to withdraw', data.account.available]] as [string, number][]).map(([k, v]) => (
              <Box key={k}><Box component="span" sx={{ fontSize: 12, color: 'text.secondary' }}>{k}: </Box><Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: k === 'Available to withdraw' ? 700 : undefined }}>{peso(v)}</Box></Box>
            ))}
          </Box>
        )}
        {data && data.holds.length > 0 && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            {data.holds.map((h, i) => (
              <Box key={i}>{h.reason ?? 'Hold'}: {peso(h.amount)}{h.description ? ` — ${h.description}` : ''}{h.date ? ` (${fmtDate(h.date)})` : ''}</Box>
            ))}
          </Alert>
        )}
        {data && (
          <Table size="small" aria-label="Savings entries">
            <TableHead>
              <TableRow><TableCell>Date</TableCell><TableCell>TR</TableCell><TableCell>Document</TableCell><TableCell>Cheque</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell><TableCell align="right">Balance</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {data.entries.map((e, i) => (
                <TableRow key={`${e.documentNo}-${i}`} {...txRowProps(e, setTx)}>
                  <TableCell>{fmtDate(e.date) ?? '—'}</TableCell><TableCell>{e.transaction ?? `Code ${e.transactionCode}`}</TableCell><TableCell>{e.documentNo ?? '—'}</TableCell>
                  <TableCell>{e.chequeNo ? `${e.chequeNo} · ${e.chequeCleared ? (e.clearedOn ? `cleared ${fmtDate(e.clearedOn)}` : 'cleared') : 'not cleared'}` : ''}</TableCell>
                  <TableCell align="right" sx={{ color: 'error.main', ...num }}>{e.amount < 0 ? peso(-e.amount) : ''}</TableCell><TableCell align="right" sx={num}>{e.amount > 0 ? peso(e.amount) : ''}</TableCell>
                  <TableCell align="right" sx={num}>{peso(e.balanceAfter)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      {tx && <TransactionDialog id={id} tx={tx} onClose={() => setTx(null)} />}
    </DetailPanel>
  )
}

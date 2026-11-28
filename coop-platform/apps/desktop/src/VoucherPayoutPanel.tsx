import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { askFoOverride, checkVoucherPayout, getVoucherPayablesOf, getVoucherPayablesWaiting, postVoucherPayout, type VoucherPayable, type VoucherPayoutCheck, type VoucherPayoutReceipt } from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'
import { useRequestKey } from './ui/requestKey'
import { notifyError } from './ui/notices'
import { LoadingBlock } from './ui/Loading'
import { Hinted } from './appleUi'

/**
 * Members whose released loan, share withdrawal or time deposit payout is waiting in their vouchers-payable account: the cashier pays the whole balance
 * out of their own drawer, and it is posted at once (like the old Cash Voucher payment). Look a member up by client number, or pick from those waiting.
 */
export function VoucherPayoutPanel() {
  const waiting = useQuery({ queryKey: ['voucher-payables-waiting'], queryFn: getVoucherPayablesWaiting, refetchInterval: 30_000, retry: false })
  const [clientText, setClientText] = useState('')
  const [lookup, setLookup] = useState<number | null>(null)
  const found = useQuery({ queryKey: ['voucher-payables-of', lookup], queryFn: () => getVoucherPayablesOf(lookup!), enabled: lookup !== null, retry: false })
  const [paying, setPaying] = useState<VoucherPayable | null>(null)

  if (waiting.isLoading) return <LoadingBlock q={waiting} what="the vouchers payable" />
  if (waiting.error) return null // no right to pay vouchers out
  const rows = lookup !== null ? found.data ?? [] : waiting.data ?? []
  const clientNo = Number(clientText)
  const canFind = clientText.trim() !== '' && Number.isInteger(clientNo) && clientNo > 0
  return (
    <Box sx={(t) => ({ p: 1.5, flex: 1, minHeight: 0, overflow: 'auto', ...glass(t) })}>
      <Typography variant="overline" sx={{ display: 'block', mb: 0.75 }} color="text.secondary">Pay out vouchers payable</Typography>
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', mb: 1 }}>
        <TextField size="small" label="Member number" value={clientText} onChange={(e) => setClientText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && canFind) setLookup(clientNo) }} sx={{ width: 180 }} />
        <Button variant="outlined" disabled={!canFind} onClick={() => setLookup(clientNo)}>Find</Button>
        {lookup !== null && <Button onClick={() => { setLookup(null); setClientText('') }}>Show those waiting</Button>}
      </Box>
      {found.error && lookup !== null && <Alert severity="error" sx={{ mb: 1 }}>{(found.error as Error).message}</Alert>}
      {rows.length === 0 && <Typography sx={{ py: 2 }} color="text.secondary">{lookup !== null ? 'This member has no money waiting in a vouchers-payable account.' : 'Nobody has money waiting from a recent voucher. Look a member up by member number.'}</Typography>}
      {rows.map((r) => (
        <Box key={`${r.clientId}-${r.refNo}`} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.9, borderTop: '1px solid', borderColor: 'divider' }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography>{r.client ?? `Member ${r.clientId}`} · {money(r.balance)}</Typography>
            <Typography variant="body2" color="text.secondary" noWrap>Client {r.clientId} · payable account {r.refNo}{r.lastCredit ? ` · last credited ${r.lastCredit}` : ''}</Typography>
          </Box>
          <Button size="small" variant="contained" onClick={() => setPaying(r)}>Pay out</Button>
        </Box>
      ))}
      {paying && <PayDialog payable={paying} onClose={() => setPaying(null)} />}
    </Box>
  )
}

function PayDialog({ payable, onClose }: { payable: VoucherPayable; onClose: () => void }) {
  const requestKey = useRequestKey() // one key for this posting: a second press, a retry or a confirm does not post twice
  const qc = useQueryClient()
  const [receiptNo, setReceiptNo] = useState('')
  const [explanation, setExplanation] = useState('')
  const [check, setCheck] = useState<VoucherPayoutCheck | null>(null)
  const [receipt, setReceipt] = useState<VoucherPayoutReceipt | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const body = { clientId: payable.clientId, refNo: payable.refNo, explanation, receiptNo }

  // what stands in the way and what a supervisor has to allow; look again while a request waits
  const waitingForSupervisor = !!check?.needs.some((n) => n.status === 'PENDING')
  useEffect(() => {
    if (receipt) return
    let live = true
    const run = () => { checkVoucherPayout({ clientId: payable.clientId, refNo: payable.refNo }).then((c) => { if (live) setCheck(c) }).catch((e: Error) => { if (live) setError(e.message) }) }
    run()
    const poll = waitingForSupervisor ? setInterval(run, 4000) : undefined
    return () => { live = false; if (poll) clearInterval(poll) }
  }, [payable.clientId, payable.refNo, waitingForSupervisor, receipt])

  const ask = async () => {
    setBusy(true)
    try {
      for (const n of check?.needs ?? []) {
        if (n.status === 'NONE' || n.status === 'REJECTED') await askFoOverride({ draftId: null, kind: n.kind, description: n.description, amount: n.amount, limitAmount: n.limit })
      }
      setCheck(await checkVoucherPayout({ clientId: payable.clientId, refNo: payable.refNo }))
    } catch (e) { notifyError(e) } finally { setBusy(false) }
  }

  const pay = async () => {
    setBusy(true)
    try {
      setReceipt(await postVoucherPayout(body, requestKey))
      void qc.invalidateQueries({ queryKey: ['voucher-payables-waiting'] })
      void qc.invalidateQueries({ queryKey: ['voucher-payables-of'] })
    } catch (e) { notifyError(e) } finally { setBusy(false) }
  }

  const needsAsking = !!check && check.needs.some((n) => n.status === 'NONE' || n.status === 'REJECTED')
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{receipt ? 'Paid out' : 'Pay out vouchers payable'}</DialogTitle>
      <DialogContent>
        {!receipt && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <Box sx={{ fontSize: 13 }}>{payable.client ?? `Member ${payable.clientId}`} · payable account {payable.refNo}</Box>
            <Box sx={{ fontSize: 22, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{money(check?.payable.balance ?? payable.balance)}</Box>
            <Box sx={{ fontSize: 12 }}>The whole balance is paid in cash out of your own drawer{check ? ` (it holds ${money(check.drawerCash)})` : ''} and posted to the ledger at once.</Box>
            <Hinted tip="Up to 8 letters or digits. Left empty, the system numbers it."><TextField label="Receipt number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} /></Hinted>
            <TextField label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder="VOUCHER PAYMENT" />
            {check?.problems.map((p) => <Alert key={p} severity="error">{p}</Alert>)}
            {check?.needs.map((n) => (
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
            <Box>{receipt.clientName ?? `Member ${receipt.clientId}`}</Box>
            <Box>Paid out <strong>{money(receipt.amount)}</strong> from payable account {receipt.refNo}</Box>
            <Box>By {receipt.cashier}</Box>
          </Box>
        )}
        {/* the check could not be made: shown here, like a list that could not load; a refused action pops up (notifyError) */}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        {receipt ? <Button onClick={onClose}>Close</Button> : (
          <>
            <Button onClick={onClose} disabled={busy}>Cancel</Button>
            {needsAsking
              ? <Button variant="contained" disabled={busy || (check?.problems.length ?? 0) > 0} onClick={ask}>Ask a supervisor</Button>
              : <Button variant="contained" color="success" disabled={busy || !check || !check.ready} onClick={pay}>Pay out cash</Button>}
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}

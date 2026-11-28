import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField, Typography } from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { encodeTdInterest, quoteTdInterest, type TdCloseEncoded, type TdInterestQuote } from './api'
import MoneyField from './MoneyField'
import { notifyError } from './ui/notices'
import { money as peso } from './ui/format'


/**
 * Withdraw interest from a running Lock In certificate on an anniversary, on the person asking. The certificate is not touched: the voucher takes the accrued interest
 * (and the rest from the interest expense) and credits the member's vouchers payable. It then waits on the Disburse tab for two other people to approve and post it.
 */
export default function TimeDepositInterestDialog({ clientId, typeCode, refNo, label, onClose }: {
  clientId: number; typeCode: number; refNo: number; label: string; onClose: () => void
}) {
  const qc = useQueryClient()
  const [amount, setAmount] = useState('')
  const [explanation, setExplanation] = useState('')
  const [quote, setQuote] = useState<TdInterestQuote | null>(null)
  const [done, setDone] = useState<TdCloseEncoded | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const body = { clientId, typeCode, refNo, amount: amount.trim() === '' ? undefined : Number(amount), explanation }
  const key = JSON.stringify(body)

  // the figures come from the server each time the amount changes; left blank it is one year of interest, or what has fallen due if less
  useEffect(() => {
    if (done) return
    let live = true
    const t = setTimeout(() => {
      quoteTdInterest(body).then((q) => { if (live) { setQuote(q); setError(null) } }).catch((e: Error) => { if (live) setError(e.message) })
    }, 300)
    return () => { live = false; clearTimeout(t) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, done])

  const submit = async () => {
    setBusy(true); setError(null)
    try {
      setDone(await encodeTdInterest(body))
      void qc.invalidateQueries({ queryKey: ['member-time-deposits'] })
    } catch (e) { notifyError(e) } finally { setBusy(false) }
  }

  if (done) {
    return (
      <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
        <DialogTitle>Voucher encoded</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'grid', gap: 0.5, fontSize: 13 }}>
            <div>{done.clientName}: interest of <b>{peso(done.due)}</b> goes to the member vouchers payable</div>
            <div>Voucher no. <b>{done.documentNo}</b> now waits on the Disburse tab: another person approves it and a third posts it.</div>
          </Box>
        </DialogContent>
        <DialogActions><Button variant="contained" onClick={onClose}>Done</Button></DialogActions>
      </Dialog>
    )
  }
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{label}: withdraw interest</DialogTitle>
      <DialogContent>
        <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
          {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
          {quote && (
            <Box sx={{ display: 'grid', gridTemplateColumns: 'auto auto', justifyContent: 'start', columnGap: 3, rowGap: 0.25, fontSize: 13 }}>
              <span>Certificate</span><b>{quote.type} {quote.certificate ?? quote.refNo}</b>
              <span>Balance at {quote.ratePercent}%</span><b>{peso(quote.balance)}</b>
              <span>Anniversaries passed</span><b>{quote.completedYears}</b>
              <span>A year of interest</span><span>{peso(quote.yearInterest)}</span>
              <span>Already withdrawn</span><span>{peso(quote.withdrawn)}</span>
              <span>Fallen due, still to withdraw</span><b>{peso(quote.available)}</b>
              <span>Accrued so far</span><span>{peso(quote.accrued)}</span>
            </Box>
          )}
          <MoneyField size="small" label="Interest to pay (blank = a year, or what is due if less)" value={amount} onValueChange={(v) => setAmount(v)} />
          {quote && quote.amount > 0 && (
            <Typography><b>{peso(quote.amount)}</b> goes to the member vouchers payable: {peso(quote.fromAccrual)} from the accrued interest
              {quote.expense > 0 && <> and {peso(quote.expense)} charged to interest expense</>}. The certificate keeps its principal.</Typography>
          )}
          <TextField size="small" label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder="LOCK IN INTEREST WITHDRAWAL" />
          {quote?.problems.map((p) => <Alert key={p} severity="warning">{p}</Alert>)}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>Cancel</Button>
        <Button variant="contained" disabled={busy || !quote?.ready} onClick={() => void submit()}>{busy ? 'Encoding…' : 'Encode the voucher'}</Button>
      </DialogActions>
    </Dialog>
  )
}

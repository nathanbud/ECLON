import { Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { encodeTdClose, quoteTdClose, type TdCloseEncoded, type TdCloseQuote, type TdCloseReq } from './api'
import MoneyField from './MoneyField'
import { money as peso } from './ui/format'


/**
 * Pay out or renew one matured time deposit, on the person asking (nothing happens by itself). It encodes the voucher; the voucher then waits on the Disburse
 * tab for two other people to approve and post it. A renewal can take some of the money out, change the term and rate, and carries the renewal fee.
 */
export default function TimeDepositCloseDialog({ clientId, typeCode, refNo, label, initialMode, onClose }: {
  clientId: number; typeCode: number; refNo: number; label: string; initialMode: 'PAYOUT' | 'RENEW'; onClose: () => void
}) {
  const qc = useQueryClient()
  const [mode, setMode] = useState<'PAYOUT' | 'RENEW'>(initialMode)
  const [term, setTerm] = useState<number | ''>('')
  const [rate, setRate] = useState('')
  const [withdraw, setWithdraw] = useState('')
  const [fee, setFee] = useState(true)
  const [years, setYears] = useState(1)
  const [pretermRate, setPretermRate] = useState('')
  const [interestPaid, setInterestPaid] = useState('')
  const [newType, setNewType] = useState<number | ''>('')
  const [explanation, setExplanation] = useState('')
  const [quote, setQuote] = useState<TdCloseQuote | null>(null)
  const [done, setDone] = useState<TdCloseEncoded | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const isLockIn = typeCode === 2 || typeCode === 4
  const body: TdCloseReq = {
    clientId, typeCode, refNo, mode,
    // a Lock In pays interest by the year; left blank, "take out" is the interest and the principal is renewed (what the old vouchers did)
    ...(isLockIn ? { interestYears: years } : {}),
    // a certificate that has not matured is pre-terminated: Short Term pays a typed rate (1% unless changed), Lock In pays a typed amount (0 unless changed)
    ...(pretermRate.trim() !== '' && !isLockIn ? { pretermRate: Number(pretermRate) } : {}),
    ...(interestPaid.trim() !== '' && isLockIn ? { interestPaid: Number(interestPaid) } : {}),
    ...(mode === 'RENEW' ? { termDays: term === '' ? undefined : term, ratePercent: rate.trim() === '' ? undefined : Number(rate), withdraw: isLockIn && withdraw.trim() === '' ? undefined : Number(withdraw) || 0,
      renewalFee: isLockIn || quote?.preterm ? false : fee, ...(isLockIn && newType !== '' ? { newTypeCode: newType } : {}) } : {}),
    explanation,
  }
  const key = JSON.stringify(body)

  // the figures come from the server each time the form changes; the first answer fills in the term and rate of the old certificate
  useEffect(() => {
    if (done) return
    let live = true
    const t = setTimeout(() => {
      quoteTdClose(body).then((q) => {
        if (!live) return
        setQuote(q); setError(null)
        if (term === '' && q.termDays && (!q.lockIn || q.terms.includes(q.termDays))) setTerm(q.termDays)
        if (rate === '' && q.ratePercent != null && (!q.lockIn || q.newTypeCode === typeCode)) setRate(String(q.ratePercent))
        if (q.lockIn && newType === '') setNewType(q.newTypeCode)
      }).catch((e: Error) => { if (live) setError(e.message) })
    }, 300)
    return () => { live = false; clearTimeout(t) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, done])

  const submit = async () => {
    setBusy(true); setError(null)
    try {
      const r = await encodeTdClose(body)
      setDone(r)
      void qc.invalidateQueries({ queryKey: ['member-time-deposits'] }); void qc.invalidateQueries({ queryKey: ['td-maturities'] }); void qc.invalidateQueries({ queryKey: ['td-maturity-count'] })
    } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  if (done) {
    return (
      <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
        <DialogTitle>Voucher encoded</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'grid', gap: 0.5, fontSize: 13 }}>
            <div>{done.clientName}: {done.mode === 'RENEW' ? 'renewal' : 'payout'} of <b>{peso(done.due)}</b></div>
            {done.newCertificate != null && <div>New certificate <b>{done.newCertificate}</b> for {peso(done.newPrincipal ?? 0)}</div>}
            {done.paidToPayable > 0 && <div>{peso(done.paidToPayable)} goes to the member vouchers payable</div>}
            <div>Voucher no. <b>{done.documentNo}</b> now waits on the Disburse tab: another person approves it and a third posts it.</div>
          </Box>
        </DialogContent>
        <DialogActions><Button variant="contained" onClick={onClose}>Done</Button></DialogActions>
      </Dialog>
    )
  }
  const renew = mode === 'RENEW'
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{label}</DialogTitle>
      <DialogContent>
        <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
          {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
          <ToggleButtonGroup exclusive size="small" value={mode} onChange={(_, v) => { if (v) { setMode(v); setQuote(null) } }}>
            <ToggleButton value="PAYOUT">Pay out</ToggleButton>
            <ToggleButton value="RENEW">Renew</ToggleButton>
          </ToggleButtonGroup>
          {quote && (
            <Box sx={{ display: 'grid', gridTemplateColumns: 'auto auto', justifyContent: 'start', columnGap: 3, rowGap: 0.25, fontSize: 13 }}>
              <span>Certificate</span><b>{quote.type} {quote.certificate ?? quote.refNo}</b>
              <span>{quote.preterm ? 'Matures' : 'Matured'}</span><b>{quote.maturity}</b>
              {quote.preterm && <><span>Held</span><b>{quote.daysHeld} days: a pre-termination</b></>}
              <span>Balance</span><b>{peso(quote.principal)}</b>
              {quote.preterm
                ? <><span>Interest paid ({quote.lockIn ? 'typed' : `${quote.pretermRate}% for ${quote.daysHeld} days`})</span><b>{peso(quote.interest)}</b></>
                : quote.lockIn
                ? <><span>Interest for {quote.interestYears} year{quote.interestYears === 1 ? '' : 's'} at {quote.ratePercent}%</span><b>{peso(quote.interest)}</b></>
                : <><span>Interest for the term ({quote.termDays} days, {quote.ratePercent}%)</span><b>{peso(quote.interest)}</b></>}
              <span>of which already accrued</span><span>{peso(quote.accrued)}</span>
              {quote.preterm && <><span>Termination charge</span><b>−{peso(quote.terminationFee)}</b></>}
              <span>Money due to the member</span><b>{peso(quote.due)}</b>
            </Box>
          )}
          {quote?.preterm && !quote.lockIn && (
            <TextField size="small" label="Interest rate % a year for the days held" value={pretermRate} placeholder={String(quote.pretermRate ?? 1)} onChange={(e) => setPretermRate(e.target.value.replace(/[^0-9.]/g, ''))}
              helperText="1% is what the old vouchers used; never above the certificate's own rate" />
          )}
          {quote?.preterm && quote.lockIn && (
            <MoneyField size="small" label="Interest paid (blank = none, the accrual is taken back)" value={interestPaid} onValueChange={(v) => setInterestPaid(v)} />
          )}
          {quote?.lockIn && !quote.preterm && (
            <TextField select size="small" label="Years of interest paid now" value={years} onChange={(e) => setYears(Number(e.target.value))}
              helperText="1 for the last year; more when earlier years were never withdrawn">
              {Array.from({ length: quote.maxYears }, (_, i) => i + 1).map((y) => <MenuItem key={y} value={y}>{y}</MenuItem>)}
            </TextField>
          )}
          {renew && (
            <>
              {isLockIn && (
                <TextField select size="small" label="Renew into" value={newType} onChange={(e) => { setNewType(Number(e.target.value)); setTerm(''); setRate('') }}>
                  <MenuItem value={1}>TD - Short Term</MenuItem>
                  <MenuItem value={2}>TD - Lock In</MenuItem>
                </TextField>
              )}
              <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 1.5 }}>
                <TextField select size="small" label="New term" value={term} onChange={(e) => setTerm(Number(e.target.value))}>
                  {(quote?.terms ?? []).map((d) => <MenuItem key={d} value={d}>{d} days</MenuItem>)}
                </TextField>
                <TextField size="small" label="Rate % a year" value={rate} onChange={(e) => setRate(e.target.value.replace(/[^0-9.]/g, ''))} />
                <MoneyField size="small" label={isLockIn ? 'Take out (blank = the interest)' : 'Take out'} value={withdraw} onValueChange={(v) => setWithdraw(v)} />
              </Box>
              {!isLockIn && !quote?.preterm && <FormControlLabel control={<Checkbox checked={fee} onChange={(_, v) => setFee(v)} />} label="Renewal certificate fee 10.00 (taken from the new certificate)" />}
              {quote?.newPrincipal != null && (
                <Typography sx={{ fontSize: 13 }}>The new certificate is <b>{peso(quote.newPrincipal)}</b>, maturing <b>{quote.newMaturity}</b>.
                  {quote.withdraw != null && quote.withdraw > 0 && <> {peso(quote.withdraw)} goes to the member vouchers payable.</>}</Typography>
              )}
            </>
          )}
          {!renew && quote && <Typography sx={{ fontSize: 13 }}>{quote.preterm ? 'This ends the certificate early. ' : ''}All of it, <b>{peso(quote.due)}</b>, goes to the member vouchers payable account to be paid out.</Typography>}
          <TextField size="small" label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder={quote?.preterm ? 'PRE-TERMINATION OF TD' : renew ? 'RENEWAL OF MATURED TD' : 'MATURED TD WITHDRAWAL'} />
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

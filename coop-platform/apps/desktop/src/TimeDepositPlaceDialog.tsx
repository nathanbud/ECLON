import { Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, TextField, Typography } from '@mui/material'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { askTdRate, checkTdPlacement, getTdSetup, placeTimeDeposit, type TdPlaceCheck, type TdPlaceIn, type TdPlaceReceipt } from './api'
import ChequeFields, { chequeSum, chequesToApi, type ChequeRow } from './ChequeFields'
import MoneyField from './MoneyField'

const peso = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * Place a new time deposit certificate for a member: choose the type and term, type the rate (the rates used for that term in the last year are shown; one outside
 * them needs a supervisor), the amount, and pay by cash, cheque or both. The certificate fee is added to what the member pays.
 */
export default function TimeDepositPlaceDialog({ clientId, onClose }: { clientId: number; onClose: () => void }) {
  const qc = useQueryClient()
  const setup = useQuery({ queryKey: ['td-setup'], queryFn: getTdSetup, staleTime: 5 * 60_000 })
  const [typeCode, setTypeCode] = useState<number | ''>('')
  const [termDays, setTermDays] = useState<number | ''>('')
  const [rate, setRate] = useState('')
  const [amount, setAmount] = useState('')
  const [certificate, setCertificate] = useState(true)
  const [explanation, setExplanation] = useState('')
  const [receiptNo, setReceiptNo] = useState('')
  const [cheques, setCheques] = useState<ChequeRow[]>([])
  const [check, setCheck] = useState<TdPlaceCheck | null>(null)
  const [receipt, setReceipt] = useState<TdPlaceReceipt | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [askConfirm, setAskConfirm] = useState(false)

  const type = setup.data?.types.find((t) => t.typeCode === typeCode)
  const term = type?.terms.find((t) => t.termDays === termDays)
  const value = Number(amount)
  const rateValue = Number(rate)
  const body: TdPlaceIn | null = typeCode !== '' && termDays !== '' && rate.trim() !== '' && Number.isFinite(rateValue) && amount.trim() !== '' && Number.isFinite(value)
    ? { clientId, typeCode, termDays, ratePercent: rateValue, amount: value, certificate, explanation, receiptNo, cheques: chequesToApi(cheques) } : null
  const fee = certificate ? (setup.data?.certificateFee ?? 0) : 0
  const chequesOver = chequeSum(cheques) > value + fee + 0.001
  const key = JSON.stringify(body)
  const waiting = check?.rateApproval === 'PENDING'

  // look again at the server whenever the form changes, and while a supervisor decides the rate
  useEffect(() => {
    if (!body || receipt) { setCheck(null); return }
    let live = true
    const run = () => { checkTdPlacement(body).then((c) => { if (live) setCheck(c) }).catch((e: Error) => { if (live) setError(e.message) }) }
    const t = setTimeout(run, 400)
    const poll = waiting ? setInterval(run, 4000) : undefined
    return () => { live = false; clearTimeout(t); if (poll) clearInterval(poll) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, waiting, receipt])

  const pick = (code: number) => {
    setTypeCode(code); setTermDays(''); setRate(''); setCheck(null)
  }
  const pickTerm = (days: number) => {
    setTermDays(days)
    const t = type?.terms.find((x) => x.termDays === days)
    if (t) setRate(String(t.usual))
  }
  const run = async (what: () => Promise<void>) => { setBusy(true); setError(null); try { await what() } catch (e) { setError((e as Error).message) } finally { setBusy(false) } }
  const ask = () => run(async () => { await askTdRate(body!); setCheck(await checkTdPlacement(body!)) })
  const submit = (confirmDuplicate: boolean) => run(async () => {
    try {
      const r = await placeTimeDeposit({ ...body!, confirmDuplicate })
      setReceipt(r); setAskConfirm(false)
      void qc.invalidateQueries({ queryKey: ['member-time-deposits'] }); void qc.invalidateQueries({ queryKey: ['fo-totals'] })
    } catch (e) {
      const m = (e as Error).message
      if (m.includes('confirm it')) setAskConfirm(true); else throw e
    }
  })

  const canPost = !!body && !!check && check.ready && !chequesOver && !busy

  if (receipt) {
    return (
      <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
        <DialogTitle>Time deposit placed</DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'grid', gap: 0.5, fontSize: 14 }}>
            <div>{receipt.clientName}</div>
            <div>{receipt.type} certificate <b>{receipt.certificate}</b>: {peso(receipt.amount)} for {receipt.termDays} days at {receipt.ratePercent}%</div>
            <div>Matures {receipt.maturity}</div>
            {receipt.fee > 0 && <div>Certificate fee {peso(receipt.fee)}</div>}
            <div>Received <b>{peso(receipt.total)}</b> · receipt {receipt.documentNo} · {receipt.cashier}</div>
          </Box>
        </DialogContent>
        <DialogActions><Button variant="contained" onClick={onClose}>Done</Button></DialogActions>
      </Dialog>
    )
  }
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Place a time deposit</DialogTitle>
      <DialogContent>
        <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
          {setup.isError && <Alert severity="error">{(setup.error as Error).message}</Alert>}
          {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
          <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5 }}>
            <TextField select size="small" label="Type" value={typeCode} onChange={(e) => pick(Number(e.target.value))}>
              {(setup.data?.types ?? []).map((t) => <MenuItem key={t.typeCode} value={t.typeCode}>{t.name}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Term (days)" value={termDays} onChange={(e) => pickTerm(Number(e.target.value))} disabled={!type}>
              {(type?.terms ?? []).map((t) => <MenuItem key={t.termDays} value={t.termDays}>{t.termDays} days</MenuItem>)}
            </TextField>
          </Box>
          <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5 }}>
            <TextField size="small" label="Interest rate % a year" value={rate} onChange={(e) => setRate(e.target.value.replace(/[^0-9.]/g, ''))} disabled={!term}
              helperText={term ? `Used for ${term.termDays} days: ${term.min} to ${term.max}% (most often ${term.usual}%)` : 'Choose the type and term first.'} />
            <MoneyField size="small" label="Amount to place" value={amount} onValueChange={(v) => setAmount(v)} helperText={type && type.minAmount > 0 ? `At least ${peso(type.minAmount)}` : undefined} />
          </Box>
          <FormControlLabel control={<Checkbox checked={certificate} onChange={(_, v) => setCertificate(v)} />}
            label={`Issue a certificate (fee ${peso(setup.data?.certificateFee ?? 0)} on top)`} />
          <ChequeFields rows={cheques} onChange={(r) => { setCheques(r); setAskConfirm(false); setError(null) }} total={value > 0 ? value + fee : 0} />
          <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1.5 }}>
            <TextField size="small" label="Receipt number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} />
            <TextField size="small" label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder={termDays ? `NEW TD ${termDays} DAYS` : 'NEW TD'} />
          </Box>
          {check && check.problems.map((p) => <Alert key={p} severity="warning">{p}</Alert>)}
          {chequesOver && <Alert severity="warning">The cheques add up to more than the payment.</Alert>}
          {check && check.problems.length === 0 && (
            <Typography sx={{ fontSize: 13.5 }}>
              Matures <b>{check.maturity}</b>
              {check.estimatedInterest != null && <> · interest at maturity about <b>{peso(check.estimatedInterest)}</b></>}
              {' '}· the member pays <b>{peso(check.total)}</b>
            </Typography>
          )}
          {check?.rateApproval === 'NONE' && <Alert severity="warning" action={<Button color="inherit" size="small" disabled={busy} onClick={() => void ask()}>Ask a supervisor</Button>}>This rate is outside what has been used for this term. A supervisor has to allow it.</Alert>}
          {check?.rateApproval === 'PENDING' && <Alert severity="info">Waiting for a supervisor to allow this rate.</Alert>}
          {check?.rateApproval === 'REJECTED' && <Alert severity="error" action={<Button color="inherit" size="small" disabled={busy} onClick={() => void ask()}>Ask again</Button>}>A supervisor did not allow this rate.</Alert>}
          {askConfirm && <Alert severity="warning" action={<Button color="inherit" size="small" onClick={() => void submit(true)}>Post anyway</Button>}>A receipt for this client was posted in the last minute (or a cheque was received before). Post another?</Alert>}
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>Cancel</Button>
        <Button variant="contained" disabled={!canPost} onClick={() => void submit(false)}>{busy ? 'Posting…' : 'Place the deposit'}</Button>
      </DialogActions>
    </Dialog>
  )
}

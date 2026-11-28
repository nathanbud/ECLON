import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { askFoOverride, checkFoDraftPost, getBankCashBanks, postFoDraft, type DraftPostIn, type DraftPostPlan, type DraftPosted } from './api'
import ChequeFields, { chequesToApi, type ChequeRow } from './ChequeFields'
import { ConfirmButton } from './ui/dialogs'
import { money } from './ui/format'
import { useRequestKey } from './ui/requestKey'
import { notifyError } from './ui/notices'
import { Hinted } from './appleUi'

const today = () => new Date().toISOString().slice(0, 10)

/**
 * Posts a saved front office draft (documentation/general-voucher-map.md). The server says what the draft becomes and what stands in the way: an other
 * receipt or a misc disbursement is posted at once on the person's drawer; a Cash Voucher or a Check Voucher is sent for another person's approval and
 * appears under Disburse > Vouchers waiting. Everything is checked again when it is posted.
 */
export default function DraftPostDialog({ draftId, payor, onClose, onPosted }: {
  draftId: number
  /** The draft's client name: the default payee of a Check Voucher. */
  payor: string
  onClose: () => void
  onPosted: (p: DraftPosted) => void
}) {
  const requestKey = useRequestKey() // one key for this posting: a second press or a retry does not post twice
  const [plan, setPlan] = useState<DraftPostPlan | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [cheques, setCheques] = useState<ChequeRow[]>([])
  const [receiptNo, setReceiptNo] = useState('')
  const [bank, setBank] = useState('')
  const [chequeNo, setChequeNo] = useState('')
  const [chequeDate, setChequeDate] = useState(today())
  const [payee, setPayee] = useState(payor)
  const banks = useQuery({ queryKey: ['bank-cash-banks'], queryFn: getBankCashBanks, staleTime: 10 * 60_000, enabled: plan?.kind === 'CHECK_VOUCHER', retry: false })
  const chosen = (banks.data ?? []).find((b) => `${b.bankId}/${b.ledgerAccount}` === bank) ?? null

  const body = (): DraftPostIn => ({
    receiptNo: receiptNo.trim() || undefined,
    cheques: plan?.kind === 'OTHER_RECEIPT' ? chequesToApi(cheques) : undefined,
    cheque: plan?.kind === 'CHECK_VOUCHER' ? { bankId: chosen?.bankId ?? null, account: chosen?.ledgerAccount ?? null, number: chequeNo, date: chequeDate, payee } : null,
  })

  // what posting would do; looked at again when the inputs change and while a supervisor is asked
  const waiting = !!plan?.needs.some((n) => n.status === 'PENDING')
  const inputs = JSON.stringify([plan?.kind, cheques, bank, chequeNo, chequeDate, payee])
  useEffect(() => {
    let live = true
    const run = () => { checkFoDraftPost(draftId, body()).then((p) => { if (live) { setPlan(p); setError(null) } }).catch((e: Error) => { if (live) setError(e.message) }) }
    const t = setTimeout(run, 250)
    const poll = waiting ? setInterval(run, 4000) : undefined
    return () => { live = false; clearTimeout(t); if (poll) clearInterval(poll) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftId, inputs, waiting])

  const ask = async () => {
    if (!plan) return
    setBusy(true)
    try {
      for (const n of plan.needs) if (n.status === 'NONE' || n.status === 'REJECTED') await askFoOverride({ draftId: null, kind: n.kind, description: n.description, amount: n.amount, limitAmount: n.limit })
      setPlan(await checkFoDraftPost(draftId, body()))
    } catch (e) { notifyError(e) } finally { setBusy(false) }
  }

  const post = async () => {
    setBusy(true)
    try {
      onPosted(await postFoDraft(draftId, body(), requestKey))
    } catch (e) { notifyError(e) } finally { setBusy(false) }
  }

  const waits = plan?.kind === 'CASH_VOUCHER' || plan?.kind === 'CHECK_VOUCHER'
  const needsAsking = !!plan && plan.needs.some((n) => n.status === 'NONE' || n.status === 'REJECTED')
  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="draft-post-title">
      <DialogTitle id="draft-post-title">{plan?.kindWords ? `Post: ${plan.kindWords}` : 'Post the draft'}</DialogTitle>
      <DialogContent>
        {!plan && !error && <Typography color="text.secondary">Checking the draft…</Typography>}
        {plan && (
          <Box sx={{ display: 'grid', gap: 1.5 }}>
            {plan.postsHow && <Typography>{plan.postsHow}</Typography>}
            <Table size="small" aria-label="What will be written">
              <TableHead><TableRow><TableCell>Account</TableCell><TableCell>Description</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
              <TableBody>
                {plan.lines.map((l, i) => (
                  <TableRow key={i}>
                    <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.account}</TableCell>
                    <TableCell>{l.description}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount > 0 ? money(l.amount) : ''}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{l.amount < 0 ? money(-l.amount) : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {plan.drawerCash != null && <Typography variant="body2" color="text.secondary">Your drawer holds {money(plan.drawerCash)}.</Typography>}

            {plan.kind === 'OTHER_RECEIPT' && (
              <>
                <Hinted tip="Up to 8 letters or digits. Left empty, the system numbers it."><TextField label="Receipt number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} /></Hinted>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>Paid by cheque? Add the cheques; whatever they do not cover is cash.</Typography>
                <ChequeFields rows={cheques} onChange={setCheques} total={plan.amount} />
              </>
            )}
            {plan.kind === 'MIXED_PAYMENT' && (
              <Hinted tip="Left empty, the system numbers it."><TextField label="Voucher number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} /></Hinted>
            )}
            {plan.kind === 'CHECK_VOUCHER' && (
              <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1 }}>
                <TextField select label="Drawn on (the cooperative's bank account)" value={bank} onChange={(e) => setBank(e.target.value)} sx={{ gridColumn: '1 / -1' }}>
                  {(banks.data ?? []).map((b) => <MenuItem key={`${b.bankId}/${b.ledgerAccount}`} value={`${b.bankId}/${b.ledgerAccount}`}>{b.name} · {b.ledgerAccount}</MenuItem>)}
                </TextField>
                <TextField label="Cheque number" value={chequeNo} onChange={(e) => setChequeNo(e.target.value.replace(/[^0-9]/g, '').slice(0, 15))} />
                <TextField type="date" label="Date on the cheque" value={chequeDate} onChange={(e) => setChequeDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
                <TextField label="Pay to" value={payee} onChange={(e) => setPayee(e.target.value.slice(0, 60))} sx={{ gridColumn: '1 / -1' }} />
              </Box>
            )}

            {plan.problems.map((p) => <Alert key={p} severity="error">{p}</Alert>)}
            {plan.needs.map((n) => (
              <Alert key={n.kind} severity={n.status === 'APPROVED' ? 'success' : n.status === 'REJECTED' ? 'error' : 'warning'}>
                A supervisor has to allow this: {n.title}.{' '}
                <strong>{n.status === 'APPROVED' ? 'Allowed.' : n.status === 'PENDING' ? 'Waiting for a supervisor…' : n.status === 'REJECTED' ? 'Refused. You can ask again.' : 'Not asked yet.'}</strong>
              </Alert>
            ))}
          </Box>
        )}
        {/* the check could not be made: shown here, like a list that could not load; a refused action pops up (notifyError) */}
        {error && <Alert severity="error" sx={{ mt: 2 }} role="alert">{error}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>Cancel</Button>
        {needsAsking
          ? <Button variant="contained" disabled={busy || (plan?.problems.length ?? 0) > 0} onClick={() => { void ask() }}>Ask a supervisor</Button>
          : (
            <ConfirmButton
              variant="contained" color={waits ? 'primary' : 'success'} disabled={busy || !plan?.ready}
              title={waits ? 'Send for approval?' : 'Post it now?'}
              message={waits
                ? `${plan?.kindWords} of ${money(plan?.amount ?? 0)} is written to wait for another person's approval. It cannot be edited afterwards.`
                : `${plan?.kindWords} of ${money(plan?.amount ?? 0)} is posted to the ledger now, dated the open business day, on your drawer. It cannot be edited afterwards.`}
              onConfirm={() => { void post() }}
            >
              {waits ? 'Send for approval' : 'Post'}
            </ConfirmButton>
          )}
      </DialogActions>
    </Dialog>
  )
}

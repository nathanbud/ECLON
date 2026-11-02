import { type Id } from '../clients'
import TransactionDialog, { txRowProps, type TxKey } from '../TransactionDialog'
import { useState } from 'react'
import { getShareHistory, getShares, postSharePayment, postShareWithdrawal, type ShareAccount, type SharePaymentReceipt, type ShareWithdrawalOut } from '../api'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, TextField, FormControlLabel, Switch } from '@mui/material'
import { DetailPanel, EmptyState, TableCard } from './fields'
import { FiInfo } from 'react-icons/fi'
import { CoMakerNote } from './CoMakerNote'
import { fmtDate, peso } from './format'
import ChequeFields, { chequeSum, chequesToApi, type ChequeRow } from '../ChequeFields'
import MoneyField from '../MoneyField'

export function ShareCapitalTab({ id, teller = false }: { id: Id; teller?: boolean }) {
  const [open, setOpen] = useState<{ typeCode: number; refNo: number } | null>(null)
  const [withdrawing, setWithdrawing] = useState<ShareAccount | null>(null)
  const [paying, setPaying] = useState<ShareAccount | null>(null)
  const [showClosed, setShowClosed] = useState(false)
  const { data, error, isLoading } = useQuery({ queryKey: ['member-shares', id], queryFn: () => getShares(id.branchCode, id.clientId), staleTime: 60_000 })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={160} />
  if (!data || data.accounts.length === 0) return <EmptyState icon={<FiInfo />} title="No share capital" hint="This client has no share capital accounts." />
  const closedCount = data.accounts.filter((a) => a.status !== 15).length
  const shown = showClosed ? data.accounts : data.accounts.filter((a) => a.status === 15)
  return (
    <>
      <CoMakerNote id={id} />
      {closedCount > 0 && <FormControlLabel sx={{ mb: 0.5 }} control={<Switch size="small" checked={showClosed} onChange={(_, v) => setShowClosed(v)} />} label={`Show closed and dormant accounts (${closedCount})`} />}
      <TableCard>
        <Table size="small" aria-label="Share capital accounts">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell><TableCell>Account no.</TableCell><TableCell>Opened</TableCell><TableCell>Status</TableCell>
              <TableCell>Last activity</TableCell><TableCell align="right">Minimum</TableCell><TableCell align="right">On hold</TableCell><TableCell align="right">Balance</TableCell><TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((a) => (
              <TableRow key={`${a.typeCode}-${a.refNo}`} hover selected={open?.typeCode === a.typeCode && open.refNo === a.refNo} sx={{ cursor: 'pointer' }} onClick={() => setOpen(open?.typeCode === a.typeCode && open.refNo === a.refNo ? null : { typeCode: a.typeCode, refNo: a.refNo })}>
                <TableCell>{a.type ?? `Type ${a.typeCode}`}</TableCell><TableCell>{a.refNoText ?? a.refNo}</TableCell>
                <TableCell>{fmtDate(a.opened) ?? '—'}</TableCell><TableCell>{a.statusName}</TableCell><TableCell>{fmtDate(a.lastActivity) ?? '—'}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{peso(a.minimumBalance)}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{a.held > 0 ? peso(a.held) : '—'}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{peso(a.balance)}</TableCell>
                <TableCell align="right">
                  {teller && a.status === 15 && <Button size="small" onClick={(e) => { e.stopPropagation(); setPaying(a) }}>Post payment</Button>}
                  {teller && a.status === 15 && a.balance - a.held > 0 && <Button size="small" onClick={(e) => { e.stopPropagation(); setWithdrawing(a) }}>Withdraw</Button>}
                </TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell colSpan={7} sx={{ fontWeight: 700 }}>Total share capital</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{peso(data.totalBalance)}</TableCell>
              <TableCell />
            </TableRow>
          </TableBody>
        </Table>
      </TableCard>
      {open && <ShareHistory key={`${open.typeCode}-${open.refNo}`} id={id} typeCode={open.typeCode} refNo={open.refNo} onClose={() => setOpen(null)} />}
      {paying && <SharePaymentDialog id={id} account={paying} onClose={() => setPaying(null)} />}
      {withdrawing && <ShareWithdrawalDialog id={id} account={withdrawing} onClose={() => setWithdrawing(null)} />}
    </>
  )
}

function SharePaymentDialog({ id, account, onClose }: { id: Id; account: ShareAccount; onClose: () => void }) {
  const qc = useQueryClient()
  const [amount, setAmount] = useState('')
  const [receiptNo, setReceiptNo] = useState('')
  const [explanation, setExplanation] = useState('')
  const [receipt, setReceipt] = useState<SharePaymentReceipt | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [askConfirm, setAskConfirm] = useState(false)
  const [cheques, setCheques] = useState<ChequeRow[]>([])
  const value = Number(amount)
  const valid = amount.trim() !== '' && Number.isFinite(value) && value > 0 && chequeSum(cheques) <= value + 0.001

  const submit = async (confirmDuplicate: boolean) => {
    setBusy(true); setError(null)
    try {
      const r = await postSharePayment({ clientId: id.clientId, typeCode: account.typeCode, refNo: account.refNo, amount: value, explanation, receiptNo, confirmDuplicate, cheques: chequesToApi(cheques) })
      setReceipt(r)
      qc.invalidateQueries({ queryKey: ['member-shares'] })
      qc.invalidateQueries({ queryKey: ['member-share-history'] })
    } catch (e) {
      const msg = (e as Error).message
      setError(msg)
      setAskConfirm(msg.includes('confirm it'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{receipt ? 'Payment posted' : 'Post a cash payment'}</DialogTitle>
      <DialogContent>
        {!receipt && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <Box sx={{ fontSize: 13 }}>{account.type ?? 'Share capital'} {account.refNoText ?? account.refNo} · balance {peso(account.balance)}</Box>
            <MoneyField label="Amount" autoFocus value={amount} onValueChange={(v) => setAmount(v)} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
            <ChequeFields rows={cheques} onChange={(r) => { setCheques(r); setAskConfirm(false); setError(null) }} total={valid || value > 0 ? value : 0} />
            <TextField label="Receipt number (optional)" value={receiptNo} onChange={(e) => setReceiptNo(e.target.value)} helperText="Up to 8 letters or digits. Left empty, the system numbers it." />
            <TextField label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder="SC" />
            <Box sx={{ fontSize: 12 }}>Cash and cheques go into your own drawer and are posted to the ledger at once.</Box>
          </Box>
        )}
        {receipt && (
          <Box sx={{ display: 'grid', gap: 0.5, pt: 1, fontSize: 14 }}>
            <Box>Receipt <strong>{receipt.documentNo}</strong> (control {receipt.controlNo}) dated {receipt.date}</Box>
            <Box>{receipt.clientName}</Box>
            <Box>{receipt.account}</Box>
            <Box>Paid <strong>{peso(receipt.amount)}</strong>, new balance <strong>{peso(receipt.balanceAfter)}</strong></Box>
            <Box>Received by {receipt.cashier}</Box>
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
              : <Button variant="contained" disabled={busy || !valid} onClick={() => submit(false)}>Post payment</Button>}
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}

function ShareWithdrawalDialog({ id, account, onClose }: { id: Id; account: ShareAccount; onClose: () => void }) {
  const qc = useQueryClient()
  const [amount, setAmount] = useState('')
  const [explanation, setExplanation] = useState('')
  const [done, setDone] = useState<ShareWithdrawalOut | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const value = Number(amount)
  const valid = amount.trim() !== '' && Number.isFinite(value) && value > 0

  const submit = async () => {
    setBusy(true); setError(null)
    try {
      setDone(await postShareWithdrawal({ clientId: id.clientId, typeCode: account.typeCode, refNo: account.refNo, amount: value, explanation }))
      qc.invalidateQueries({ queryKey: ['loan-releases'] })
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onClose={busy ? undefined : onClose} maxWidth="xs" fullWidth>
      <DialogTitle>{done ? 'Withdrawal voucher created' : 'Withdraw share capital'}</DialogTitle>
      <DialogContent>
        {!done && (
          <Box sx={{ display: 'grid', gap: 1.5, pt: 1 }}>
            <Box sx={{ fontSize: 13 }}>{account.type ?? 'Share capital'} {account.refNoText ?? account.refNo} · balance {peso(account.balance)}{account.held ? ` · on hold ${peso(account.held)} (co-maker) · available ${peso(account.balance - account.held)}` : ''}</Box>
            <MoneyField label="Amount" autoFocus value={amount} onValueChange={(v) => setAmount(v)} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
            <TextField label="Explanation (optional)" value={explanation} onChange={(e) => setExplanation(e.target.value)} placeholder="SC WITHDRAWAL" />
            <Box sx={{ fontSize: 12 }}>This creates a Cash Voucher that credits the member's vouchers-payable account. Nothing is posted until two other people approve and post it on the Disburse tab.</Box>
          </Box>
        )}
        {done && (
          <Box sx={{ display: 'grid', gap: 0.5, pt: 1, fontSize: 14 }}>
            <Box>Voucher <strong>{done.documentNo}</strong> (control {done.controlNo}) for {done.clientName}</Box>
            <Box>Withdrawal <strong>{peso(done.amount)}</strong>; capital left after it posts <strong>{peso(done.availableAfter)}</strong></Box>
            <Box>It now waits for approval and posting on the Disburse tab.</Box>
          </Box>
        )}
        {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      </DialogContent>
      <DialogActions>
        {done ? <Button onClick={onClose}>Close</Button> : (
          <>
            <Button onClick={onClose} disabled={busy}>Cancel</Button>
            <Button variant="contained" disabled={busy || !valid} onClick={submit}>Create voucher</Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  )
}

function ShareHistory({ id, typeCode, refNo, onClose }: { id: Id; typeCode: number; refNo: number; onClose: () => void }) {
  const [page, setPage] = useState(0)
  const [tx, setTx] = useState<TxKey | null>(null)
  const size = 25
  const { data, error } = useQuery({ queryKey: ['member-share-history', id, typeCode, refNo, page], queryFn: () => getShareHistory(id.branchCode, id.clientId, typeCode, refNo, page, size) })
  return (
    <DetailPanel
      title={data ? `${data.account.type ?? 'Share capital'} ${data.account.refNoText ?? ''}` : 'Share capital'} onClose={onClose}
      footer={<>
        {data && <Box sx={{ flex: 1, fontSize: 13 }}>{data.totalEntries} entries</Box>}
        <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</Button>
        <Button disabled={!data || (page + 1) * size >= data.totalEntries} onClick={() => setPage(page + 1)}>Older</Button>
      </>}
    >
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {data && (
          <Table size="small" aria-label="Share capital entries">
            <TableHead>
              <TableRow><TableCell>Date</TableCell><TableCell>TR</TableCell><TableCell>Document</TableCell><TableCell>Cheque</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell><TableCell align="right">Balance</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {data.entries.map((e, i) => (
                <TableRow key={`${e.documentNo}-${i}`} {...txRowProps(e, setTx)}>
                  <TableCell>{fmtDate(e.date) ?? '—'}</TableCell><TableCell>{e.transaction ?? `Code ${e.transactionCode}`}</TableCell><TableCell>{e.documentNo ?? '—'}</TableCell>
                  <TableCell>{e.chequeNo ? `${e.chequeNo} · ${e.chequeCleared ? (e.clearedOn ? `cleared ${fmtDate(e.clearedOn)}` : 'cleared') : 'not cleared'}` : ''}</TableCell>
                  <TableCell align="right" sx={{ color: 'error.main', fontVariantNumeric: 'tabular-nums' }}>{e.amount < 0 ? peso(-e.amount) : ''}</TableCell><TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{e.amount > 0 ? peso(e.amount) : ''}</TableCell>
                  <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{peso(e.balanceAfter)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      {tx && <TransactionDialog id={id} tx={tx} onClose={() => setTx(null)} />}
    </DetailPanel>
  )
}

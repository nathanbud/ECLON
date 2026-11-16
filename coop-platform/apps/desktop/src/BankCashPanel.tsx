import { Alert, Box, Button, MenuItem, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { getBankCashBanks, getFoHandoverDrawers, getFoHandoverMine, postBankDeposit, postBankWithdrawal, type FoSetup } from './api'
import { CountGrid, countTotal, toNumbers, type Counts } from './CountGrid'
import { money } from './FoTransactionForm'
import MoneyField from './MoneyField'
import { glass } from './glass'

/**
 * The head cashier moves cash between the vault and a bank account (Bank Deposit and Bank Withdrawal of the old system). The count of notes and coins
 * sets the amount, so the two cannot disagree. It posts at once.
 */
export function BankCashPanel({ setup }: { setup: FoSetup }) {
  const qc = useQueryClient()
  const mine = useQuery({ queryKey: ['fo-handover-mine'], queryFn: getFoHandoverMine, refetchInterval: 30_000 })
  const banks = useQuery({ queryKey: ['bank-cash-banks'], queryFn: getBankCashBanks, enabled: mine.data?.headCashier === true })
  const drawers = useQuery({ queryKey: ['fo-handover-drawers'], queryFn: getFoHandoverDrawers, enabled: mine.data?.headCashier === true })
  const vaultCheques = drawers.data?.find((d) => d.event === 50)?.cheques ?? 0
  const [chequeText, setChequeText] = useState('')
  const chequeTotal = Number(chequeText.replace(/,/g, '')) || 0
  const [bankId, setBankId] = useState<number | ''>('')
  const [counts, setCounts] = useState<Counts>({})
  const [remarks, setRemarks] = useState('')
  const [note, setNote] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const total = countTotal(setup, counts)
  const run = useMutation({
    mutationFn: (kind: 'deposit' | 'withdraw' | 'cheques') => {
      if (kind === 'cheques') return postBankDeposit({ bankId: Number(bankId), amount: chequeTotal, counts: {}, remarks: remarks.trim() || undefined, cheques: true })
      const body = { bankId: Number(bankId), amount: total, counts: toNumbers(counts), remarks: remarks.trim() || undefined }
      return kind === 'deposit' ? postBankDeposit(body) : postBankWithdrawal(body)
    },
    onSuccess: (r, kind) => {
      setError(null); setCounts({}); setRemarks(''); setChequeText('')
      setNote(`${kind === 'withdraw' ? 'Withdrawn from' : 'Deposited to'} ${r.bank}: ${money(r.amount)}${kind === 'cheques' ? ' in cheques' : ''} (document ${r.documentNo}). The vault now holds ${money(r.vaultAfter)}${kind === 'cheques' ? ' in cheques' : ''}.`)
      void qc.invalidateQueries({ queryKey: ['fo-handover-drawers'] })
      void qc.invalidateQueries({ queryKey: ['fo-handover-mine'] })
    },
    onError: (e) => setError((e as Error).message),
  })
  if (mine.data?.headCashier !== true) return null
  const ready = bankId !== '' && total > 0 && !run.isPending
  const chequesReady = bankId !== '' && chequeTotal > 0 && chequeTotal <= vaultCheques + 0.001 && !run.isPending
  return (
    <Box sx={(t) => ({ p: 1.5, mt: 1.5, ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Bank deposit and withdrawal (vault)</Typography>
      {note && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNote(null)}>{note}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      <Box sx={{ display: 'flex', gap: 1.5, mb: 1.5, flexWrap: 'wrap' }}>
        <TextField select size="small" label="Bank account" value={bankId} onChange={(e) => setBankId(Number(e.target.value))} sx={{ minWidth: 260 }}>
          {(banks.data ?? []).map((b) => <MenuItem key={b.bankId} value={b.bankId}>{b.name} · {b.ledgerAccount}</MenuItem>)}
        </TextField>
        <TextField size="small" label="Note (optional)" value={remarks} onChange={(e) => setRemarks(e.target.value)} sx={{ flex: 1, minWidth: 200 }} />
      </Box>
      <CountGrid setup={setup} counts={counts} onChange={setCounts} />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 1.5 }}>
        <Typography sx={{ fontSize: 13, fontWeight: 700 }}>Total {money(total)}</Typography>
        <Box sx={{ flex: 1 }} />
        <Button variant="outlined" disabled={!ready} onClick={() => run.mutate('withdraw')}>Withdraw from bank to vault</Button>
        <Button variant="contained" disabled={!ready} onClick={() => run.mutate('deposit')}>Deposit vault cash to bank</Button>
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mt: 2, pt: 1.5, borderTop: 1, borderColor: 'divider', flexWrap: 'wrap' }}>
        <MoneyField size="small" label="Cheques to deposit (total)" value={chequeText} onValueChange={(v) => setChequeText(v)} sx={{ width: 240 }}
          helperText={`The vault holds ${money(vaultCheques)} in cheques.`} />
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" disabled={!chequesReady} onClick={() => run.mutate('cheques')}>Deposit vault cheques to bank</Button>
      </Box>
    </Box>
  )
}

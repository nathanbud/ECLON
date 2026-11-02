import { Alert, Box, Button, Chip, InputAdornment, MenuItem, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { getLoans, getSavings, getShares, type LoanItem, type SavingsAccount, type ShareAccount } from './api'
import type { Id } from './clients'
import { glass } from './glass'
import { LoanPaymentDialog } from './LoansTab'
import MoneyField from './MoneyField'
import { SavingsCashDialog } from './profile/Savings'
import { SharePaymentDialog, ShareWithdrawalDialog } from './profile/ShareCapital'
import { money } from './ui/format'

export type TellerKind = 'savings' | 'share' | 'loan'
export type TellerMode = 'deposit' | 'withdraw' | 'payment'
export interface TellerPreset { kind: TellerKind; mode: TellerMode; seq: number }

const MODES: Record<TellerKind, { value: TellerMode; label: string }[]> = {
  savings: [{ value: 'deposit', label: 'Deposit' }, { value: 'withdraw', label: 'Withdraw' }],
  share: [{ value: 'payment', label: 'Payment' }, { value: 'withdraw', label: 'Withdraw' }],
  loan: [{ value: 'payment', label: 'Payment' }],
}
const QUICK = [500, 1000, 5000, 10000]

interface Option { key: string; kind: TellerKind; label: string; sub: string; savings?: SavingsAccount; share?: ShareAccount; loan?: LoanItem }

/**
 * One panel for the teller's cash transactions on a member: choose the account, what to do with it, the amount (quick buttons for the usual ones) and a remark, then Post.
 * Posting opens the same confirmation as before with everything filled in, because that is where the receipt number, cheques, holds, limits and supervisor approvals are handled.
 */
export default function TellerTransaction({ id, preset }: { id: Id; preset: TellerPreset | null }) {
  const savings = useQuery({ queryKey: ['member-savings', id], queryFn: () => getSavings(id.branchCode, id.clientId), staleTime: 60_000 })
  const shares = useQuery({ queryKey: ['member-shares', id], queryFn: () => getShares(id.branchCode, id.clientId), staleTime: 60_000 })
  const loans = useQuery({ queryKey: ['member-loans', id.branchCode, id.clientId, false], queryFn: () => getLoans(id.branchCode, id.clientId, false), staleTime: 60_000 })

  const options = useMemo<Option[]>(() => [
    ...(savings.data?.accounts ?? []).filter((a) => a.status === 15).map((a): Option => ({ key: `s-${a.typeCode}-${a.refNo}`, kind: 'savings', label: `${a.type ?? 'Savings'} ${a.refNoText ?? a.refNo}`, sub: `Available ${money(a.available)}`, savings: a })),
    ...(shares.data?.accounts ?? []).filter((a) => a.status === 15).map((a): Option => ({ key: `c-${a.typeCode}-${a.refNo}`, kind: 'share', label: `${a.type ?? 'Share capital'} ${a.refNoText ?? a.refNo}`, sub: `Balance ${money(a.balance)}`, share: a })),
    ...(loans.data?.loans ?? []).filter((l) => l.outstanding > 0 && l.status !== 13).map((l): Option => ({ key: `l-${l.typeCode}-${l.refNo}`, kind: 'loan', label: `${l.type ?? 'Loan'} #${l.refNo}`, sub: `Outstanding ${money(l.outstanding)}`, loan: l })),
  ], [savings.data, shares.data, loans.data])

  const [optKey, setOptKey] = useState('')
  const [mode, setMode] = useState<TellerMode>('deposit')
  const [amount, setAmount] = useState('')
  const [remarks, setRemarks] = useState('')
  const [dialog, setDialog] = useState<null | { option: Option; mode: TellerMode; amount: string; remarks: string }>(null)

  const option = options.find((o) => o.key === optKey) ?? options[0]
  const modes = option ? MODES[option.kind] : []
  const activeMode = modes.some((m) => m.value === mode) ? mode : modes[0]?.value

  // A quick action (Deposit, Withdraw, Share capital, Loan payment) picks the first account of that kind and the matching action.
  useEffect(() => {
    if (!preset) return
    const first = options.find((o) => o.kind === preset.kind)
    if (first) setOptKey(first.key)
    setMode(preset.mode)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [preset?.seq])

  const value = Number(amount)
  const ok = !!option && !!activeMode && Number.isFinite(value) && value > 0
  const loading = savings.isLoading || shares.isLoading || loans.isLoading

  return (
    <Box component="section" aria-label="Transaction" sx={(t) => ({ p: 1.5, mb: 1.25, ...glass(t) })}>
      <Typography component="h2" sx={{ fontSize: 14.5, fontWeight: 700, mb: 1 }}>Transaction</Typography>
      {!loading && options.length === 0 && <Alert severity="info">This member has no active account to post to.</Alert>}
      {options.length > 0 && (
        <Box sx={{ display: 'grid', gap: 1.25 }}>
          <TextField select size="small" label="Account" value={option?.key ?? ''} onChange={(e) => setOptKey(e.target.value)}>
            {options.map((o) => <MenuItem key={o.key} value={o.key}><Box sx={{ display: 'flex', justifyContent: 'space-between', width: '100%', gap: 2 }}><span>{o.label}</span><Box component="span" sx={{ color: 'text.secondary', fontSize: 12.5 }}>{o.sub}</Box></Box></MenuItem>)}
          </TextField>
          <ToggleButtonGroup exclusive fullWidth size="small" value={activeMode} aria-label="What to do" onChange={(_, v) => { if (v) setMode(v) }}>
            {modes.map((m) => <ToggleButton key={m.value} value={m.value} sx={{ textTransform: 'none' }}>{m.label}</ToggleButton>)}
          </ToggleButtonGroup>
          <MoneyField label="Amount" value={amount} onValueChange={setAmount} placeholder="0.00" slotProps={{ input: { startAdornment: <InputAdornment position="start">₱</InputAdornment> } }} />
          <Box sx={{ display: 'flex', gap: 0.75, flexWrap: 'wrap' }}>
            {QUICK.map((q) => <Chip key={q} label={`₱ ${q.toLocaleString()}`} onClick={() => setAmount(q.toFixed(2))} variant="outlined" />)}
            <Chip label="Clear" onClick={() => setAmount('')} variant="outlined" />
          </Box>
          <TextField size="small" label="Remarks (optional)" value={remarks} onChange={(e) => setRemarks(e.target.value)} placeholder="e.g. monthly savings" />
          <Button variant="contained" disabled={!ok} onClick={() => option && activeMode && setDialog({ option, mode: activeMode, amount, remarks })}>
            Post {activeMode === 'withdraw' ? 'withdrawal' : activeMode === 'deposit' ? 'deposit' : 'payment'}{ok ? ` of ${money(value)}` : ''}
          </Button>
        </Box>
      )}
      {dialog?.option.savings && (
        <SavingsCashDialog id={id} account={dialog.option.savings} mode={dialog.mode === 'withdraw' ? 'withdraw' : 'deposit'} initialAmount={dialog.amount} initialExplanation={dialog.remarks}
          onClose={() => { setDialog(null); setAmount(''); setRemarks('') }} />
      )}
      {dialog?.option.share && dialog.mode !== 'withdraw' && (
        <SharePaymentDialog id={id} account={dialog.option.share} initialAmount={dialog.amount} initialExplanation={dialog.remarks} onClose={() => { setDialog(null); setAmount(''); setRemarks('') }} />
      )}
      {dialog?.option.share && dialog.mode === 'withdraw' && (
        <ShareWithdrawalDialog id={id} account={dialog.option.share} initialAmount={dialog.amount} initialExplanation={dialog.remarks} onClose={() => { setDialog(null); setAmount(''); setRemarks('') }} />
      )}
      {dialog?.option.loan && (
        <LoanPaymentDialog clientId={id.clientId} loan={dialog.option.loan} initialAmount={dialog.amount} initialExplanation={dialog.remarks} onClose={() => { setDialog(null); setAmount(''); setRemarks('') }} />
      )}
    </Box>
  )
}

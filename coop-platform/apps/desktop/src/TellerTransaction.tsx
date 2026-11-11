import { Alert, Box, Button, InputAdornment, MenuItem, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material'
import { FiCheckCircle, FiRepeat } from 'react-icons/fi'
import { IconChip } from './appleUi'
import { tileFill } from './theme'
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
/** A small heading over a part of the form. */
const Label = ({ children }: { children: string }) => <Typography sx={{ fontSize: 12, fontWeight: 600, mt: 0.5 }} color="text.secondary">{children}</Typography>

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
    <Box component="section" aria-label="Transaction" sx={(t) => ({ p: 1.75, mb: 1.25, ...glass(t) })}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.25 }}>
        <IconChip icon={<FiRepeat />} color={tileFill.blue} />
        <Typography component="h2" sx={{ fontSize: 15, fontWeight: 600, letterSpacing: '-0.01em' }}>Transaction</Typography>
      </Box>
      {!loading && options.length === 0 && <Alert severity="info">This member has no active account to post to.</Alert>}
      {options.length > 0 && (
        <Box sx={{ display: 'grid', gap: 0.75 }}>
          <Label>Account</Label>
          <TextField select size="small" hiddenLabel value={option?.key ?? ''} onChange={(e) => setOptKey(e.target.value)} slotProps={{ htmlInput: { 'aria-label': 'Account' } }}>
            {options.map((o) => <MenuItem key={o.key} value={o.key}><Box sx={{ display: 'flex', justifyContent: 'space-between', width: '100%', gap: 2 }}><span>{o.label}</span><Box component="span" sx={{ color: 'text.secondary', fontSize: 12 }}>{o.sub}</Box></Box></MenuItem>)}
          </TextField>
          <Label>Transaction type</Label>
          <ToggleButtonGroup exclusive fullWidth size="small" value={activeMode} aria-label="What to do" onChange={(_, v) => { if (v) setMode(v) }}>
            {modes.map((m) => <ToggleButton key={m.value} value={m.value} sx={{ textTransform: 'none' }}>{m.label}</ToggleButton>)}
          </ToggleButtonGroup>
          <Label>Amount</Label>
          <MoneyField
            hiddenLabel value={amount} onValueChange={setAmount} placeholder="0.00"
            sx={{ '& .MuiOutlinedInput-input': { fontSize: 20, fontWeight: 600, letterSpacing: '-0.01em' } }}
            slotProps={{ input: { startAdornment: <InputAdornment position="start">₱</InputAdornment> }, htmlInput: { 'aria-label': 'Amount' } }}
          />
          <Box sx={{ display: 'grid', gap: 0.75, gridTemplateColumns: `repeat(${QUICK.length + 1}, minmax(0, 1fr))` }}>
            {QUICK.map((q) => <Button key={q} size="small" variant="outlined" onClick={() => setAmount(q.toFixed(2))} sx={{ minWidth: 0, px: 0.5, fontVariantNumeric: 'tabular-nums' }}>₱{q.toLocaleString()}</Button>)}
            <Button size="small" variant="outlined" onClick={() => setAmount('')} sx={{ minWidth: 0, px: 0.5 }}>Clear</Button>
          </Box>
          <Label>Remarks (optional)</Label>
          <TextField size="small" hiddenLabel value={remarks} onChange={(e) => setRemarks(e.target.value)} placeholder="e.g. monthly savings" slotProps={{ htmlInput: { 'aria-label': 'Remarks (optional)' } }} />
          <Button variant="contained" size="large" startIcon={<FiCheckCircle />} disabled={!ok} onClick={() => option && activeMode && setDialog({ option, mode: activeMode, amount, remarks })} sx={{ mt: 0.75 }}>
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

import { Box, Checkbox, Tab, Tabs, Typography } from '@mui/material'
import { useState } from 'react'
import type { LoanAppTemplate } from './api'
import MoneyField from './MoneyField'
import { money } from './ui/format'

/** The amounts typed per deduction line (by the line's id) and the earlier loans paid off from the proceeds (by "type/ref"). */
export type DeductionAmounts = Record<number, string>
export type Payoffs = Record<string, { principal: string; interest: string; penalty: string }>

export function deductionTotals(ded: DeductionAmounts, paid: Payoffs, amount: number) {
  const paidTotal = Object.values(paid).reduce((s, v) => s + (Number(v.principal) || 0) + (Number(v.interest) || 0) + (Number(v.penalty) || 0), 0)
  const total = Object.values(ded).reduce((s, v) => s + (Number(v) || 0), 0) + paidTotal
  return { total, net: amount - total }
}

/** What the server takes: the lines with an amount, and the pay-offs. */
export function deductionInput(ded: DeductionAmounts, paid: Payoffs) {
  return {
    deductions: Object.entries(ded).filter(([, v]) => Number(v) > 0).map(([id, v]) => ({ id: Number(id), amount: Number(v) })),
    payoffs: Object.entries(paid).map(([k, v]) => {
      const [typeCode, refNo] = k.split('/').map(Number)
      return { typeCode, refNo, principal: Number(v.principal) || 0, interest: Number(v.interest) || 0, penalty: Number(v.penalty) || 0 }
    }),
  }
}

/**
 * The Deduction step's inputs, in one group of two tabs: Deductions (the loan type's deduction lines with the amounts the clerk types) and Pay off
 * (earlier loans paid off from the proceeds). Each tab says what it takes out; the total and the net proceeds show below, whichever tab is open.
 * Used when one person encodes both steps, and by the Deduction step of an application saved without them.
 */
export default function LoanDeductionFields({ t, amount, ded, setDed, paid, setPaid }: {
  t: Pick<LoanAppTemplate, 'payoffs' | 'deductions'>; amount: number
  ded: DeductionAmounts; setDed: (d: DeductionAmounts) => void; paid: Payoffs; setPaid: (p: Payoffs) => void
}) {
  const [tab, setTab] = useState(0)
  const { total, net } = deductionTotals(ded, paid, amount)
  const linesTotal = deductionTotals(ded, {}, amount).total
  const paidTotal = total - linesTotal
  const label = (name: string, sum: number) => (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'baseline', gap: 0.75 }}>
      {name}{sum > 0 && <Box component="span" sx={{ fontSize: 12, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>{money(sum)}</Box>}
    </Box>
  )

  const deductions = (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 1.25 }}>
      {t.deductions.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">This loan type has no deduction lines.</Typography>}
      {t.deductions.map((d) => (
        <MoneyField key={d.id} size="small" label={d.description} value={ded[d.id] ?? ''} onValueChange={(v) => setDed({ ...ded, [d.id]: v })}
          error={d.needsMemberAccount && !d.hasMemberAccount} helperText={d.needsMemberAccount && !d.hasMemberAccount ? 'The member has no account for this' : undefined} />
      ))}
    </Box>
  )

  const payOff = (
    <Box sx={{ display: 'grid', gap: 1 }}>
      {t.payoffs.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">The member has no earlier loans to pay off.</Typography>}
      {t.payoffs.length > 0 && <Typography sx={{ fontSize: 12 }} color="text.secondary">Tick a loan to pay it off from the proceeds.</Typography>}
      {t.payoffs.map((p) => {
        const k = `${p.typeCode}/${p.refNo}`
        const on = k in paid
        return (
          <Box key={k} sx={{ display: 'grid', gridTemplateColumns: 'auto 1.5fr 1fr 1fr 1fr', gap: 1, alignItems: 'center' }}>
            <Checkbox size="small" checked={on} slotProps={{ input: { 'aria-label': `Pay off ${p.typeName ?? p.typeCode} ${p.refNo}` } }}
              onChange={(_, c) => { const next = { ...paid }; if (c) next[k] = { principal: String(p.outstanding), interest: String(p.interestDue), penalty: '' }; else delete next[k]; setPaid(next) }} />
            <Typography sx={{ fontSize: 13 }}>{p.typeName ?? `Loan ${p.typeCode}`} #{p.refNo} · owes {money(p.outstanding)}{p.daysPastDue > 0 ? ` · ${p.daysPastDue} days late` : ''}</Typography>
            <MoneyField size="small" label="Principal" disabled={!on} value={paid[k]?.principal ?? ''} onValueChange={(v) => setPaid({ ...paid, [k]: { ...paid[k], principal: v } })} />
            <MoneyField size="small" label="Interest" disabled={!on} value={paid[k]?.interest ?? ''} onValueChange={(v) => setPaid({ ...paid, [k]: { ...paid[k], interest: v } })} />
            <MoneyField size="small" label="Penalty (typed)" disabled={!on} value={paid[k]?.penalty ?? ''} onValueChange={(v) => setPaid({ ...paid, [k]: { ...paid[k], penalty: v } })} />
          </Box>
        )
      })}
    </Box>
  )

  return (
    <>
      <Box component="section" aria-label="Deductions and pay off" sx={{ border: 1, borderColor: 'divider', borderRadius: '8px' }}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons={false}
          sx={{ borderBottom: 1, borderColor: 'divider', minHeight: 40, '& .MuiTab-root': { minHeight: 40, textTransform: 'none', fontSize: 13 } }}>
          <Tab label={label('Deductions', linesTotal)} id="deduction-tab-0" aria-controls="deduction-panel-0" />
          <Tab label={label(t.payoffs.length > 0 ? `Pay off (${t.payoffs.length})` : 'Pay off', paidTotal)} id="deduction-tab-1" aria-controls="deduction-panel-1" />
        </Tabs>
        <Box role="tabpanel" id={`deduction-panel-${tab}`} aria-labelledby={`deduction-tab-${tab}`} sx={{ p: 1.5 }}>{tab === 0 ? deductions : payOff}</Box>
      </Box>
      <Typography sx={{ fontVariantNumeric: 'tabular-nums' }}>Deductions {money(total)} · Net proceeds <strong>{money(net)}</strong></Typography>
    </>
  )
}

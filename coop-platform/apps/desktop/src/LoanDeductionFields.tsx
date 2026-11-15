import { Box, Checkbox, Typography } from '@mui/material'
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
 * The Deduction step's inputs: earlier loans to pay off from the proceeds, and the loan type's deduction lines with the amounts the clerk types.
 * Used when one person encodes both steps, and by the Deduction step of an application saved without them.
 */
export default function LoanDeductionFields({ t, amount, ded, setDed, paid, setPaid }: {
  t: Pick<LoanAppTemplate, 'payoffs' | 'deductions'>; amount: number
  ded: DeductionAmounts; setDed: (d: DeductionAmounts) => void; paid: Payoffs; setPaid: (p: Payoffs) => void
}) {
  const { total, net } = deductionTotals(ded, paid, amount)
  return (
    <>
      {t.payoffs.length > 0 && (
        <>
          <Typography sx={{ fontSize: 12, fontWeight: 700 }} color="text.secondary">Pay off earlier loans from the proceeds</Typography>
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
        </>
      )}
      <Typography sx={{ fontSize: 12, fontWeight: 700 }} color="text.secondary">Deductions (amounts typed by the clerk)</Typography>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 1.25 }}>
        {t.deductions.map((d) => (
          <MoneyField key={d.id} size="small" label={d.description} value={ded[d.id] ?? ''} onValueChange={(v) => setDed({ ...ded, [d.id]: v })}
            error={d.needsMemberAccount && !d.hasMemberAccount} helperText={d.needsMemberAccount && !d.hasMemberAccount ? 'The member has no account for this' : undefined} />
        ))}
      </Box>
      <Typography sx={{ fontVariantNumeric: 'tabular-nums' }}>Deductions {money(total)} · Net proceeds <strong>{money(net)}</strong></Typography>
    </>
  )
}

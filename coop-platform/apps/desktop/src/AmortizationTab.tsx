import { Alert, Box, Button, MenuItem, Skeleton, TextField, Typography } from '@mui/material'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { getAmortKinds, previewAmortization, type AmortKind, type AmortTry, type LoanSetupLists } from './api'
import { Dot, listTable, SectionLabel, Surface } from './appleUi'
import MoneyField from './MoneyField'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const day = (iso: string) => new Date(iso + 'T00:00:00').toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
const today = () => new Date().toLocaleDateString('en-CA')

// the payment modes each kind can schedule so far (paymentmode ids)
const MODES: Record<number, number[]> = { 1: [4, 5, 9], 3: [4, 5, 6, 9] }

function KindRow({ k, first }: { k: AmortKind; first: boolean }) {
  return (
    <Box sx={(t) => ({ px: 2, py: 1.5, borderTop: first ? 0 : `0.5px solid ${t.palette.divider}` })}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 0.5 }}>
        <Typography sx={{ fontSize: 15, fontWeight: 600, flex: 1 }}>{k.name}</Typography>
        {k.supported ? <Dot color="success">Mapped</Dot> : <Dot color="grey">Not mapped</Dot>}
      </Box>
      <Typography color="text.secondary" sx={{ fontSize: 12.5, lineHeight: 1.45 }}>{k.formula}</Typography>
      <Typography color="text.secondary" sx={{ fontSize: 12, mt: 0.75 }}>
        {k.loans.toLocaleString()} {k.loans === 1 ? 'loan' : 'loans'}
        {k.types.length > 0 ? ` · loan types: ${k.types.join(', ')}` : ' · no loan type uses it'}
      </Typography>
    </Box>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={(t) => ({ p: 1.25, borderRadius: '10px', bgcolor: t.palette.mode === 'dark' ? 'rgba(255,255,255,0.07)' : 'rgba(0,0,0,0.04)' })}>
      <Typography color="text.secondary" sx={{ fontSize: 11.5 }}>{label}</Typography>
      <Typography sx={{ fontSize: 17, fontWeight: 600, fontVariantNumeric: 'tabular-nums', letterSpacing: '-0.01em' }}>{value}</Typography>
    </Box>
  )
}

/** The amortization kinds explained, and a calculator that gives the schedule the loan application would. Nothing is saved. */
export default function AmortizationTab({ lists }: { lists: LoanSetupLists | undefined }) {
  const kinds = useQuery({ queryKey: ['amort-kinds'], queryFn: getAmortKinds, staleTime: 60_000 })
  const mapped = useMemo(() => (kinds.data ?? []).filter((k) => k.supported), [kinds.data])

  const [kind, setKind] = useState(3)
  const [amount, setAmount] = useState('100000.00')
  const [term, setTerm] = useState('12')
  const [unit, setUnit] = useState<'months' | 'years'>('months')
  const [mode, setMode] = useState(4)
  const [rate, setRate] = useState('10')
  const [dayCount, setDayCount] = useState(1)
  const [release, setRelease] = useState(today())
  const [all, setAll] = useState(false)

  const modes = (lists?.paymentModes ?? []).filter((m) => (MODES[kind] ?? []).includes(m.id))
  const termMonths = Number(term) * (unit === 'years' ? 12 : 1)
  const valid = Number(amount) > 0 && Number.isInteger(Number(term)) && termMonths >= 1 && termMonths <= 600 && rate !== '' && Number(rate) >= 0 && Number(rate) <= 100 && (MODES[kind] ?? []).includes(mode)
  const wanted: AmortTry = { kind, amount: Number(amount), termMonths, paymentMode: mode, ratePercent: Number(rate), release: release || null, dayCount: kind === 3 ? dayCount : null }

  // wait a moment after typing so the server is asked once, not on every key
  const [asked, setAsked] = useState(wanted)
  const signature = JSON.stringify(wanted)
  useEffect(() => { const h = window.setTimeout(() => setAsked(JSON.parse(signature) as AmortTry), 300); return () => window.clearTimeout(h) }, [signature])

  const preview = useQuery({ queryKey: ['amort-preview', asked], queryFn: () => previewAmortization(asked), enabled: valid, placeholderData: keepPreviousData, retry: false })
  const rows = preview.data?.rows.slice(1) ?? []
  const shown = all || rows.length <= 7 ? rows : [...rows.slice(0, 5), ...rows.slice(-1)]

  return (
    <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) minmax(0, 1.05fr)' }, alignItems: 'start' }}>
      <Box>
        <SectionLabel>Kinds</SectionLabel>
        <Surface label="Amortization kinds">
          {kinds.isLoading && <Skeleton variant="rounded" height={240} />}
          {kinds.data?.map((k, i) => <KindRow key={k.id} k={k} first={i === 0} />)}
        </Surface>
        <Typography color="text.secondary" sx={{ fontSize: 12.5, mt: 1.25, px: 0.5 }}>
          The formulas are part of the program and are checked against thousands of real schedules, so they cannot be edited here. Pick the kind for a loan type in its Term section.
        </Typography>
      </Box>

      <Box>
        <SectionLabel>Try a schedule</SectionLabel>
        <Surface label="Schedule calculator">
          <Box sx={{ p: 2 }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 1.25 }}>
              <TextField select size="small" label="Kind" value={kind} onChange={(e) => { const k = Number(e.target.value); setKind(k); if (!(MODES[k] ?? []).includes(mode)) setMode(4) }}>
                {mapped.map((k) => <MenuItem key={k.id} value={k.id}>{k.name}</MenuItem>)}
              </TextField>
              <MoneyField size="small" label="Amount" value={amount} onValueChange={setAmount} sx={{ gridColumn: 'span 2' }} />
              <TextField size="small" type="number" label="Term" value={term} onChange={(e) => setTerm(e.target.value)} slotProps={{ htmlInput: { min: 1 } }} />
              <TextField select size="small" label="Counted in" value={unit} onChange={(e) => setUnit(e.target.value as 'months' | 'years')}>
                <MenuItem value="months">Months</MenuItem><MenuItem value="years">Years</MenuItem>
              </TextField>
              <TextField select size="small" label="Paid" value={mode} onChange={(e) => setMode(Number(e.target.value))}>
                {modes.map((m) => <MenuItem key={m.id} value={m.id}>{m.name}</MenuItem>)}
              </TextField>
              <TextField size="small" type="number" label="Interest (a year)" value={rate} onChange={(e) => setRate(e.target.value)} slotProps={{ htmlInput: { min: 0, max: 100, step: 0.01 }, input: { endAdornment: <Typography color="text.secondary" sx={{ fontSize: 13 }}>%</Typography> } }} />
              <TextField select size="small" label="Interest days" value={dayCount} disabled={kind !== 3} onChange={(e) => setDayCount(Number(e.target.value))}>
                <MenuItem value={1}>30 days a month</MenuItem><MenuItem value={2}>Real days</MenuItem>
              </TextField>
              <TextField size="small" type="date" label="Released" value={release} onChange={(e) => setRelease(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            </Box>

            {preview.error && <Alert severity="warning" sx={{ mt: 1.5, py: 0 }}>{(preview.error as Error).message}</Alert>}
            {valid && preview.data && (
              <Box sx={{ mt: 2, opacity: preview.isFetching ? 0.6 : 1, transition: 'opacity 160ms' }}>
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 1, mb: 1.5 }}>
                  <Stat label={kind === 3 ? 'Payment' : 'First payment'} value={money(preview.data.payment)} />
                  <Stat label="Payments" value={String(preview.data.payments)} />
                  <Stat label="Total interest" value={money(preview.data.totalInterest)} />
                  <Stat label="Total paid" value={money(preview.data.totalPaid)} />
                </Box>
                <Box sx={(t) => ({ ...listTable(t), maxHeight: all ? 260 : 'none', overflow: 'auto', '& table': { width: '100%', borderCollapse: 'collapse' }, '& td, & th': { px: 1, textAlign: 'right', fontVariantNumeric: 'tabular-nums', fontSize: 12.5 }, '& th': { py: 0.6, fontSize: 11, fontWeight: 600, letterSpacing: '0.05em', textTransform: 'uppercase', color: t.palette.text.secondary, borderBottom: `0.5px solid ${t.palette.divider}` }, '& td': { py: 0.4 } })}>
                  <table>
                    <thead><tr><Box component="th" sx={{ textAlign: 'left !important' }}>Due</Box><th>Principal</th><th>Interest</th><th>Balance</th></tr></thead>
                    <tbody>
                      {shown.map((r, i) => (
                        <tr key={r.dueDate + i}>
                          <Box component="td" sx={{ textAlign: 'left !important', py: 0.4 }}>{day(r.dueDate)}</Box>
                          <td>{money(r.principalDue)}</td><td>{money(r.interestDue)}</td><td>{money(r.principalBalance)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </Box>
                {rows.length > 7 && <Button size="small" onClick={() => setAll((a) => !a)} sx={{ mt: 0.5 }}>{all ? 'Show fewer' : `Show all ${rows.length} payments`}</Button>}
              </Box>
            )}
            {!valid && <Typography color="text.secondary" sx={{ fontSize: 13, mt: 2 }}>Fill in the amount, term, how it is paid and the rate to see the schedule.</Typography>}
          </Box>
        </Surface>
        <Typography color="text.secondary" sx={{ fontSize: 12.5, mt: 1.25, px: 0.5 }}>This is the schedule the loan application would build. Nothing is saved.</Typography>
      </Box>
    </Box>
  )
}

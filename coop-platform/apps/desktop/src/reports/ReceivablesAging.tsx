import {
  Alert, Autocomplete, Box, Button, Chip, FormControlLabel, IconButton, LinearProgress, MenuItem, Radio, RadioGroup, Stack, Switch, Tab, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Tabs, TextField, Typography, alpha, type Theme,
} from '@mui/material'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useMemo, useState, type ReactNode } from 'react'
import { FiChevronDown, FiChevronRight, FiDownload, FiFileText, FiGrid, FiPrinter, FiRotateCcw, FiX } from 'react-icons/fi'
import { logExport } from '../api/privacy'
import { downloadAging, getAgingOptions, runAging, type AgingLine, type AgingOptions, type AgingRequest, type AgingResult } from '../api/receivables'
import { LoadingBlock } from '../ui/Loading'
import { money } from '../ui/format'
import { agingCsv, DEFAULT_REQUEST, forgetRequest, groupIndexes, rememberedRequest, rememberRequest, shares, visible } from './aging'
import { ScreenTitle } from '../appleUi'

/** Rows drawn at once; the rest wait for Show all, so a run of ten thousand accounts does not freeze the window. */
const ROW_LIMIT = 1500

/** One colour per bucket, from on time (green) to over a year late (dark red), from the theme's palette. */
export function bucketColor(t: Theme, i: number): string {
  const p = t.palette
  return [p.success.main, p.info.main, p.warning.light, p.warning.main, p.error.light, p.error.main, p.error.dark][Math.min(i, 6)]
}

const tabular = { fontVariantNumeric: 'tabular-nums' } as const
const dash = (n: number) => (n === 0 ? '–' : money(n))
const blankToNull = (s: string) => (s.trim() === '' ? null : s)
const numOrNull = (s: string) => (s.trim() === '' || Number.isNaN(Number(s.replace(/,/g, ''))) ? null : Number(s.replace(/,/g, '')))

function download(name: string, text: string) {
  try {
    const url = URL.createObjectURL(new Blob(['﻿' + text], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
  } catch {
    // the download was refused; the table is still on screen
  }
}

/**
 * Aging of receivables (Reports > Receivables), the old system's AGING OF RECEIVABLES FILTER ({@code pics/reports.png}) rebuilt: a cut-off date, age by PAR @ n days or by
 * maturity, the setup dates, SL classes and types, number filters, up to three nested groupings, sorting and Show totals only; then a summary, the aging spread and the
 * table with a subtotal per group that can be folded. Saved as CSV or printed. The choices are remembered on this computer.
 */
export default function ReceivablesAging() {
  const options = useQuery({ queryKey: ['receivables-aging-options'], queryFn: getAgingOptions, staleTime: 5 * 60_000 })
  if (!options.data) return <LoadingBlock q={options} what="the aging choices" />
  return <AgingScreen options={options.data} />
}

function AgingScreen({ options }: { options: AgingOptions }) {
  const [req, setReq] = useState<AgingRequest>(() => {
    const r = rememberedRequest(options.cutOff)
    const classes = r.classes.filter((c) => options.classes.some((o) => o.code === c))
    const groupBy = r.groupBy.filter((g) => options.groupings.some((o) => o.key === g)).slice(0, 3)
    return { ...r, classes: classes.length ? classes : [12, 13], groupBy }
  })
  const [tab, setTab] = useState(0)
  const [folded, setFolded] = useState<Set<number>>(new Set())
  const [showAll, setShowAll] = useState(false)
  const run = useMutation({
    mutationFn: runAging,
    onSuccess: (r) => {
      rememberRequest(req)
      setShowAll(false)
      // a big grouped run opens on the subtotals of the innermost groups; a click opens a group
      const accounts = r.lines.filter((l) => l.kind === 'account').length
      setFolded(new Set(r.groupBy.length > 0 && accounts > 400 ? groupIndexes(r.lines, r.groupBy.length - 1) : []))
    },
  })
  const set = (patch: Partial<AgingRequest>) => setReq((r) => ({ ...r, ...patch }))
  const go = () => run.mutate(req)

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ justifyContent: 'space-between', alignItems: { sm: 'flex-start' }, gap: 1.5, mb: 1.5 }}>
        <Box>
          <ScreenTitle title="Aging of receivables">Loans and accounts receivable still owed on the cut-off date, aged and grouped the way you choose.</ScreenTitle>
        </Box>
        <BasisPicker req={req} set={set} />
      </Stack>

      <Box className="no-print" component="form" onSubmit={(e) => { e.preventDefault(); go() }}
        sx={(t) => ({ border: 1, borderColor: 'divider', borderRadius: 2, mb: 2, bgcolor: alpha(t.palette.background.paper, 0.5) })}>
        <Stack direction="row" sx={{ alignItems: 'center', borderBottom: 1, borderColor: 'divider', px: 1, gap: 1, flexWrap: 'wrap' }}>
          <Tabs value={tab} onChange={(_, v: number) => setTab(v)} sx={{ flex: 1, minWidth: 0 }} variant="scrollable" scrollButtons={false}>
            <Tab label="Basic" />
            <Tab label={`Groupings${req.groupBy.length ? ` (${req.groupBy.length})` : ''}`} />
            <Tab label="Number filters" />
            <Tab label={`Accrued interest${req.accruedInterest ? ' (on)' : ''}`} />
          </Tabs>
          <FormControlLabel control={<Switch checked={req.totalsOnly} onChange={(e) => set({ totalsOnly: e.target.checked })} />} label="Show totals only" sx={{ mr: 1 }} />
        </Stack>
        <Box sx={{ p: 2 }}>
          {tab === 0 && <BasicTab req={req} set={set} options={options} />}
          {tab === 1 && <GroupingTab req={req} set={set} options={options} />}
          {tab === 2 && <NumbersTab req={req} set={set} />}
          {tab === 3 && <AccrualTab req={req} set={set} />}
        </Box>
        <Stack direction="row" sx={{ gap: 1, px: 2, pb: 2, flexWrap: 'wrap', alignItems: 'center' }}>
          <Button type="submit" variant="contained" disabled={run.isPending}>Run report</Button>
          <Button startIcon={<FiRotateCcw />} onClick={() => { forgetRequest(); setReq(DEFAULT_REQUEST(options.cutOff)) }} disabled={run.isPending}>Reset choices</Button>
          <Typography sx={{ fontSize: 12, ml: { sm: 'auto' } }} color="text.secondary">{summaryOf(req, options)}</Typography>
        </Stack>
        <Typography sx={{ fontSize: 12, px: 2, pb: 1.5, mt: -1 }} color="text.secondary">
          {req.basis === 'PAR'
            ? `PAR @ ${req.parDays}: ages each account by how late its oldest unpaid instalment is, and counts its whole balance at risk once it is ${req.parDays} or more days late.`
            : `Maturity @ ${req.parDays}: ages each account by the days since its maturity date, and counts it at risk once it is ${req.parDays} or more days past maturity.`}
        </Typography>
      </Box>

      {run.error && <Alert severity="error" sx={{ mb: 1.5 }} onClose={() => run.reset()}>{(run.error as Error).message}</Alert>}
      {run.isPending && (
        <Box sx={{ mb: 2 }}>
          <LinearProgress />
          <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">Reading the receivable ledgers and repayment schedules; this can take 10 to 30 seconds.</Typography>
        </Box>
      )}
      {run.data && !run.isPending && (
        <AgingReport r={run.data} request={run.variables ?? req} folded={folded} setFolded={setFolded} showAll={showAll} onShowAll={() => setShowAll(true)} options={options} />
      )}
    </Box>
  )
}

/** The old screen's PAR @ / Maturity choice, top right: age by days late or days past maturity, and the days from which an account counts as at risk. */
function BasisPicker({ req, set }: { req: AgingRequest; set: (p: Partial<AgingRequest>) => void }) {
  return (
    <Stack className="no-print" direction="row" sx={{ alignItems: 'center', gap: 1.5, border: 1, borderColor: 'divider', borderRadius: 2, px: 1.5, py: 0.75 }}>
      <RadioGroup row value={req.basis} onChange={(e) => set({ basis: e.target.value as AgingRequest['basis'] })} aria-label="Age by">
        <FormControlLabel value="PAR" control={<Radio size="small" />} label="PAR @" />
        <FormControlLabel value="MATURITY" control={<Radio size="small" />} label="Maturity @" />
      </RadioGroup>
      <TextField label="Days" type="number" value={req.parDays} onChange={(e) => set({ parDays: Math.max(1, Math.min(3650, Number(e.target.value) || 1)) })}
        sx={{ width: 90 }} slotProps={{ htmlInput: { min: 1, max: 3650 } }} helperText={null} />
    </Stack>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Box>
      <Typography sx={{ fontSize: 12, fontWeight: 600, mb: 0.75 }} color="text.secondary">{label}</Typography>
      {children}
    </Box>
  )
}

function DateRange({ from, to, onFrom, onTo, label }: { from: string | null; to: string | null; onFrom: (v: string | null) => void; onTo: (v: string | null) => void; label: string }) {
  return (
    <Field label={label}>
      <Stack direction="row" sx={{ gap: 1 }}>
        <TextField type="date" label="From" value={from ?? ''} onChange={(e) => onFrom(blankToNull(e.target.value))} slotProps={{ inputLabel: { shrink: true } }} fullWidth />
        <TextField type="date" label="To" value={to ?? ''} onChange={(e) => onTo(blankToNull(e.target.value))} slotProps={{ inputLabel: { shrink: true } }} fullWidth />
      </Stack>
    </Field>
  )
}

function BasicTab({ req, set, options }: { req: AgingRequest; set: (p: Partial<AgingRequest>) => void; options: AgingOptions }) {
  const typeOptions = options.classes.filter((c) => req.classes.includes(c.code)).flatMap((c) => c.types.map((t) => ({ key: `${c.code}:${t.code}`, label: t.name, group: c.name })))
  const chosenTypes = typeOptions.filter((o) => req.types.includes(o.key))
  const toggleClass = (code: number) => {
    const classes = req.classes.includes(code) ? req.classes.filter((c) => c !== code) : [...req.classes, code]
    if (classes.length === 0) return
    set({ classes, types: req.types.filter((t) => classes.includes(Number(t.split(':')[0]))) })
  }
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) minmax(0, 1.2fr)' }, gap: 2.5 }}>
      <Stack sx={{ gap: 2 }}>
        <Field label="Cut-off date">
          <TextField type="date" label="Balances as of" required value={req.cutOff} onChange={(e) => e.target.value && set({ cutOff: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 200 }} />
        </Field>
        <DateRange label="Date released" from={req.releasedFrom} to={req.releasedTo} onFrom={(v) => set({ releasedFrom: v })} onTo={(v) => set({ releasedTo: v })} />
        <DateRange label="Maturity date" from={req.maturityFrom} to={req.maturityTo} onFrom={(v) => set({ maturityFrom: v })} onTo={(v) => set({ maturityTo: v })} />
        <DateRange label="Last transaction date" from={req.lastTransFrom} to={req.lastTransTo} onFrom={(v) => set({ lastTransFrom: v })} onTo={(v) => set({ lastTransTo: v })} />
      </Stack>
      <Stack sx={{ gap: 2 }}>
        <Field label="SL class">
          <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
            {options.classes.map((c) => {
              const on = req.classes.includes(c.code)
              return <Chip key={c.code} label={c.name} color={on ? 'primary' : 'default'} variant={on ? 'filled' : 'outlined'} onClick={() => toggleClass(c.code)} aria-pressed={on} />
            })}
          </Stack>
        </Field>
        <Field label="SL type">
          <Autocomplete multiple options={typeOptions} groupBy={(o) => o.group} value={chosenTypes} getOptionLabel={(o) => o.label} isOptionEqualToValue={(a, b) => a.key === b.key}
            onChange={(_, v) => set({ types: v.map((o) => o.key) })} disableCloseOnSelect limitTags={4}
            renderInput={(p) => <TextField {...p} label={chosenTypes.length ? 'Types' : 'All types'} placeholder={chosenTypes.length ? '' : 'Leave empty for every type'} />} />
          <Typography sx={{ fontSize: 12, mt: 0.75 }} color="text.secondary">Leave empty to take every type of the chosen classes.</Typography>
        </Field>
      </Stack>
    </Box>
  )
}

function GroupingTab({ req, set, options }: { req: AgingRequest; set: (p: Partial<AgingRequest>) => void; options: AgingOptions }) {
  const label = (k: string) => options.groupings.find((g) => g.key === k)?.label ?? k
  const full = req.groupBy.length >= 3
  return (
    <Stack sx={{ gap: 2 }}>
      <Field label="Group by (in this order, up to three)">
        <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap', alignItems: 'center', minHeight: 32 }}>
          {req.groupBy.length === 0 && <Typography sx={{ fontSize: 13 }} color="text.secondary">No grouping: one line per account. Choose below.</Typography>}
          {req.groupBy.map((g, i) => (
            <Chip key={g} color="primary" label={`${i + 1} · ${label(g)}`} onDelete={() => set({ groupBy: req.groupBy.filter((x) => x !== g) })} deleteIcon={<FiX aria-label={`Remove ${label(g)}`} />} />
          ))}
        </Stack>
      </Field>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
        {options.groupings.filter((g) => !req.groupBy.includes(g.key)).map((g) => (
          <Chip key={g.key} label={g.label} variant="outlined" disabled={full} onClick={() => set({ groupBy: [...req.groupBy, g.key] })} />
        ))}
      </Box>
      {full && <Typography sx={{ fontSize: 12 }} color="text.secondary">Three groupings chosen; remove one to choose another.</Typography>}
      <Field label="Sort the accounts by">
        <TextField select value={req.sort} onChange={(e) => set({ sort: e.target.value })} sx={{ width: 280 }}>
          {options.sorts.map((s) => (
            <MenuItem key={s.key} value={s.key} disabled={ACCRUAL_SORTS.includes(s.key) && !req.accruedInterest}>
              {s.label}{ACCRUAL_SORTS.includes(s.key) && !req.accruedInterest ? ' (turn on accrued interest)' : ''}
            </MenuItem>
          ))}
        </TextField>
      </Field>
    </Stack>
  )
}

function NumbersTab({ req, set }: { req: AgingRequest; set: (p: Partial<AgingRequest>) => void }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' }, gap: 2.5, maxWidth: 720 }}>
      <Field label="Outstanding balance">
        <Stack direction="row" sx={{ gap: 1 }}>
          <TextField label="At least" value={req.minBalance ?? ''} onChange={(e) => set({ minBalance: numOrNull(e.target.value) })} fullWidth slotProps={{ htmlInput: { inputMode: 'decimal' } }} />
          <TextField label="At most" value={req.maxBalance ?? ''} onChange={(e) => set({ maxBalance: numOrNull(e.target.value) })} fullWidth slotProps={{ htmlInput: { inputMode: 'decimal' } }} />
        </Stack>
      </Field>
      <Field label={req.basis === 'PAR' ? 'Days late' : 'Days past maturity'}>
        <Stack direction="row" sx={{ gap: 1 }}>
          <TextField label="At least" value={req.minDays ?? ''} onChange={(e) => set({ minDays: numOrNull(e.target.value) })} fullWidth slotProps={{ htmlInput: { inputMode: 'numeric' } }} />
          <TextField label="At most" value={req.maxDays ?? ''} onChange={(e) => set({ maxDays: numOrNull(e.target.value) })} fullWidth slotProps={{ htmlInput: { inputMode: 'numeric' } }} />
        </Stack>
      </Field>
    </Box>
  )
}

/** Sorts that read the Accrued Interest tab's figures. */
const ACCRUAL_SORTS = ['interest', 'totaldue']

/**
 * The old screen's Accrued Interest tab: whether to work out, per account, the interest owed on the cut-off date (as the interest and penalty worksheet does) and the
 * penalty, and whether to list only the accounts that owe some.
 */
function AccrualTab({ req, set }: { req: AgingRequest; set: (p: Partial<AgingRequest>) => void }) {
  const on = req.accruedInterest
  return (
    <Stack sx={{ gap: 1.5, maxWidth: 760 }}>
      <FormControlLabel control={<Switch checked={on} onChange={(e) => set(e.target.checked ? { accruedInterest: true } : { accruedInterest: false, penalty: false, owingOnly: false, sort: ACCRUAL_SORTS.includes(req.sort) ? 'name' : req.sort })} />}
        label="Work out accrued interest" />
      <Typography sx={{ fontSize: 12, mt: -1, ml: 6 }} color="text.secondary">
        Interest owed on the cut-off date, worked out as on the loan&apos;s interest and penalty worksheet: the yearly rate on the principal balance for each day (365-day year), less
        interest paid. Interest taken in advance at release counts as paid; a payment ahead shows as paid ahead. Adds a few seconds.
      </Typography>
      <FormControlLabel disabled={!on} control={<Switch checked={req.penalty} onChange={(e) => set({ penalty: e.target.checked })} />} label="Include penalty (estimate)" />
      <Typography sx={{ fontSize: 12, mt: -1, ml: 6 }} color="text.secondary">
        The yearly penalty rate on the late principal, less penalty paid or waived. Not yet confirmed against the old system, so treat it as an estimate.
      </Typography>
      <FormControlLabel disabled={!on} control={<Switch checked={req.owingOnly} onChange={(e) => set({ owingOnly: e.target.checked })} />} label="Only accounts that owe interest or penalty" />
    </Stack>
  )
}

/** One line under the form that says what will run. */
function summaryOf(r: AgingRequest, o: AgingOptions): string {
  const classes = o.classes.filter((c) => r.classes.includes(c.code)).map((c) => c.name).join(', ')
  const groups = r.groupBy.map((g) => o.groupings.find((x) => x.key === g)?.label ?? g).join(' › ')
  return `${classes}${r.types.length ? ` (${r.types.length} type${r.types.length > 1 ? 's' : ''})` : ''} · ${r.basis === 'PAR' ? 'PAR' : 'Maturity'} @ ${r.parDays} · as of ${r.cutOff}${groups ? ` · by ${groups}` : ''}${r.accruedInterest ? ` · with accrued interest${r.penalty ? ' and penalty' : ''}` : ''}`
}

function Tile({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: 'error' | 'warning' }) {
  return (
    <Box sx={(t) => ({ p: 1.5, borderRadius: 2, border: 1, borderColor: tone ? alpha(t.palette[tone].main, 0.4) : 'divider', bgcolor: tone ? alpha(t.palette[tone].main, 0.06) : 'transparent', minWidth: 0 })}>
      <Typography sx={{ fontSize: 12, fontWeight: 600 }} color="text.secondary">{label}</Typography>
      <Typography sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.01em', ...tabular, overflowWrap: 'anywhere' }} color={tone ? `${tone}.main` : 'text.primary'}>{value}</Typography>
      {sub && <Typography sx={{ fontSize: 12 }} color="text.secondary">{sub}</Typography>}
    </Box>
  )
}

/** The spread across the buckets as one bar, with each bucket's amount and share below. */
function AgingBar({ total, buckets }: { total: AgingLine; buckets: string[] }) {
  const pct = shares(total)
  return (
    <Box sx={{ mb: 2 }}>
      <Box role="img" aria-label={`Aging spread: ${buckets.map((b, i) => `${b} ${pct[i].toFixed(1)}%`).join(', ')}`}
        sx={{ display: 'flex', height: 14, borderRadius: 7, overflow: 'hidden', bgcolor: 'action.hover', printColorAdjust: 'exact', WebkitPrintColorAdjust: 'exact' }}>
        {pct.map((p, i) => p > 0 && <Box key={i} sx={(t) => ({ width: `${p}%`, bgcolor: bucketColor(t, i), minWidth: 2 })} />)}
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))', gap: 1, mt: 1.25 }}>
        {buckets.map((b, i) => (
          <Stack key={b} direction="row" sx={{ gap: 0.75, alignItems: 'flex-start' }}>
            <Box sx={(t) => ({ width: 10, height: 10, borderRadius: '3px', bgcolor: bucketColor(t, i), mt: '3px', flexShrink: 0, printColorAdjust: 'exact', WebkitPrintColorAdjust: 'exact' })} />
            <Box sx={{ minWidth: 0 }}>
              <Typography sx={{ fontSize: 12, fontWeight: 600 }}>{b}</Typography>
              <Typography sx={{ fontSize: 12, ...tabular }} color="text.secondary">{money(total.buckets[i])} · {pct[i].toFixed(1)}%</Typography>
            </Box>
          </Stack>
        ))}
      </Box>
    </Box>
  )
}

function AgingReport({ r, request, folded, setFolded, showAll, onShowAll, options }: {
  r: AgingResult; request: AgingRequest; folded: Set<number>; setFolded: (s: Set<number>) => void; showAll: boolean; onShowAll: () => void; options: AgingOptions
}) {
  const total = r.lines[r.lines.length - 1]
  // Excel and PDF come from the server, for the choices of the run on screen (it reuses that run, so they come at once)
  const file = useMutation({ mutationFn: (kind: 'xlsx' | 'pdf') => downloadAging(request, kind), meta: { done: (name: unknown) => `Saved ${String(name)}` } })
  const shown = useMemo(() => visible(r.lines, folded), [r.lines, folded])
  const drawn = showAll ? shown : shown.slice(0, ROW_LIMIT)
  const hasAccounts = r.lines.some((l) => l.kind === 'account')
  const groups = groupIndexes(r.lines)
  const lateAccounts = r.lines.filter((l) => l.kind === 'account' && (l.days ?? 0) > 0).length
  const owingInterest = r.lines.filter((l) => l.kind === 'account' && l.interestDue > 0).length
  const riskWord = r.basis === 'PAR' ? `PAR ${r.parDays}` : `Past maturity ${r.parDays}+ days`
  const toggle = (i: number) => {
    const next = new Set(folded)
    if (next.has(i)) next.delete(i)
    else next.add(i)
    setFolded(next)
  }
  const groupNames = r.groupBy.map((g) => options.groupings.find((x) => x.key === g)?.label ?? g)
  const accrualCols = r.accrual ? (r.penalty ? 4 : 3) : 0
  const cols = 3 + r.buckets.length + 3 + accrualCols + (hasAccounts ? 4 : 0)

  return (
    <Box id="report-result">
      <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">
        As of <b>{r.cutOff}</b> · aged by {r.basis === 'PAR' ? `days late (PAR @ ${r.parDays})` : `days past maturity (@ ${r.parDays})`}{groupNames.length ? ` · grouped by ${groupNames.join(' › ')}` : ''}
      </Typography>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1.5, mb: 2 }}>
        <Tile label="Outstanding principal" value={money(total.outstanding)} sub={`${total.accounts.toLocaleString()} accounts`} />
        <Tile label={r.basis === 'PAR' ? 'Past-due principal' : 'Past maturity'} value={money(r.basis === 'PAR' ? total.pastDue : total.outstanding - total.buckets[0])}
          sub={hasAccounts ? `${lateAccounts.toLocaleString()} accounts ${r.basis === 'PAR' ? 'late' : 'past maturity'}` : undefined} tone="warning" />
        <Tile label={`At risk (${riskWord})`} value={money(total.atRisk)} sub="Whole balance of the accounts at risk" tone="error" />
        <Tile label={`${riskWord} ratio`} value={`${total.riskPct.toFixed(2)}%`} sub="At risk ÷ outstanding" tone={total.riskPct >= 5 ? 'error' : undefined} />
      </Box>
      {r.accrual && (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: `repeat(${r.penalty ? 4 : 3}, minmax(0, 1fr))` }, gap: 1.5, mb: 2 }}>
          <Tile label="Accrued interest due" value={money(total.interestDue)} sub={`${owingInterest.toLocaleString()} accounts owe interest`} tone="warning" />
          <Tile label="Interest paid ahead" value={money(total.interestAhead)} sub="Paid before it was earned" />
          {r.penalty && <Tile label="Penalty due (estimate)" value={money(total.penaltyDue)} sub="Not yet confirmed against the old system" tone="warning" />}
          <Tile label="Total owed" value={money(total.totalDue)} sub={r.penalty ? 'Principal, interest and penalty' : 'Principal and interest'} />
        </Box>
      )}
      <AgingBar total={total} buckets={r.buckets} />

      <Stack className="no-print" direction="row" sx={{ gap: 1, mb: 1, flexWrap: 'wrap', alignItems: 'center' }}>
        {groups.length > 0 && <Button size="small" onClick={() => setFolded(new Set())}>Open all groups</Button>}
        {groups.length > 0 && <Button size="small" onClick={() => setFolded(new Set(groupIndexes(r.lines, 0)))}>Close all groups</Button>}
        <Box sx={{ flex: 1 }} />
        <Button size="small" startIcon={<FiGrid />} disabled={file.isPending} onClick={() => file.mutate('xlsx')}>{file.isPending && file.variables === 'xlsx' ? 'Making Excel…' : 'Excel'}</Button>
        <Button size="small" startIcon={<FiFileText />} disabled={file.isPending} onClick={() => file.mutate('pdf')}>{file.isPending && file.variables === 'pdf' ? 'Making PDF…' : 'PDF'}</Button>
        <Button size="small" startIcon={<FiDownload />} onClick={() => { download(`aging-of-receivables-${r.cutOff}.csv`, agingCsv(r)); void logExport('REPORT', 'receivables-aging', r.lines.length) }}>CSV</Button>
        <Button size="small" startIcon={<FiPrinter />} onClick={() => window.print()}>Print</Button>
      </Stack>

      {file.error && <Alert severity="error" className="no-print" sx={{ mb: 1 }} onClose={() => file.reset()}>{(file.error as Error).message}</Alert>}
      <TableContainer sx={{ maxHeight: '70vh', border: 1, borderColor: 'divider', borderRadius: 2, '@media print': { maxHeight: 'none', overflow: 'visible', border: 0 } }}>
        <Table size="small" stickyHeader aria-label="Aging of receivables">
          <TableHead>
            <TableRow>
              <TableCell sx={{ fontWeight: 700, minWidth: 240 }}>{groupNames.length ? `${groupNames.join(' › ')}${hasAccounts ? ' › account' : ''}` : 'Account'}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700 }}>Accounts</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700 }}>Outstanding</TableCell>
              {r.buckets.map((b, i) => (
                <TableCell key={b} align="right" sx={(t) => ({ fontWeight: 700, whiteSpace: 'nowrap', boxShadow: `inset 0 -3px 0 ${bucketColor(t, i)}` })}>{b}</TableCell>
              ))}
              <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{r.basis === 'PAR' ? 'Past due' : 'Matured'}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>At risk</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700 }}>%</TableCell>
              {r.accrual && <>
                <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>Interest due</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>Paid ahead</TableCell>
                {r.penalty && <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>Penalty</TableCell>}
                <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>Total owed</TableCell>
              </>}
              {hasAccounts && <>
                <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>Days</TableCell>
                <TableCell sx={{ fontWeight: 700 }}>Released</TableCell>
                <TableCell sx={{ fontWeight: 700 }}>Maturity</TableCell>
                <TableCell sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>Last trans.</TableCell>
              </>}
            </TableRow>
          </TableHead>
          <TableBody>
            {drawn.map((i) => <AgingRow key={i} l={r.lines[i]} index={i} open={!folded.has(i)} onToggle={toggle} withDates={hasAccounts} basis={r.basis} accrual={r.accrual} penalty={r.penalty} />)}
            {total.accounts === 0 && <TableRow><TableCell colSpan={cols}><Typography color="text.secondary">No receivable has a balance for these choices.</Typography></TableCell></TableRow>}
            {!showAll && shown.length > ROW_LIMIT && (
              <TableRow className="no-print"><TableCell colSpan={cols}>
                <Stack direction="row" sx={{ gap: 1.5, alignItems: 'center' }}>
                  <Typography sx={{ fontSize: 13 }} color="text.secondary">Showing {ROW_LIMIT.toLocaleString()} of {shown.length.toLocaleString()} lines. The totals count every account.</Typography>
                  <Button size="small" onClick={onShowAll}>Show all</Button>
                </Stack>
              </TableCell></TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      <Typography sx={{ fontSize: 12, mt: 1.5 }} color="text.secondary">{r.note}</Typography>
      <Typography sx={{ fontSize: 12, mt: 0.5 }} color="text.secondary">Run by {r.ranBy} on {r.ranAt.replace('T', ' ')}</Typography>
    </Box>
  )
}

function AgingRow({ l, index, open, onToggle, withDates, basis, accrual, penalty }: {
  l: AgingLine; index: number; open: boolean; onToggle: (i: number) => void; withDates: boolean; basis: AgingResult['basis']; accrual: boolean; penalty: boolean
}) {
  const group = l.kind === 'group', total = l.kind === 'total'
  const strong = group || total
  const cellSx = (t: Theme) => ({
    ...tabular,
    fontWeight: strong ? 700 : 400,
    ...(total ? { borderTop: `2px solid ${t.palette.divider}`, bgcolor: alpha(t.palette.primary.main, 0.06) } : {}),
    ...(group && l.level === 0 ? { bgcolor: alpha(t.palette.text.primary, 0.035) } : {}),
  })
  const days = l.days ?? 0
  const bucketOf = l.buckets.findIndex((b) => b !== 0)
  return (
    <TableRow hover={!total}>
      <TableCell sx={(t) => ({ ...cellSx(t), pl: 1 + l.level * 2.5 })}>
        <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5 }}>
          {group && (
            <IconButton size="small" className="no-print" onClick={() => onToggle(index)} aria-label={open ? `Close ${l.label}` : `Open ${l.label}`} aria-expanded={open} sx={{ ml: -0.5 }}>
              {open ? <FiChevronDown /> : <FiChevronRight />}
            </IconButton>
          )}
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontSize: 13, fontWeight: strong ? 700 : 400, lineHeight: 1.3 }}>{l.label}</Typography>
            {l.kind === 'account' && <Typography sx={{ fontSize: 11, lineHeight: 1.3 }} color="text.secondary">No. {l.client} · {l.detail}</Typography>}
          </Box>
        </Stack>
      </TableCell>
      <TableCell align="right" sx={cellSx}>{l.kind === 'account' ? '' : l.accounts.toLocaleString()}</TableCell>
      <TableCell align="right" sx={cellSx}>{money(l.outstanding)}</TableCell>
      {l.buckets.map((b, i) => (
        <TableCell key={i} align="right" sx={(t) => ({ ...cellSx(t), whiteSpace: 'nowrap', color: b === 0 ? 'text.disabled' : i >= 3 ? 'error.main' : undefined })}>{dash(b)}</TableCell>
      ))}
      <TableCell align="right" sx={(t) => ({ ...cellSx(t), whiteSpace: 'nowrap' })}>{dash(basis === 'PAR' ? l.pastDue : l.outstanding - l.buckets[0])}</TableCell>
      <TableCell align="right" sx={(t) => ({ ...cellSx(t), whiteSpace: 'nowrap', color: l.atRisk > 0 ? 'error.main' : 'text.disabled' })}>{dash(l.atRisk)}</TableCell>
      <TableCell align="right" sx={cellSx}>{l.kind === 'account' ? '' : `${l.riskPct.toFixed(2)}%`}</TableCell>
      {accrual && <>
        <TableCell align="right" sx={(t) => ({ ...cellSx(t), whiteSpace: 'nowrap', color: l.interestDue > 0 ? 'warning.dark' : 'text.disabled' })}>{dash(l.interestDue)}</TableCell>
        <TableCell align="right" sx={(t) => ({ ...cellSx(t), whiteSpace: 'nowrap', color: l.interestAhead > 0 ? 'success.dark' : 'text.disabled' })}>{dash(l.interestAhead)}</TableCell>
        {penalty && <TableCell align="right" sx={(t) => ({ ...cellSx(t), whiteSpace: 'nowrap', color: l.penaltyDue > 0 ? 'error.main' : 'text.disabled' })}>{dash(l.penaltyDue)}</TableCell>}
        <TableCell align="right" sx={(t) => ({ ...cellSx(t), whiteSpace: 'nowrap' })}>{money(l.totalDue)}</TableCell>
      </>}
      {withDates && <>
        <TableCell align="right" sx={cellSx}>
          {l.kind === 'account' && (days > 0
            ? <Box component="span" sx={(t) => ({ px: 0.75, py: 0.25, borderRadius: 1, bgcolor: alpha(bucketColor(t, bucketOf), 0.14), color: bucketOf >= 4 ? 'error.main' : 'text.primary', fontWeight: 600 })}>{days}</Box>
            : <Box component="span" sx={{ color: 'text.disabled' }}>0</Box>)}
        </TableCell>
        <TableCell sx={{ ...tabular, whiteSpace: 'nowrap' }}>{l.released ?? ''}</TableCell>
        <TableCell sx={{ ...tabular, whiteSpace: 'nowrap' }}>{l.maturity ?? ''}</TableCell>
        <TableCell sx={{ ...tabular, whiteSpace: 'nowrap' }}>{l.lastTrans ?? ''}</TableCell>
      </>}
    </TableRow>
  )
}

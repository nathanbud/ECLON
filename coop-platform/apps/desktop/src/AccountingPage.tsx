import { Alert, Box, Button, Chip, FormControlLabel, MenuItem, Stack, Switch, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useMemo, useState } from 'react'
import {
  buildBalances, createPeriod, getBalanceStatus, getConsolidating, getFinancialCondition, getOperations, getStatementUnits, listPeriods, setPeriodStatus, type ConsolidatingStatement,
  type PeriodStatus, type StatementSection,
} from './api'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import BanksTab from './BanksTab'
import TrialBalancePage from './TrialBalancePage'
import { BranchSelect } from './ui/BranchSelect'
import YearEndCloseTab from './YearEndCloseTab'
import { money } from './ui/format'
import { ConfirmButton } from './ui/dialogs'
import { labelOf } from './ui/labels'
import { unitHeading, unitOptions } from './ui/units'

const today = () => new Date().toLocaleDateString('en-CA')
const yearStart = () => `${new Date().getFullYear()}-01-01`

/** The books: trial balance for everyone; statements, periods, banks and the year-end close for accountants and administrators. Entries (journals, memos, waivers) are on Back Office. */
export default function AccountingPage({ initialTab }: { initialTab?: string }) {
  const me = useMe()
  const accountant = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  const tabs = ['Trial balance', ...(accountant ? ['Statements', 'Periods', 'Banks', 'Year-end close'] : [])]
  // a tab named by the side menu or the search opens first
  const [tab, setTab] = useState(() => Math.max(0, tabs.indexOf(initialTab ?? '')))
  const [branchCode, setBranchCode] = useState(me.branchCode ?? 1)
  const ok = branchCode > 0

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <Stack direction="row" spacing={2} sx={{ mb: 1, alignItems: "center" }}>
          <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', flex: 1 }}>Accounting</Typography>
          {tab > 0 && <BranchSelect value={branchCode} onChange={setBranchCode} />}
        </Stack>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>{tabs.map((t) => <Tab key={t} label={t} />)}</Tabs>
        {tab === 0 && <Box sx={{ mx: -3, mt: -3 }}><TrialBalancePage /></Box>}
        {tab === 1 && ok && <Statements branchCode={branchCode} />}
        {tab === 2 && ok && <Periods branchCode={branchCode} />}
        {tab === 3 && ok && <BanksTab branchCode={branchCode} />}
        {tab === 4 && ok && <YearEndCloseTab branchCode={branchCode} />}
      </Box>
    </Box>
  )
}

// ---- statements ----------------------------------------------------------------------------------------------------

function SectionRows({ s }: { s: StatementSection }) {
  return (
    <>
      <TableRow>
        <TableCell colSpan={2} sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{s.name}</TableCell>
        <TableCell align="right" sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{money(s.total)}</TableCell>
      </TableRow>
      {s.groups.map((g) => (
        <GroupRows key={g.name} g={g} />
      ))}
    </>
  )
}

function GroupRows({ g }: { g: StatementSection['groups'][number] }) {
  return (
    <>
      <TableRow>
        <TableCell colSpan={2} sx={{ fontWeight: 600, pl: 3 }}>{g.name}</TableCell>
        <TableCell align="right" sx={{ fontWeight: 600 }}>{money(g.total)}</TableCell>
      </TableRow>
      {g.lines.map((l) => (
        <TableRow key={l.accountCode + l.description} hover>
          <TableCell sx={{ pl: 5, width: 110 }}>{l.accountCode}</TableCell>
          <TableCell>{l.description}</TableCell>
          <TableCell align="right">{money(l.amount)}</TableCell>
        </TableRow>
      ))}
    </>
  )
}

function SectionTable({ sections, children }: { sections: StatementSection[]; children?: React.ReactNode }) {
  return (
    <Table size="small">
      <TableBody>
        {sections.map((s) => (
          <SectionRows key={s.name} s={s} />
        ))}
        {children}
      </TableBody>
    </Table>
  )
}

function Statements({ branchCode }: { branchCode: number }) {
  const [kind, setKind] = useState<'fc' | 'ops'>('fc')
  const [asOf, setAsOf] = useState(today())
  const [from, setFrom] = useState(yearStart())
  const [to, setTo] = useState(today())
  // null: the whole branch's books; otherwise a unit with everything under it
  const [unit, setUnit] = useState<number | null>(null)
  const [sideBySide, setSideBySide] = useState(false)
  const units = useQuery({ queryKey: ['fs-units'], queryFn: getStatementUnits, staleTime: 300_000 })
  const options = useMemo(() => unitOptions(units.data ?? []), [units.data])
  const chosen = options.find((o) => o.unit.id === unit)?.unit
  const columns = sideBySide && chosen != null && chosen.children > 0
  const fc = useQuery({ queryKey: ['fs-fc', branchCode, asOf, unit], queryFn: () => getFinancialCondition(branchCode, asOf, unit), enabled: kind === 'fc' && !!asOf && !columns, retry: false })
  const ops = useQuery({ queryKey: ['fs-ops', branchCode, from, to, unit], queryFn: () => getOperations(branchCode, from, to, unit), enabled: kind === 'ops' && !!from && !!to && !columns, retry: false })
  const cons = useQuery({
    queryKey: ['fs-cons', branchCode, kind, asOf, from, to, unit],
    queryFn: () => getConsolidating(branchCode, unit!, kind === 'fc' ? { asOf } : { from, to }),
    enabled: columns && (kind === 'fc' ? !!asOf : !!from && !!to), retry: false,
  })
  const q = columns ? cons : kind === 'fc' ? fc : ops
  const shownUnit = (kind === 'fc' ? fc.data?.unit : ops.data?.unit) ?? null
  return (
    <Box>
      <Stack direction="row" spacing={2} useFlexGap sx={{ mb: 2, alignItems: "center", flexWrap: "wrap" }}>
        <TextField select size="small" label="Statement" value={kind} onChange={(e) => setKind(e.target.value as 'fc' | 'ops')} sx={{ width: 260 }}>
          <MenuItem value="fc">Statement of Financial Condition</MenuItem>
          <MenuItem value="ops">Statement of Operations</MenuItem>
        </TextField>
        <TextField select size="small" label="Unit" value={unit == null ? 'all' : String(unit)} sx={{ width: 300 }}
          onChange={(e) => { const v = e.target.value; setUnit(v === 'all' ? null : Number(v)) }}
          slotProps={{ select: { renderValue: (v) => (v === 'all' ? 'Whole branch (every unit)' : chosen?.name ?? String(v)) } }}>
          <MenuItem value="all">Whole branch (every unit)</MenuItem>
          {options.map(({ unit: u, depth }) => (
            <MenuItem key={u.id} value={String(u.id)} sx={{ pl: 2 + depth * 2 }}>
              <Box sx={{ minWidth: 0 }}>
                <Typography sx={{ fontSize: 13, color: u.disabled ? 'text.disabled' : undefined }}>{u.name}</Typography>
                <Typography sx={{ fontSize: 11 }} color="text.secondary">{u.kindName}{u.kindChosen ? '' : ' (suggested)'}{u.children > 0 ? ` · ${u.children} under it` : ''}</Typography>
              </Box>
            </MenuItem>
          ))}
        </TextField>
        {chosen != null && chosen.children > 0 && (
          <FormControlLabel control={<Switch checked={sideBySide} onChange={(e) => setSideBySide(e.target.checked)} />} label="Show each unit side by side" />
        )}
        {kind === 'fc' ? (
          <TextField size="small" type="date" label="As at" value={asOf} onChange={(e) => setAsOf(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        ) : (
          <>
            <TextField size="small" type="date" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField size="small" type="date" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          </>
        )}
      </Stack>
      {q.error && <Alert severity="error" sx={{ mb: 2 }}>{(q.error as Error).message}</Alert>}
      <YearBalances branchCode={branchCode} />
      {!columns && shownUnit && <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1, px: 0.5 }}>{unitHeading(shownUnit)}</Typography>}
      {columns && cons.data && (
        <>
          <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1, px: 0.5 }}>Consolidating statement: {cons.data.unit.name}</Typography>
          <Box sx={(t) => ({ overflow: 'auto', opacity: cons.isFetching ? 0.6 : 1, ...glass(t) })}><ConsolidatingTable c={cons.data} /></Box>
        </>
      )}
      {!columns && kind === 'fc' && fc.data && (
        <>
          <Alert severity={fc.data.balanced ? 'success' : 'warning'} sx={{ mb: 2 }}>
            {fc.data.balanced
              ? 'Assets equal liabilities plus equity.'
              : `Assets are ${money(fc.data.totalAssets)} but liabilities plus equity are ${money(fc.data.totalLiabilitiesAndEquity)} (difference ${money(Math.abs(fc.data.totalAssets - fc.data.totalLiabilitiesAndEquity))}). ${fc.data.unit ? 'A single unit balances only when its entries with the other units (due to and due from) are in its own books.' : 'The legacy ledger does not balance for this date.'}`}
          </Alert>
          <Box sx={(t) => ({ overflow: 'auto', opacity: fc.isFetching ? 0.6 : 1, ...glass(t) })}><SectionTable sections={fc.data.sections} /></Box>
        </>
      )}
      {!columns && kind === 'ops' && ops.data && (
        <Box sx={(t) => ({ overflow: 'auto', opacity: ops.isFetching ? 0.6 : 1, ...glass(t) })}>
          <SectionTable sections={ops.data.sections}>
            <TableRow>
              <TableCell colSpan={2} sx={{ fontWeight: 700 }}>Net surplus</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700 }}>{money(ops.data.netSurplus)}</TableCell>
            </TableRow>
          </SectionTable>
        </Box>
      )}
    </Box>
  )
}

/**
 * Which finished years are summed for the statements "as at" a date, for the whole branch and by unit; an administrator starts the summing of the missing ones,
 * which runs in the background (a few minutes a year) and is shown while it runs.
 */
function YearBalances({ branchCode }: { branchCode: number }) {
  const me = useMe()
  const qc = useQueryClient()
  const status = useQuery({
    queryKey: ['fs-balances', branchCode], queryFn: () => getBalanceStatus(branchCode),
    refetchInterval: (q) => (q.state.data?.state === 'running' ? 5000 : false),
  })
  const build = useMutation({ meta: { done: 'Summing the earlier years has started. It runs in the background.' }, mutationFn: () => buildBalances(branchCode),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['fs-balances', branchCode] }) })
  const s = status.data
  if (!s) return null
  const last = new Date().getFullYear() - 1
  const upTo = (ys: number[]) => (ys.length === 0 ? 'none yet' : ys.includes(last) ? `every year to ${last}` : `${Math.min(...ys)} to ${Math.max(...ys)}, not ${last} yet`)
  const missing = !s.builtYears.includes(last) || !s.builtYearsByUnit.includes(last)
  const running = s.state === 'running'
  return (
    <Stack direction="row" spacing={1.5} useFlexGap sx={{ mb: 1.5, px: 0.5, alignItems: 'center', flexWrap: 'wrap' }}>
      <Typography sx={{ fontSize: 12 }} color="text.secondary">
        Earlier years summed: {upTo(s.builtYears)}; by unit: {upTo(s.builtYearsByUnit)}.{running ? ' Summing now…' : ''}{s.state.startsWith('failed') ? ` The last run failed: ${s.state.slice(8)}` : ''}
      </Typography>
      {me.admin && missing && !running && <Button size="small" disabled={build.isPending} onClick={() => build.mutate()}>Build balances</Button>}
    </Stack>
  )
}

/** Each unit's figures in its own column, the consolidation's own entries (adjustments and eliminations) when it has any, and the consolidated total. */
function ConsolidatingTable({ c }: { c: ConsolidatingStatement }) {
  const head = { fontWeight: 600, whiteSpace: 'nowrap' as const, verticalAlign: 'bottom' as const }
  const amounts = (xs: number[], total: number, weight?: number) => (
    <>
      {xs.map((v, i) => <TableCell key={i} align="right" sx={{ fontWeight: weight, whiteSpace: 'nowrap' }}>{money(v)}</TableCell>)}
      <TableCell align="right" sx={{ fontWeight: weight ?? 600, whiteSpace: 'nowrap', bgcolor: 'action.hover' }}>{money(total)}</TableCell>
    </>
  )
  return (
    <Table size="small" aria-label="Consolidating statement">
      <TableHead>
        <TableRow>
          <TableCell colSpan={2} />
          {c.columns.map((col) => (
            <TableCell key={`${col.id}-${col.ownEntries}`} align="right" sx={head}>
              <Box>{col.name}</Box>
              <Typography component="div" sx={{ fontSize: 11 }} color="text.secondary">{col.kindName}</Typography>
            </TableCell>
          ))}
          <TableCell align="right" sx={{ ...head, bgcolor: 'action.hover' }}>Consolidated</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {c.sections.map((s) => (
          <Fragment key={s.name}>
            <TableRow>
              <TableCell colSpan={2} sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{s.name}</TableCell>
              {amounts(s.totals, s.total, 700)}
            </TableRow>
            {s.groups.map((g) => (
              <Fragment key={g.name}>
                <TableRow>
                  <TableCell colSpan={2} sx={{ fontWeight: 600, pl: 3 }}>{g.name}</TableCell>
                  {amounts(g.totals, g.total, 600)}
                </TableRow>
                {g.lines.map((l) => (
                  <TableRow key={l.accountCode + l.description} hover>
                    <TableCell sx={{ pl: 5, width: 110 }}>{l.accountCode}</TableCell>
                    <TableCell sx={{ minWidth: 200 }}>{l.description}</TableCell>
                    {amounts(l.amounts, l.total)}
                  </TableRow>
                ))}
              </Fragment>
            ))}
          </Fragment>
        ))}
        <TableRow>
          <TableCell colSpan={2} sx={{ fontWeight: 700 }}>{c.bottomName}</TableCell>
          {amounts(c.bottom, c.bottomTotal, 700)}
        </TableRow>
      </TableBody>
    </Table>
  )
}

// ---- periods -------------------------------------------------------------------------------------------------------

const nextStatus: Record<PeriodStatus, PeriodStatus | null> = { OPEN: 'SOFT_CLOSED', SOFT_CLOSED: 'CLOSED', CLOSED: 'LOCKED', LOCKED: null }

function Periods({ branchCode }: { branchCode: number }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['periods', branchCode], queryFn: () => listPeriods(branchCode) })
  const [name, setName] = useState('')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const refresh = () => qc.invalidateQueries({ queryKey: ['periods'] })
  const add = useMutation({ meta: { done: 'Period added.' },
    mutationFn: () => createPeriod({ branchCode, name: name.trim(), startDate: start, endDate: end }),
    onSuccess: () => { setName(''); setStart(''); setEnd(''); refresh() },
  })
  const move = useMutation({ meta: { done: (_, v) => ({ OPEN: 'Period reopened.', SOFT_CLOSED: 'Period closed for review.', CLOSED: 'Period closed.', LOCKED: 'Period locked.' } as Record<string, string>)[(v as { status: string }).status] ?? null }, mutationFn: (v: { id: number; status: PeriodStatus }) => setPeriodStatus(v.id, v.status), onSuccess: refresh })
  const error = (list.error ?? add.error ?? move.error) as Error | null
  return (
    <Box>
      <Stack direction="row" spacing={1.5} useFlexGap sx={{ mb: 2, flexWrap: "wrap" }}>
        <TextField size="small" label="Name" value={name} onChange={(e) => setName(e.target.value)} />
        <TextField size="small" type="date" label="Start" value={start} onChange={(e) => setStart(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField size="small" type="date" label="End" value={end} onChange={(e) => setEnd(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <Button variant="contained" disabled={add.isPending || !name.trim() || !start || !end} onClick={() => add.mutate()}>Add period</Button>
      </Stack>
      {error && <Alert severity="error" sx={{ mb: 2 }}>{error.message}</Alert>}
      {list.data && list.data.length === 0 && <Alert severity="info">No periods yet. Posting is refused for dates without a period.</Alert>}
      {list.data && list.data.length > 0 && (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Accounting periods">
            <TableHead>
              <TableRow><TableCell>Name</TableCell><TableCell>From</TableCell><TableCell>To</TableCell><TableCell>Status</TableCell><TableCell /></TableRow>
            </TableHead>
            <TableBody>
              {list.data.map((p) => {
                const next = nextStatus[p.status]
                return (
                  <TableRow key={p.id}>
                    <TableCell>{p.name}</TableCell>
                    <TableCell>{p.startDate}</TableCell>
                    <TableCell>{p.endDate}</TableCell>
                    <TableCell><Chip size="small" label={labelOf(p.status)} color={p.status === 'OPEN' ? 'success' : 'default'} /></TableCell>
                    <TableCell align="right">
                      {p.status === 'SOFT_CLOSED' && <Button size="small" onClick={() => move.mutate({ id: p.id, status: 'OPEN' })}>Reopen</Button>}
                      {next === 'SOFT_CLOSED' && <Button size="small" disabled={move.isPending} onClick={() => move.mutate({ id: p.id, status: next })}>Soft close</Button>}
                      {(next === 'CLOSED' || next === 'LOCKED') && (
                        <ConfirmButton size="small" color="warning" disabled={move.isPending} onConfirm={() => move.mutate({ id: p.id, status: next })}
                          title={next === 'CLOSED' ? `Close ${p.name}?` : `Lock ${p.name}?`} confirmLabel={next === 'CLOSED' ? 'Close the period' : 'Lock the period'}
                          message={next === 'CLOSED'
                            ? <>Nothing can be posted dated {p.startDate} to {p.endDate} any more: no journals, memos or teller transactions. A closed period cannot be reopened.</>
                            : <>The period stays closed for good. This cannot be undone.</>}>
                          {next === 'CLOSED' ? 'Close' : 'Lock'}
                        </ConfirmButton>
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </Box>
      )}
    </Box>
  )
}

import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack, Switch, Tab, Table, TableBody, TableCell, TableHead,
  TableRow, Tabs, TextField, Typography,
} from '@mui/material'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useMemo, useState } from 'react'
import {
  buildBalances, createPeriod, getBalanceStatus, getCashFlow, getCashFlowClasses, getChangesInEquity, getComparative, getConsolidating, getFinancialCondition,
  getOperations, getStatementUnits, setCashFlowClass, type CashFlowCategory, type CashFlowStatement, type ChangesInEquity, listPeriods, setPeriodStatus, type ConsolidatingStatement,
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
import { DrillCode, LedgerDrill, type Drill } from './ui/LedgerDrill'
import { BusyArea, Loading, LoadingBlock } from './ui/Loading'
import { ReasonDialog } from './appleUi'
import BudgetTab from './BudgetTab'
import { ClosingPolicy, CloseStatusNotice, MonthReview, YearChecklist } from './ClosingParts'

const today = () => new Date().toLocaleDateString('en-CA')
const yearStart = () => `${new Date().getFullYear()}-01-01`

/** The books: trial balance for everyone; statements, periods, banks and the year-end close for accountants and administrators. Entries (journals, memos, waivers) are on Back Office. */
export default function AccountingPage({ initialTab }: { initialTab?: string }) {
  const me = useMe()
  const accountant = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  // an auditor reads every tab and changes nothing
  const auditor = !accountant && me.roles.some((r) => r.replace('ROLE_', '') === 'AUDITOR')
  const tabs = ['Trial balance', ...(accountant || auditor ? ['Statements', 'Periods', 'Banks', 'Month-end review', 'Year-end close', 'Budget'] : [])]
  // a tab named by the side menu or the search opens first
  const [tab, setTab] = useState(() => Math.max(0, tabs.indexOf(initialTab ?? '')))
  const name = tabs[tab] ?? tabs[0]
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
        {auditor && tab > 0 && <Alert severity="info" sx={{ mb: 2 }}>You are signed in as an auditor: everything here can be read and exported, and nothing can be changed.</Alert>}
        {tab > 0 && ok && <CloseStatusNotice branchCode={branchCode} />}
        {name === 'Trial balance' && <Box sx={{ mx: -3, mt: -3 }}><TrialBalancePage /></Box>}
        {name === 'Statements' && ok && <Statements branchCode={branchCode} />}
        {name === 'Periods' && ok && <Periods branchCode={branchCode} />}
        {name === 'Banks' && ok && <BanksTab branchCode={branchCode} />}
        {name === 'Month-end review' && ok && (
          <>
            <ClosingPolicy branchCode={branchCode} admin={me.admin} />
            <MonthReview branchCode={branchCode} canEdit={accountant} />
          </>
        )}
        {name === 'Year-end close' && ok && (
          <>
            <ClosingPolicy branchCode={branchCode} admin={me.admin} />
            <YearChecklist branchCode={branchCode} canEdit={accountant} />
            <YearEndCloseTab branchCode={branchCode} />
          </>
        )}
        {name === 'Budget' && ok && <BudgetTab branchCode={branchCode} canEdit={accountant} />}
      </Box>
    </Box>
  )
}

// ---- statements ----------------------------------------------------------------------------------------------------

function SectionRows({ s, onDrill }: { s: StatementSection; onDrill?: (code: string, name: string) => void }) {
  return (
    <>
      <TableRow>
        <TableCell colSpan={2} sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{s.name}</TableCell>
        <TableCell align="right" sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{money(s.total)}</TableCell>
      </TableRow>
      {s.groups.map((g) => (
        <GroupRows key={g.name} g={g} onDrill={onDrill} />
      ))}
    </>
  )
}

function GroupRows({ g, onDrill }: { g: StatementSection['groups'][number]; onDrill?: (code: string, name: string) => void }) {
  return (
    <>
      <TableRow>
        <TableCell colSpan={2} sx={{ fontWeight: 600, pl: 3 }}>{g.name}</TableCell>
        <TableCell align="right" sx={{ fontWeight: 600 }}>{money(g.total)}</TableCell>
      </TableRow>
      {g.lines.map((l) => (
        <TableRow key={l.accountCode + l.description} hover>
          <TableCell sx={{ pl: 5, width: 110 }}>{l.accountCode && onDrill ? <DrillCode code={l.accountCode} onOpen={() => onDrill(l.accountCode, l.description)} /> : l.accountCode}</TableCell>
          <TableCell>{l.description}</TableCell>
          <TableCell align="right">{money(l.amount)}</TableCell>
        </TableRow>
      ))}
    </>
  )
}

function SectionTable({ sections, children, onDrill }: { sections: StatementSection[]; children?: React.ReactNode; onDrill?: (code: string, name: string) => void }) {
  return (
    <Table size="small">
      <TableBody>
        {sections.map((s) => (
          <SectionRows key={s.name} s={s} onDrill={onDrill} />
        ))}
        {children}
      </TableBody>
    </Table>
  )
}

type StatementKind = 'fc' | 'ops' | 'eq' | 'cf'
const STATEMENTS: { value: StatementKind; label: string }[] = [
  { value: 'fc', label: 'Statement of Financial Condition' },
  { value: 'ops', label: 'Statement of Operations' },
  { value: 'eq', label: 'Statement of Changes in Equity' },
  { value: 'cf', label: 'Statement of Cash Flows' },
]

function Statements({ branchCode }: { branchCode: number }) {
  const me = useMe()
  const [kind, setKind] = useState<StatementKind>('fc')
  const [asOf, setAsOf] = useState(today())
  const [from, setFrom] = useState(yearStart())
  const [to, setTo] = useState(today())
  // null: the whole branch's books; otherwise a unit with everything under it
  const [unit, setUnit] = useState<number | null>(null)
  const [sideBySide, setSideBySide] = useState(false)
  const [compare, setCompare] = useState(false)
  const [classes, setClasses] = useState(false)
  // the entries behind a line, opened from its account code
  const [drill, setDrill] = useState<Drill | null>(null)
  const units = useQuery({ queryKey: ['fs-units'], queryFn: getStatementUnits, staleTime: 300_000 })
  const options = useMemo(() => unitOptions(units.data ?? []), [units.data])
  const chosen = options.find((o) => o.unit.id === unit)?.unit
  const twoKinds = kind === 'fc' || kind === 'ops'
  const columns = twoKinds && sideBySide && chosen != null && chosen.children > 0
  const comparing = twoKinds && compare && !columns
  const single = twoKinds && !columns && !comparing
  const period = kind === 'fc' ? { asOf } : { from, to }
  const datesOk = kind === 'fc' ? !!asOf : !!from && !!to
  const fc = useQuery({ queryKey: ['fs-fc', branchCode, asOf, unit], queryFn: () => getFinancialCondition(branchCode, asOf, unit), enabled: kind === 'fc' && single && datesOk, retry: false, placeholderData: keepPreviousData })
  const ops = useQuery({ queryKey: ['fs-ops', branchCode, from, to, unit], queryFn: () => getOperations(branchCode, from, to, unit), enabled: kind === 'ops' && single && datesOk, retry: false, placeholderData: keepPreviousData })
  const cons = useQuery({
    queryKey: ['fs-cons', branchCode, kind, asOf, from, to, unit],
    queryFn: () => getConsolidating(branchCode, unit!, period),
    enabled: columns && datesOk, retry: false, placeholderData: keepPreviousData,
  })
  const comp = useQuery({
    queryKey: ['fs-comp', branchCode, kind, asOf, from, to, unit],
    queryFn: () => getComparative(branchCode, unit, period),
    enabled: comparing && datesOk, retry: false, placeholderData: keepPreviousData,
  })
  const eq = useQuery({ queryKey: ['fs-eq', branchCode, from, to, unit], queryFn: () => getChangesInEquity(branchCode, from, to, unit), enabled: kind === 'eq' && datesOk, retry: false, placeholderData: keepPreviousData })
  const cf = useQuery({ queryKey: ['fs-cf', branchCode, from, to, unit], queryFn: () => getCashFlow(branchCode, from, to, unit), enabled: kind === 'cf' && datesOk, retry: false, placeholderData: keepPreviousData })
  const q = columns ? cons : comparing ? comp : kind === 'fc' ? fc : kind === 'ops' ? ops : kind === 'eq' ? eq : cf
  const shownUnit = (kind === 'fc' ? fc.data?.unit : kind === 'ops' ? ops.data?.unit : kind === 'eq' ? eq.data?.unit : cf.data?.unit) ?? null
  const accountant = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  return (
    <Box>
      <Stack direction="row" spacing={2} useFlexGap sx={{ mb: 2, alignItems: "center", flexWrap: "wrap" }}>
        <TextField select size="small" label="Statement" value={kind} onChange={(e) => setKind(e.target.value as StatementKind)} sx={{ width: 280 }}>
          {STATEMENTS.map((x) => <MenuItem key={x.value} value={x.value}>{x.label}</MenuItem>)}
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
        {kind === 'fc' ? (
          <TextField size="small" type="date" label="As at" value={asOf} onChange={(e) => setAsOf(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        ) : (
          <>
            <TextField size="small" type="date" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField size="small" type="date" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          </>
        )}
        {twoKinds && (
          <FormControlLabel control={<Switch checked={compare} onChange={(e) => setCompare(e.target.checked)} disabled={columns} />}
            label={kind === 'fc' ? 'Compare with the end of last year' : 'Compare with the same period last year'} />
        )}
        {twoKinds && chosen != null && chosen.children > 0 && (
          <FormControlLabel control={<Switch checked={sideBySide} onChange={(e) => setSideBySide(e.target.checked)} />} label="Show each unit side by side" />
        )}
        {kind === 'cf' && <Button size="small" onClick={() => setClasses(true)}>Cash flow classes</Button>}
      </Stack>
      {q.error && <Alert severity="error" sx={{ mb: 2 }}>{(q.error as Error).message}</Alert>}
      <YearBalances branchCode={branchCode} />
      {q.isLoading && <Loading what="the statement" />}
      {!columns && !comparing && shownUnit && <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1, px: 0.5 }}>{unitHeading(shownUnit)}</Typography>}
      {columns && cons.data && (
        <>
          <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1, px: 0.5 }}>Consolidating statement: {cons.data.unit.name}</Typography>
          <BusyArea busy={cons.isFetching} label="Loading the statement"><Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}><ConsolidatingTable c={cons.data} /></Box></BusyArea>
        </>
      )}
      {comparing && comp.data && (
        <>
          <Typography sx={{ fontSize: 13, fontWeight: 600, mb: 1, px: 0.5 }}>Comparative statement: {comp.data.unit.name}</Typography>
          <BusyArea busy={comp.isFetching} label="Loading the statement"><Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}><ConsolidatingTable c={comp.data} /></Box></BusyArea>
        </>
      )}
      {single && kind === 'fc' && fc.data && (
        <>
          <Alert severity={fc.data.balanced ? 'success' : 'warning'} sx={{ mb: 2 }}>
            {fc.data.balanced
              ? 'Assets equal liabilities plus equity.'
              : `Assets are ${money(fc.data.totalAssets)} but liabilities plus equity are ${money(fc.data.totalLiabilitiesAndEquity)} (difference ${money(Math.abs(fc.data.totalAssets - fc.data.totalLiabilitiesAndEquity))}). ${fc.data.unit ? 'A single unit balances only when its entries with the other units (due to and due from) are in its own books.' : 'The legacy ledger does not balance for this date.'}`}
          </Alert>
          <BusyArea busy={fc.isFetching} label="Loading the statement"><Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}><SectionTable sections={fc.data.sections} onDrill={(account, name) => setDrill({ account, name, from: `${asOf.slice(0, 4)}-01-01`, to: asOf })} /></Box></BusyArea>
        </>
      )}
      {single && kind === 'ops' && ops.data && (
        <BusyArea busy={ops.isFetching} label="Loading the statement">
          <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
            <SectionTable sections={ops.data.sections} onDrill={(account, name) => setDrill({ account, name, from, to })}>
              <TableRow>
                <TableCell colSpan={2} sx={{ fontWeight: 700 }}>Net surplus</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700 }}>{money(ops.data.netSurplus)}</TableCell>
              </TableRow>
            </SectionTable>
          </Box>
        </BusyArea>
      )}
      {kind === 'eq' && eq.data && (
        <BusyArea busy={eq.isFetching} label="Loading the statement"><Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}><EquityTable e={eq.data} /></Box></BusyArea>
      )}
      {kind === 'cf' && cf.data && (
        <BusyArea busy={cf.isFetching} label="Loading the statement"><Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}><CashFlowTable c={cf.data} /></Box></BusyArea>
      )}
      {classes && <CashFlowClasses branchCode={branchCode} canEdit={accountant} onClose={() => setClasses(false)} />}
      {drill && <LedgerDrill drill={drill} onClose={() => setDrill(null)} />}
    </Box>
  )
}

/** The Statement of Changes in Equity: each equity account from the balance at the start to the balance at the end, by what came in and what went out. */
function EquityTable({ e }: { e: ChangesInEquity }) {
  const showOther = e.rows.some((r) => Math.abs(r.other) >= 0.005)
  const num = (v: number, bold?: boolean) => <TableCell align="right" sx={{ whiteSpace: 'nowrap', fontWeight: bold ? 700 : undefined }}>{money(v)}</TableCell>
  return (
    <Table size="small" aria-label="Statement of Changes in Equity">
      <TableHead>
        <TableRow>
          <TableCell>Equity</TableCell>
          <TableCell align="right">Beginning ({e.from})</TableCell>
          <TableCell align="right">Increases</TableCell>
          <TableCell align="right">Decreases</TableCell>
          {showOther && <TableCell align="right">Not explained</TableCell>}
          <TableCell align="right">Ending ({e.to})</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {e.rows.map((r) => (
          <TableRow key={r.accountCode + r.description} hover>
            <TableCell>
              <Typography sx={{ fontSize: 13 }}>{r.accountCode ? `${r.accountCode} ` : ''}{r.description}</Typography>
              <Typography sx={{ fontSize: 11 }} color="text.secondary">{r.group}</Typography>
            </TableCell>
            {num(r.beginning)}{num(r.increases)}{num(r.decreases)}{showOther && num(r.other)}{num(r.ending)}
          </TableRow>
        ))}
        <TableRow>
          <TableCell sx={{ fontWeight: 700 }}>Total equity</TableCell>
          {num(e.beginning, true)}{num(e.increases, true)}{num(e.decreases, true)}{showOther && num(e.other, true)}{num(e.ending, true)}
        </TableRow>
      </TableBody>
    </Table>
  )
}

/** The Statement of Cash Flows: net surplus, then each activity's changes, the net change in cash and the cash at the start and the end. */
function CashFlowTable({ c }: { c: CashFlowStatement }) {
  const row = (label: string, v: number, opts: { bold?: boolean; pl?: number; code?: string } = {}) => (
    <TableRow key={`${opts.code ?? ''}-${label}`} hover={!opts.bold}>
      <TableCell sx={{ pl: opts.pl ?? 2, fontWeight: opts.bold ? 700 : undefined }}>{opts.code ? `${opts.code} ` : ''}{label}</TableCell>
      <TableCell align="right" sx={{ whiteSpace: 'nowrap', fontWeight: opts.bold ? 700 : undefined }}>{money(v)}</TableCell>
    </TableRow>
  )
  return (
    <Table size="small" aria-label="Statement of Cash Flows">
      <TableBody>
        {c.activities.map((a) => (
          <Fragment key={a.category}>
            <TableRow><TableCell colSpan={2} sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{a.name}</TableCell></TableRow>
            {a.lines.map((l) => row(l.description, l.amount, { pl: 4, code: l.accountCode || undefined }))}
            {row(`Net cash from ${a.name.toLowerCase()}`, a.total, { bold: true })}
          </Fragment>
        ))}
        {Math.abs(c.unexplained) >= 0.005 && row('Not explained by the entries (old data that does not balance)', c.unexplained, { bold: true })}
        {row('Net change in cash and cash equivalents', c.netChange, { bold: true })}
        {row(`Cash and cash equivalents at the beginning (${c.from})`, c.cashBeginning)}
        {row(`Cash and cash equivalents at the end (${c.to})`, c.cashEnding, { bold: true })}
      </TableBody>
    </Table>
  )
}

const CASH_FLOW_WORDS: Record<CashFlowCategory, string> = { CASH: 'Cash and cash equivalents', OPERATING: 'Operating', INVESTING: 'Investing', FINANCING: 'Financing' }

/**
 * The class of each balance-sheet heading for the Statement of Cash Flows. A heading's class applies to every account under it that has none of its own; a
 * class not chosen is suggested from the chart's headings. Accountants and administrators change one with a reason, kept in the audit log.
 */
function CashFlowClasses({ branchCode, canEdit, onClose }: { branchCode: number; canEdit: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['fs-cf-classes', branchCode], queryFn: () => getCashFlowClasses(branchCode) })
  const [filter, setFilter] = useState('')
  const [asking, setAsking] = useState<{ code: string; name: string; category: CashFlowCategory } | null>(null)
  const save = useMutation({
    meta: { done: 'Cash flow class saved.' },
    mutationFn: (reason: string) => setCashFlowClass(branchCode, asking!.code, asking!.category, reason),
    onSuccess: (rows) => { qc.setQueryData(['fs-cf-classes', branchCode], rows); qc.invalidateQueries({ queryKey: ['fs-cf'] }); setAsking(null) },
  })
  const f = filter.trim().toLowerCase()
  const rows = (list.data ?? []).filter((r) => r.summary && (!f || r.code.includes(f) || r.name.toLowerCase().includes(f)))
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Cash flow classes</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">
          The class of each balance-sheet heading; the accounts under it follow it. Suggested classes come from the headings&apos; names; choose another where the
          cooperative&apos;s policy says so.
        </Typography>
        <TextField size="small" label="Find a heading" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ mb: 1.5, width: 280 }} />
        <LoadingBlock q={list} what="the classes" />
        <Table size="small" aria-label="Cash flow classes">
          <TableHead><TableRow><TableCell>Heading</TableCell><TableCell sx={{ width: 220 }}>Class</TableCell></TableRow></TableHead>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.code} hover>
                <TableCell>
                  <Typography sx={{ fontSize: 13 }}>{r.code} {r.name}</Typography>
                  <Typography sx={{ fontSize: 11 }} color="text.secondary">{r.chosen ? 'Chosen' : r.inheritedFrom ? `From ${r.inheritedFrom}` : 'Suggested'}</Typography>
                </TableCell>
                <TableCell>
                  <TextField select size="small" value={r.category} disabled={!canEdit} fullWidth aria-label={`Class of ${r.code}`}
                    onChange={(e) => setAsking({ code: r.code, name: r.name, category: e.target.value as CashFlowCategory })}>
                    {(Object.keys(CASH_FLOW_WORDS) as CashFlowCategory[]).map((k) => <MenuItem key={k} value={k}>{CASH_FLOW_WORDS[k]}</MenuItem>)}
                  </TextField>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
      <ReasonDialog open={asking != null} title={asking ? `${asking.code} ${asking.name}: ${CASH_FLOW_WORDS[asking.category].toLowerCase()}` : ''} confirm="Save"
        busy={save.isPending} error={save.error ? (save.error as Error).message : null} onCancel={() => setAsking(null)} onConfirm={(r) => save.mutate(r)} />
    </Dialog>
  )
}

/**
 * Which finished years are summed for the statements "as at" a date, for the whole branch and by unit; an administrator starts the summing of the missing ones,
 * which runs in the background (a few minutes a year) and is shown while it runs.
 */
function YearBalances({ branchCode }: { branchCode: number }) {
  const me = useMe()
  // the last finished year, read once
  const [last] = useState(() => new Date().getFullYear() - 1)
  const qc = useQueryClient()
  const status = useQuery({
    queryKey: ['fs-balances', branchCode], queryFn: () => getBalanceStatus(branchCode),
    refetchInterval: (q) => (q.state.data?.state === 'running' ? 5000 : false),
  })
  const build = useMutation({ meta: { done: 'Summing the earlier years has started. It runs in the background.' }, mutationFn: () => buildBalances(branchCode),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['fs-balances', branchCode] }) })
  const s = status.data
  if (!s) return null
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
          <TableCell align="right" sx={{ ...head, bgcolor: 'action.hover' }}>{c.totalName ?? 'Consolidated'}</TableCell>
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

import { Alert, Box, Button, Chip, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  createPeriod, getFinancialCondition, getOperations, listPeriods, setPeriodStatus, type PeriodStatus, type StatementSection,
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
  const fc = useQuery({ queryKey: ['fs-fc', branchCode, asOf], queryFn: () => getFinancialCondition(branchCode, asOf), enabled: kind === 'fc' && !!asOf, retry: false })
  const ops = useQuery({ queryKey: ['fs-ops', branchCode, from, to], queryFn: () => getOperations(branchCode, from, to), enabled: kind === 'ops' && !!from && !!to, retry: false })
  const q = kind === 'fc' ? fc : ops
  return (
    <Box>
      <Stack direction="row" spacing={2} useFlexGap sx={{ mb: 2, alignItems: "center", flexWrap: "wrap" }}>
        <TextField select size="small" label="Statement" value={kind} onChange={(e) => setKind(e.target.value as 'fc' | 'ops')} sx={{ width: 260 }}>
          <MenuItem value="fc">Statement of Financial Condition</MenuItem>
          <MenuItem value="ops">Statement of Operations</MenuItem>
        </TextField>
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
      {kind === 'fc' && fc.data && (
        <>
          <Alert severity={fc.data.balanced ? 'success' : 'warning'} sx={{ mb: 2 }}>
            {fc.data.balanced
              ? 'Assets equal liabilities plus equity.'
              : `Assets are ${money(fc.data.totalAssets)} but liabilities plus equity are ${money(fc.data.totalLiabilitiesAndEquity)} (difference ${money(Math.abs(fc.data.totalAssets - fc.data.totalLiabilitiesAndEquity))}). The legacy ledger does not balance for this date.`}
          </Alert>
          <Box sx={(t) => ({ overflow: 'auto', opacity: fc.isFetching ? 0.6 : 1, ...glass(t) })}><SectionTable sections={fc.data.sections} /></Box>
        </>
      )}
      {kind === 'ops' && ops.data && (
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

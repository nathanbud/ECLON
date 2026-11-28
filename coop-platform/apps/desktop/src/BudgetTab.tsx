import { Alert, Box, Button, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useMemo, useState } from 'react'
import { getBudgets, getBudgetVariance, saveBudgets } from './api'
import { ReasonDialog, Segmented } from './appleUi'
import { glass } from './glass'
import { tint } from './theme'
import { money } from './ui/format'
import { BusyArea, LoadingBlock } from './ui/Loading'

const TYPE_WORDS: Record<number, string> = { 4: 'Revenues', 5: 'Expenses' }

/**
 * Budgets: the annual budget of each income and expense account, beside last year's actual for reference, and Budget versus actual for a period of the year.
 * Accountants and administrators change the budgets, a set of changes at a time with one reason; auditors read them.
 */
export default function BudgetTab({ branchCode, canEdit }: { branchCode: number; canEdit: boolean }) {
  const [year, setYear] = useState(() => new Date().getFullYear())
  const [part, setPart] = useState<'budget' | 'variance'>('budget')
  const years = useMemo(() => Array.from({ length: 6 }, (_, i) => year - 3 + i), [year])
  return (
    <Box>
      <Stack direction="row" spacing={2} useFlexGap sx={{ mb: 2, alignItems: 'center', flexWrap: 'wrap' }}>
        <TextField select size="small" label="Budget year" value={year} onChange={(e) => setYear(Number(e.target.value))} sx={{ width: 140 }}>
          {years.map((y) => <MenuItem key={y} value={y}>{y}</MenuItem>)}
        </TextField>
        <Segmented label="Budget" value={part} onChange={setPart} options={[{ value: 'budget', label: 'Budget' }, { value: 'variance', label: 'Budget versus actual' }]} />
      </Stack>
      {part === 'budget' ? <Budgets key={year} branchCode={branchCode} year={year} canEdit={canEdit} /> : <VarianceView branchCode={branchCode} year={year} />}
    </Box>
  )
}

function Budgets({ branchCode, year, canEdit }: { branchCode: number; year: number; canEdit: boolean }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['budgets', branchCode, year], queryFn: () => getBudgets(branchCode, year) })
  // amounts typed and not saved yet, by account
  const [typed, setTyped] = useState<Record<string, string>>({})
  const [asking, setAsking] = useState(false)
  const changes = (q.data ?? []).flatMap((l) => {
    const t = typed[l.accountCode]
    if (t === undefined) return []
    const n = Number(t.replace(/,/g, ''))
    return t.trim() !== '' && Number.isFinite(n) && n >= 0 && Math.round(n * 100) / 100 !== l.budget ? [{ accountCode: l.accountCode, amount: n }] : []
  })
  const invalid = Object.entries(typed).some(([, t]) => t.trim() !== '' && (!Number.isFinite(Number(t.replace(/,/g, ''))) || Number(t.replace(/,/g, '')) < 0))
  const save = useMutation({
    meta: { done: `The ${year} budget was saved.` },
    mutationFn: (reason: string) => saveBudgets(branchCode, year, changes, reason),
    onSuccess: (rows) => { qc.setQueryData(['budgets', branchCode, year], rows); qc.invalidateQueries({ queryKey: ['budget-variance'] }); setTyped({}); setAsking(false) },
  })
  const groups = [4, 5].map((type) => ({ type, rows: (q.data ?? []).filter((l) => l.type === type) }))
  const total = (type: number) => (q.data ?? []).filter((l) => l.type === type).reduce((s, l) => {
    const t = typed[l.accountCode]
    const n = t !== undefined && t.trim() !== '' ? Number(t.replace(/,/g, '')) : l.budget
    return s + (Number.isFinite(n) ? n : 0)
  }, 0)
  return (
    <Box>
      <LoadingBlock q={q} what="the budgets" />
      {q.data && (
        <>
          <Box sx={(t) => ({ ...glass(t), overflow: 'auto', maxHeight: 'calc(100dvh - 330px)' })}>
            <Table size="small" stickyHeader aria-label={`Budget ${year}`}>
              <TableHead>
                <TableRow>
                  <TableCell>Account</TableCell>
                  <TableCell align="right">Actual {year - 1}</TableCell>
                  <TableCell align="right" sx={{ width: 200 }}>Budget {year}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {groups.map((g) => (
                  <Fragment key={g.type}>
                    <TableRow><TableCell colSpan={3} sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{TYPE_WORDS[g.type]}</TableCell></TableRow>
                    {g.rows.map((l) => (
                      <TableRow key={l.accountCode} hover>
                        <TableCell>
                          <Typography>{l.accountCode} {l.name}</Typography>
                          <Typography variant="caption" color="text.secondary">{l.group}</Typography>
                        </TableCell>
                        <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{money(l.lastYearActual)}</TableCell>
                        <TableCell align="right">
                          {canEdit ? (
                            <TextField size="small" value={typed[l.accountCode] ?? (l.budget ? String(l.budget) : '')} placeholder="0.00"
                              onChange={(e) => setTyped((t) => ({ ...t, [l.accountCode]: e.target.value }))}
                              slotProps={{ htmlInput: { inputMode: 'decimal', 'aria-label': `Budget of ${l.accountCode}`, style: { textAlign: 'right' } } }} />
                          ) : money(l.budget)}
                        </TableCell>
                      </TableRow>
                    ))}
                    <TableRow>
                      <TableCell sx={{ fontWeight: 700 }}>Total {TYPE_WORDS[g.type].toLowerCase()}</TableCell>
                      <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{money(g.rows.reduce((s, l) => s + l.lastYearActual, 0))}</TableCell>
                      <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: 'nowrap' }}>{money(total(g.type))}</TableCell>
                    </TableRow>
                  </Fragment>
                ))}
                <TableRow>
                  <TableCell sx={{ fontWeight: 700 }}>Budgeted net surplus</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700 }}>{money(groups[0].rows.reduce((s, l) => s + l.lastYearActual, 0) - groups[1].rows.reduce((s, l) => s + l.lastYearActual, 0))}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700 }}>{money(total(4) - total(5))}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </Box>
          {canEdit && (
            <Stack direction="row" spacing={1} sx={{ mt: 1.5, alignItems: 'center', justifyContent: 'flex-end' }}>
              {invalid && <Typography variant="body2" color="error">A budget is a number, zero or more.</Typography>}
              <Typography variant="body2" color="text.secondary">{changes.length ? `${changes.length} change${changes.length === 1 ? '' : 's'} not saved` : ''}</Typography>
              <Button onClick={() => setTyped({})} disabled={Object.keys(typed).length === 0}>Undo</Button>
              <Button variant="contained" disabled={changes.length === 0 || invalid} onClick={() => { save.reset(); setAsking(true) }}>Save changes</Button>
            </Stack>
          )}
        </>
      )}
      <ReasonDialog open={asking} title={`Save the ${year} budget`} intro={`${changes.length} account${changes.length === 1 ? '' : 's'} change. Each change is kept in the audit log with the amount before and after.`}
        confirm="Save" busy={save.isPending} error={save.error ? (save.error as Error).message : null} onCancel={() => setAsking(false)} onConfirm={(r) => save.mutate(r)} />
    </Box>
  )
}

function VarianceView({ branchCode, year }: { branchCode: number; year: number }) {
  const [from, setFrom] = useState(`${year}-01-01`)
  const [to, setTo] = useState(() => {
    const today = new Date().toLocaleDateString('en-CA')
    return today.startsWith(String(year)) ? today : `${year}-12-31`
  })
  const q = useQuery({ queryKey: ['budget-variance', branchCode, year, from, to], queryFn: () => getBudgetVariance(branchCode, year, from, to), retry: false, placeholderData: keepPreviousData })
  const v = q.data
  return (
    <Box>
      <Stack direction="row" spacing={2} sx={{ mb: 2 }}>
        <TextField size="small" type="date" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField size="small" type="date" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
      </Stack>
      {q.error && <Alert severity="error" sx={{ mb: 2 }}>{(q.error as Error).message}</Alert>}
      <LoadingBlock q={q} what="budget versus actual" errors={false} />
      {v && (
        <BusyArea busy={q.isFetching} label="Loading budget versus actual">
          <Typography variant="body2" sx={{ mb: 1 }} color="text.secondary">{v.months} month{v.months === 1 ? '' : 's'} of the {year} budget, spread evenly by month.</Typography>
          <Box sx={(t) => ({ ...glass(t), overflow: 'auto' })}>
            <Table size="small" aria-label="Budget versus actual">
              <TableHead>
                <TableRow>
                  <TableCell>Account</TableCell><TableCell align="right">Budget</TableCell><TableCell align="right">Actual</TableCell><TableCell align="right">Variance</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {[4, 5].map((type) => (
                  <Fragment key={type}>
                    <TableRow><TableCell colSpan={4} sx={{ fontWeight: 700, bgcolor: 'action.hover' }}>{TYPE_WORDS[type]}</TableCell></TableRow>
                    {v.lines.filter((l) => l.type === type).map((l) => (
                      <TableRow key={l.accountCode} hover>
                        <TableCell><Typography>{l.accountCode} {l.name}</Typography></TableCell>
                        <TableCell align="right">{money(l.budget)}</TableCell>
                        <TableCell align="right">{money(l.actual)}</TableCell>
                        <TableCell align="right" sx={{ color: l.favourable ? tint.green[0] : tint.red[0], whiteSpace: 'nowrap' }}
                          title={l.favourable ? 'Favourable' : 'Unfavourable'}>
                          {money(l.variance)} {l.favourable ? '▲' : '▼'}
                        </TableCell>
                      </TableRow>
                    ))}
                  </Fragment>
                ))}
                <TableRow>
                  <TableCell sx={{ fontWeight: 700 }}>Net surplus</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700 }}>{money(v.revenueBudget - v.expenseBudget)}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700 }}>{money(v.revenueActual - v.expenseActual)}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700 }}>{money(v.revenueActual - v.expenseActual - (v.revenueBudget - v.expenseBudget))}</TableCell>
                </TableRow>
              </TableBody>
            </Table>
          </Box>
          <Typography variant="body2" sx={{ mt: 1 }} color="text.secondary">▲ favourable (revenue above budget, expense below it), ▼ unfavourable.</Typography>
        </BusyArea>
      )}
    </Box>
  )
}

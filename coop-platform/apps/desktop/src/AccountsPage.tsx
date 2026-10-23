import {
  Alert, Autocomplete, Box, Button, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem, Paper, Stack, Switch,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import { FiChevronRight, FiFile, FiFolder, FiLock, FiMinusSquare, FiPlus, FiPlusSquare } from 'react-icons/fi'
import { createChartAccount, getChart, getChartUsage, listAccounts, updateChartAccount, type ChartAccount } from './api'
import { useMe } from './session'

const TYPES = ['All types', 'Assets', 'Liabilities', 'Equity and Reserves', 'Revenues', 'Expenses', 'Net Income']
const INDENT = 22

interface Node { a: ChartAccount; kids: Node[] }

const byCode = (x: ChartAccount, y: ChartAccount) => x.code.localeCompare(y.code, undefined, { numeric: true })

/** Builds the tree from each account's summary account. An account whose parent is missing is shown at the top. */
function buildTree(list: ChartAccount[]): { roots: Node[]; nodes: Map<string, Node> } {
  const nodes = new Map<string, Node>()
  for (const a of [...list].sort(byCode)) nodes.set(a.code, { a, kids: [] })
  const roots: Node[] = []
  for (const n of nodes.values()) {
    const p = n.a.parent ? nodes.get(n.a.parent) : undefined
    if (p && p !== n) p.kids.push(n)
    else roots.push(n)
  }
  return { roots, nodes }
}

/** Marks the part of a text that the search matched. */
function Marked({ text, q }: { text: string; q: string }): ReactNode {
  const i = q ? text.toLowerCase().indexOf(q) : -1
  if (i < 0) return text
  return <>{text.slice(0, i)}<Box component="mark" sx={{ bgcolor: 'warning.light', color: 'inherit', borderRadius: 0.5, px: 0.25 }}>{text.slice(i, i + q.length)}</Box>{text.slice(i + q.length)}</>
}

/** The chart of accounts as a tree. Everyone signed in can look; administrators and accountants can add accounts and change them. */
export default function AccountsPage() {
  const me = useMe()
  const canEdit = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  const qc = useQueryClient()
  const [filter, setFilter] = useState('')
  const [type, setType] = useState(0)
  const [showOff, setShowOff] = useState(false)
  const [open, setOpen] = useState<Set<string> | null>(null) // null = the top two levels
  const [dialog, setDialog] = useState<{ target: ChartAccount | 'new'; under?: ChartAccount } | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [current, setCurrent] = useState<string | null>(null) // the one row in the tab order; arrow keys move it

  const { data, error, isFetching } = useQuery({ queryKey: ['chart'], queryFn: getChart, enabled: canEdit })
  const tree = useMemo(() => buildTree(data ?? []), [data])
  const q = filter.trim().toLowerCase()

  const expanded = useMemo(() => open ?? new Set(tree.roots.map((r) => r.a.code)), [open, tree])

  // with a search: the matches and the accounts above them, all opened; without: the tree as the person has opened it
  const shown = useMemo(() => {
    const pass = (a: ChartAccount) => (type === 0 || a.type === type) && (showOff || !a.disabled)
    const keep = new Set<string>()
    if (q) {
      for (const n of tree.nodes.values()) {
        if (!pass(n.a) || !(n.a.code.toLowerCase().includes(q) || (n.a.name ?? '').toLowerCase().includes(q))) continue
        for (let c: string | null = n.a.code; c && !keep.has(c); c = tree.nodes.get(c)?.a.parent ?? null) keep.add(c)
      }
    }
    const out: { n: Node; depth: number; opened: boolean; childCount: number }[] = []
    const walk = (n: Node, depth: number) => {
      if (q ? !keep.has(n.a.code) : !pass(n.a)) return
      const kids = n.kids.filter((k) => (q ? keep.has(k.a.code) : pass(k.a)))
      const opened = q ? kids.length > 0 : expanded.has(n.a.code)
      out.push({ n, depth, opened, childCount: kids.length })
      if (opened) kids.forEach((k) => walk(k, depth + 1))
    }
    tree.roots.forEach((r) => walk(r, 0))
    return out
  }, [tree, q, type, showOff, expanded])

  const toggle = (code: string) => setOpen((cur) => {
    const next = new Set(cur ?? expanded)
    if (next.has(code)) next.delete(code); else next.add(code)
    return next
  })
  const expandAll = () => setOpen(new Set([...tree.nodes.values()].filter((n) => n.kids.length > 0).map((n) => n.a.code)))
  const collapseAll = () => setOpen(new Set())

  const saved = (msg: string, code: string) => {
    setDialog(null); setDone(msg)
    // open the branch the new account sits in so it can be seen
    setOpen((cur) => {
      const next = new Set(cur ?? expanded)
      for (let c = tree.nodes.get(code)?.a.parent ?? dialog?.under?.code ?? null; c; c = tree.nodes.get(c)?.a.parent ?? null) next.add(c)
      return next
    })
    void qc.invalidateQueries({ queryKey: ['chart'] }); void qc.invalidateQueries({ queryKey: ['accounts'] })
  }

  const onKey = (e: KeyboardEvent<HTMLTableRowElement>, r: (typeof shown)[number], i: number) => {
    const focus = (j: number) => { const el = (e.currentTarget.parentElement?.children[j] as HTMLElement | undefined); el?.focus(); e.preventDefault() }
    if (e.key === 'ArrowDown') focus(i + 1)
    else if (e.key === 'ArrowUp') focus(i - 1)
    else if (e.key === 'ArrowRight' && r.childCount > 0 && !r.opened && !q) { toggle(r.n.a.code); e.preventDefault() }
    else if (e.key === 'ArrowLeft' && !q) {
      if (r.opened && r.childCount > 0) { toggle(r.n.a.code); e.preventDefault() }
      else if (r.n.a.parent) { const j = shown.findIndex((s) => s.n.a.code === r.n.a.parent); if (j >= 0) focus(j) }
    }
  }

  if (!canEdit) return <ReadOnlyChart />

  return (
    <Container sx={{ py: 3 }}>
      <Stack direction="row" spacing={2} sx={{ mb: 2, alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}>
        <TextField size="small" label="Find by number or name" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ width: 280 }} />
        <TextField size="small" select label="Type" value={type} onChange={(e) => setType(Number(e.target.value))} sx={{ width: 190 }}>
          {TYPES.map((t, i) => <MenuItem key={t} value={i}>{t}</MenuItem>)}
        </TextField>
        <FormControlLabel control={<Switch size="small" checked={showOff} onChange={(e) => setShowOff(e.target.checked)} />} label="Show switched-off" />
        <Tooltip title="Open every heading"><span><Button size="small" startIcon={<FiPlusSquare />} onClick={expandAll} disabled={!!q}>Expand all</Button></span></Tooltip>
        <Tooltip title="Close every heading"><span><Button size="small" startIcon={<FiMinusSquare />} onClick={collapseAll} disabled={!!q}>Collapse all</Button></span></Tooltip>
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" startIcon={<FiPlus />} onClick={() => { setDone(null); setDialog({ target: 'new' }) }}>New account</Button>
      </Stack>
      {done && <Alert severity="success" onClose={() => setDone(null)} sx={{ mb: 2 }}>{done}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 2 }}>{(error as Error).message}</Alert>}
      <TableContainer component={Paper} sx={{ opacity: isFetching ? 0.6 : 1, transition: 'opacity .2s', maxHeight: 'calc(100dvh - 210px)' }}>
        <Table size="small" stickyHeader role="treegrid" aria-label="Chart of accounts">
          <TableHead>
            <TableRow>
              <TableCell>Account</TableCell><TableCell>Kind</TableCell><TableCell>Status</TableCell><TableCell width={110} />
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((r, i) => {
              const a = r.n.a
              return (
                <TableRow
                  key={a.code} hover tabIndex={a.code === (current ?? shown[0]?.n.a.code) ? 0 : -1} onFocus={() => setCurrent(a.code)} role="row" aria-level={r.depth + 1} aria-expanded={r.childCount > 0 ? r.opened : undefined}
                  onKeyDown={(e) => onKey(e, r, i)}
                  onDoubleClick={() => { setDone(null); setDialog({ target: a }) }}
                  sx={{
                    opacity: a.disabled ? 0.55 : 1, '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: -2 },
                    bgcolor: a.summary && r.depth === 0 ? 'action.hover' : undefined,
                    '& .act': { opacity: 0, transition: 'opacity .15s' }, '&:hover .act, &:focus-within .act, &:focus-visible .act': { opacity: 1 },
                  }}
                >
                  <TableCell>
                    <Box sx={{ display: 'flex', alignItems: 'center', pl: `${r.depth * INDENT}px` }}>
                      {r.childCount > 0 ? (
                        <IconButton size="small" tabIndex={-1} disabled={!!q} aria-label={r.opened ? 'Collapse' : 'Expand'} onClick={() => toggle(a.code)} sx={{ mr: 0.5 }}>
                          <FiChevronRight style={{ transition: 'transform .18s ease', transform: r.opened ? 'rotate(90deg)' : 'none' }} />
                        </IconButton>
                      ) : <Box sx={{ width: 34, flexShrink: 0 }} />}
                      <Box sx={{ color: a.summary ? 'primary.main' : 'text.disabled', display: 'flex', mr: 1 }}>{a.summary ? <FiFolder /> : <FiFile />}</Box>
                      <Typography component="span" variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', color: 'text.secondary', mr: 1.25, minWidth: 64 }}>
                        <Marked text={a.code} q={q} />
                      </Typography>
                      <Typography component="span" variant="body2" sx={{ fontWeight: a.summary ? 600 : 400 }}><Marked text={a.name ?? ''} q={q} /></Typography>
                      {a.summary && !r.opened && r.childCount > 0 && !q && <Chip size="small" variant="outlined" label={r.childCount} sx={{ ml: 1, height: 20 }} />}
                    </Box>
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{a.summary ? 'Heading' : a.glOnly ? 'Account' : 'Ledger-controlled'}</TableCell>
                  <TableCell>
                    {a.disabled ? <Chip size="small" label="Switched off" />
                      : a.inUse ? <Tooltip title="The system posts to this account, so it cannot be switched off"><Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, color: 'text.secondary' }}><FiLock size={13} />System</Box></Tooltip>
                      : null}
                  </TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    <Box className="act" component="span">
                      {a.summary && !a.disabled && (
                        <Tooltip title={`New account under ${a.code}`}>
                          <IconButton size="small" tabIndex={-1} aria-label="Add under" onClick={() => { setDone(null); setDialog({ target: 'new', under: a }) }}><FiPlus /></IconButton>
                        </Tooltip>
                      )}
                      <Button size="small" tabIndex={-1} onClick={() => { setDone(null); setDialog({ target: a }) }}>Edit</Button>
                    </Box>
                  </TableCell>
                </TableRow>
              )
            })}
            {shown.length === 0 && !isFetching && (
              <TableRow><TableCell colSpan={4} align="center" sx={{ py: 4, color: 'text.secondary' }}>No account matches.</TableCell></TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
        {shown.length.toLocaleString()} of {(data?.length ?? 0).toLocaleString()} accounts shown · arrow keys move, → opens and ← closes a heading, double-click edits
      </Typography>
      {dialog && (
        <AccountDialog key={dialog.target === 'new' ? `new-${dialog.under?.code ?? ''}` : dialog.target.code} target={dialog.target} under={dialog.under}
          chart={data ?? []} onClose={() => setDialog(null)} onSaved={saved} />
      )}
    </Container>
  )
}

/** People without the right see the list as before. */
function ReadOnlyChart() {
  const { data, error } = useQuery({ queryKey: ['accounts', 1], queryFn: () => listAccounts(1) })
  const [filter, setFilter] = useState('')
  const rows = (data ?? []).filter((a) => !filter.trim() || a.id.code.includes(filter.trim()) || (a.description ?? '').toLowerCase().includes(filter.trim().toLowerCase()))
  return (
    <Container sx={{ py: 3 }}>
      <TextField size="small" label="Find by number or name" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ mb: 2, width: 300 }} />
      {error && <Alert severity="error" sx={{ mb: 2 }}>{(error as Error).message}</Alert>}
      <TableContainer component={Paper}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Number</TableCell><TableCell>Name</TableCell><TableCell>Under</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
          <TableBody>
            {rows.map((a) => (
              <TableRow key={a.id.code} hover>
                <TableCell>{a.id.code}</TableCell><TableCell>{a.description}</TableCell><TableCell>{a.summaryAccount}</TableCell><TableCell>{a.disabled ? 'Switched off' : 'Active'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Container>
  )
}

function AccountDialog({ target, under, chart, onClose, onSaved }: {
  target: ChartAccount | 'new'; under?: ChartAccount; chart: ChartAccount[]; onClose: () => void; onSaved: (msg: string, code: string) => void
}) {
  const editing = target === 'new' ? null : target
  const [code, setCode] = useState(under?.code ?? '')
  const [name, setName] = useState(editing?.name ?? '')
  const [summary, setSummary] = useState(false)
  const [parent, setParent] = useState<string | null>(editing?.parent ?? under?.code ?? null)
  const [glOnly, setGlOnly] = useState(editing?.glOnly ?? true)
  const [disabled, setDisabled] = useState(editing?.disabled ?? false)
  const [reason, setReason] = useState('')

  const usage = useQuery({ queryKey: ['chart-usage', editing?.code], queryFn: () => getChartUsage(editing!.code), enabled: editing != null })
  // only enabled summary accounts can hold others; for a new account, those whose number is a prefix of the typed one
  const parents = useMemo(() => chart.filter((a) => a.summary && !a.disabled && (editing ? editing.code.startsWith(a.code) && a.code !== editing.code : code.startsWith(a.code) && a.code !== code)), [chart, code, editing])
  const parentRow = parents.find((p) => p.code === parent) ?? null

  const save = useMutation({
    mutationFn: () => editing
      ? updateChartAccount(editing.code, { name, parent, glOnly, disabled, reason })
      : createChartAccount({ code, name, summary, parent, glOnly, reason }),
    onSuccess: (a) => onSaved(`${a.code} ${a.name} was saved`, a.code),
  })

  const typeOf = (c: string) => TYPES[Number(c.charAt(0))] ?? ''
  const ready = name.trim() !== '' && reason.trim() !== '' && parent != null && (editing != null || /^[1-6][0-9]{0,9}$/.test(code))

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); if (ready) save.mutate() }}>
        <DialogTitle>{editing ? `${editing.code} ${editing.name ?? ''}` : under ? `New account under ${under.code} ${under.name ?? ''}` : 'New account'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {editing == null ? (
              <TextField required autoFocus label="Account number" value={code}
                onChange={(e) => { const c = e.target.value.replace(/\D/g, ''); setCode(c); if (parent && !c.startsWith(parent)) setParent(null) }}
                helperText={code ? `${typeOf(code) || 'The first digit must be 1 to 6'}. It must start with the number of the heading it goes under.` : '1 assets, 2 liabilities, 3 equity, 4 revenues, 5 expenses, 6 net income'}
                slotProps={{ htmlInput: { maxLength: 10, inputMode: 'numeric' } }} />
            ) : (
              <Typography variant="body2" color="text.secondary">
                {editing.typeName} · {editing.summary ? 'Heading' : 'Account'} · the number cannot be changed.
              </Typography>
            )}
            <TextField required autoFocus={editing != null} label="Account name" value={name} onChange={(e) => setName(e.target.value)} slotProps={{ htmlInput: { maxLength: 50 } }} />
            <Autocomplete
              options={parents} value={parentRow} onChange={(_, v) => setParent(v?.code ?? null)}
              getOptionLabel={(p) => `${p.code} ${p.name ?? ''}`} isOptionEqualToValue={(a, b) => a.code === b.code}
              disabled={editing != null && (usage.data?.entries ?? 0) > 0}
              noOptionsText={editing == null && !code ? 'Type the account number first' : 'No heading matches this number'}
              renderInput={(p) => <TextField {...p} required label="Under heading" helperText={editing != null && (usage.data?.entries ?? 0) > 0 ? 'It has ledger entries, so it stays where it is.' : undefined} />}
            />
            {editing == null && (
              <FormControlLabel control={<Switch checked={summary} onChange={(e) => setSummary(e.target.checked)} />} label="Heading (totals the accounts under it, takes no postings)" />
            )}
            {(editing == null ? !summary : !editing.summary) && (
              <FormControlLabel control={<Switch checked={glOnly} onChange={(e) => setGlOnly(e.target.checked)} />}
                label="Takes journal entries from the general ledger (switch off for an account that a loan, savings or share capital ledger controls)" />
            )}
            {editing && (
              <>
                <FormControlLabel control={<Switch checked={!disabled} onChange={(e) => setDisabled(!e.target.checked)} />} label="In use" />
                {usage.data && (
                  <Typography variant="body2" color="text.secondary">
                    {usage.data.entries.toLocaleString()} ledger {usage.data.entries === 1 ? 'entry' : 'entries'}
                    {usage.data.children > 0 && ` · ${usage.data.children} account${usage.data.children === 1 ? '' : 's'} under it`}
                    {usage.data.usedBy.length > 0 && ` · used by the system as ${usage.data.usedBy.join(' and ')}, so it cannot be switched off`}
                  </Typography>
                )}
              </>
            )}
            <TextField required label="Reason for this change" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }} />
            {save.error && <Alert severity="error">{(save.error as Error).message}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={!ready || save.isPending}>Save</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

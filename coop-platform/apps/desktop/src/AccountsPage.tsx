import {
  Alert, Autocomplete, Box, Button, Chip, Drawer, FormControlLabel, IconButton, ListItemIcon, Menu, MenuItem, Stack, Switch,
  Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Tooltip, Typography, useMediaQuery,
} from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { memo, useCallback, useDeferredValue, useLayoutEffect, useMemo, useRef, useState, useTransition, type KeyboardEvent, type ReactNode } from 'react'
import {
  FiCheckCircle, FiChevronRight, FiDownload, FiEdit2, FiGrid, FiLayers, FiLock, FiMinusSquare, FiMoreHorizontal, FiPauseCircle, FiPlus, FiPlusSquare,
  FiPower, FiUpload, FiX, FiClock, FiCornerDownRight,
} from 'react-icons/fi'
import {
  createChartAccount, getChart, getChartHistory, getChartUsage, listAccounts, updateChartAccount, type ChartAccount,
} from './api'
import { AboutNote, Hinted, listTable, ScreenTitle, SearchField, Surface } from './appleUi'
import ChartUpload from './ChartUpload'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import { BusyArea } from './ui/Loading'
import { tint, RADIUS, SURFACE_RADIUS } from './theme'
import { suggestHeadings, nextCode, type HeadingSuggestion } from './ui/chartSuggest'
import { notifyError } from './ui/notices'
import {
  codeProblem, counts, matches, nameProblem, normalOf, passes, POSTING_WORDS, postingOf, toCsv, TYPE_NAMES, TYPE_ONE, type ChartFilter, type Posting, type Status,
} from './ui/chartTree'

const INDENT = 20
/** The colour of each type, for its mark and pill: assets blue, liabilities orange, equity purple, revenues green, expenses red, net income teal. */
const TYPE_TINT = ['gray', 'blue', 'orange', 'purple', 'green', 'red', 'teal'] as const

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
  return <>{text.slice(0, i)}<Box component="mark" sx={{ bgcolor: 'rgba(255, 204, 0, 0.38)', color: 'text.primary', borderRadius: '3px', px: 0.25 }}>{text.slice(i, i + q.length)}</Box>{text.slice(i + q.length)}</>
}

const colorOf = (type: number, dark: boolean) => tint[TYPE_TINT[type] ?? 'gray'][dark ? 1 : 0]

/** A small rounded pill in a colour, as the design's type, posting and status pills. */
function Pill({ color, children, outlined }: { color: string; children: ReactNode; outlined?: boolean }) {
  return (
    <Box component="span" sx={{
      display: 'inline-flex', alignItems: 'center', borderRadius: 99, px: 1, py: '2px', fontSize: 11, fontWeight: 600, whiteSpace: 'nowrap',
      color, bgcolor: outlined ? 'transparent' : `${color}1A`, border: outlined ? `1px solid ${color}55` : '1px solid transparent',
    }}>{children}</Box>
  )
}

function TypeMark({ type, summary, dark }: { type: number; summary: boolean; dark: boolean }) {
  const c = colorOf(type, dark)
  return (
    <Box aria-hidden sx={{ width: 22, height: 22, borderRadius: `${RADIUS}px`, flexShrink: 0, display: 'grid', placeItems: 'center', color: c, bgcolor: `${c}1F`, fontSize: 12 }}>
      {summary ? <FiLayers /> : <FiGrid />}
    </Box>
  )
}

type Panel = { mode: 'new'; under?: ChartAccount } | { mode: 'edit'; account: ChartAccount; tab: 'details' | 'history' }

/** The chart of accounts as a hierarchy. Everyone signed in can look; administrators and accountants can add accounts and change them. */
export default function AccountsPage() {
  const me = useMe()
  const canEdit = me.admin || me.roles.some((r) => r.replace('ROLE_', '') === 'ACCOUNTANT')
  const qc = useQueryClient()
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState<ChartFilter>({ type: 0, status: 'active', posting: 'all' })
  const [open, setOpen] = useState<Set<string> | null>(null) // null = the top two levels
  const [panel, setPanel] = useState<Panel | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [upload, setUpload] = useState(false) // advanced mode: add accounts from a list
  const [current, setCurrent] = useState<string | null>(null) // the one row in the tab order; arrow keys move it
  const [menu, setMenu] = useState<{ el: HTMLElement; a: ChartAccount } | null>(null)
  const dark = useMediaQuery('(prefers-color-scheme: dark)')

  const { data, error, isFetching } = useQuery({ queryKey: ['chart'], queryFn: getChart, enabled: canEdit })
  const all = useMemo(() => data ?? [], [data])
  const tree = useMemo(() => buildTree(all), [all])
  // the list follows the search a moment behind the typing, so typing never waits for a thousand rows
  const deferredSearch = useDeferredValue(search)
  const q = deferredSearch.trim().toLowerCase()
  // opening, closing and filtering redraw many rows: they run as a background update, shown by a bar over the list
  const [pending, startTransition] = useTransition()
  const busy = pending || isFetching || search !== deferredSearch
  const changeFilter = (f: ChartFilter | ((f: ChartFilter) => ChartFilter)) => startTransition(() => setFilter(f))
  const sums = useMemo(() => counts(all), [all])
  const typeCounts = useMemo(() => TYPE_NAMES.map((_, i) => all.filter((a) => (i === 0 || a.type === i) && passes(a, { ...filter, type: 0 })).length), [all, filter])

  const expanded = useMemo(() => open ?? new Set(tree.roots.map((r) => r.a.code)), [open, tree])

  // with a search or a filter: the accounts that pass and the headings above them, all opened; without: the tree as the person has opened it
  const filtering = q !== '' || filter.posting !== 'all' || filter.status === 'inactive'
  const shown = useMemo(() => {
    const keep = new Set<string>()
    if (filtering) {
      for (const n of tree.nodes.values()) {
        if (!passes(n.a, filter) || !matches(n.a, q)) continue
        for (let c: string | null = n.a.code; c && !keep.has(c); c = tree.nodes.get(c)?.a.parent ?? null) keep.add(c)
      }
    }
    const visible = (a: ChartAccount) => (filtering ? keep.has(a.code) : passes(a, filter))
    const out: { n: Node; depth: number; opened: boolean; childCount: number; hit: boolean }[] = []
    const walk = (n: Node, depth: number) => {
      if (!visible(n.a)) return
      const kids = n.kids.filter((k) => visible(k.a))
      const opened = filtering ? kids.length > 0 : expanded.has(n.a.code)
      out.push({ n, depth, opened, childCount: kids.length, hit: !filtering || (passes(n.a, filter) && matches(n.a, q)) })
      if (opened) kids.forEach((k) => walk(k, depth + 1))
    }
    tree.roots.forEach((r) => walk(r, 0))
    return out
  }, [tree, q, filter, filtering, expanded])
  const matching = useMemo(() => all.filter((a) => passes(a, filter) && matches(a, q)), [all, filter, q])

  const toggle = (code: string) => startTransition(() => setOpen((cur) => {
    const next = new Set(cur ?? expanded)
    if (next.has(code)) next.delete(code); else next.add(code)
    return next
  }))
  const expandAll = () => startTransition(() => setOpen(new Set([...tree.nodes.values()].filter((n) => n.kids.length > 0).map((n) => n.a.code))))
  const collapseAll = () => startTransition(() => setOpen(new Set()))

  const saved = (msg: string, code: string, close = true) => {
    if (close) setPanel(null)
    setDone(msg)
    // open the branch the account sits in so it can be seen
    setOpen((cur) => {
      const next = new Set(cur ?? expanded)
      const under = panel?.mode === 'new' ? panel.under?.code : undefined
      for (let c = tree.nodes.get(code)?.a.parent ?? under ?? null; c; c = tree.nodes.get(c)?.a.parent ?? null) next.add(c)
      return next
    })
    void qc.invalidateQueries({ queryKey: ['chart'] }); void qc.invalidateQueries({ queryKey: ['accounts'] }); void qc.invalidateQueries({ queryKey: ['chart-history', code] })
  }

  const latest = useRef({ shown, filtering, toggle, edit: (a: ChartAccount) => { void a }, addUnder: (a?: ChartAccount) => { void a } })
  const edit = (a: ChartAccount, tab: 'details' | 'history' = 'details') => { setDone(null); setPanel({ mode: 'edit', account: a, tab }) }
  const addUnder = (a?: ChartAccount) => { setDone(null); setPanel({ mode: 'new', under: a }) }
  // the rows' handlers read the newest state through this, updated after each draw
  useLayoutEffect(() => { latest.current = { shown, filtering, toggle, edit, addUnder } })
  const rowToggle = useCallback((code: string) => latest.current.toggle(code), [])
  const rowEdit = useCallback((a: ChartAccount) => latest.current.edit(a), [])
  const rowAdd = useCallback((a: ChartAccount) => latest.current.addUnder(a), [])
  const rowMenu = useCallback((el: HTMLElement, a: ChartAccount) => setMenu({ el, a }), [])
  const rowFocus = useCallback((code: string) => setCurrent(code), [])
  const rowKey = useCallback((e: KeyboardEvent<HTMLTableRowElement>, i: number) => {
    const r = latest.current.shown[i]
    if (!r) return
    if (e.key === 'Enter') { latest.current.edit(r.n.a); e.preventDefault(); return }
    const focus = (j: number) => { const el = (e.currentTarget.parentElement?.children[j] as HTMLElement | undefined); el?.focus(); e.preventDefault() }
    const filteringNow = latest.current.filtering
    if (e.key === 'ArrowDown') focus(i + 1)
    else if (e.key === 'ArrowUp') focus(i - 1)
    else if (e.key === 'ArrowRight' && r.childCount > 0 && !r.opened && !filteringNow) { latest.current.toggle(r.n.a.code); e.preventDefault() }
    else if (e.key === 'ArrowLeft' && !filteringNow) {
      if (r.opened && r.childCount > 0) { latest.current.toggle(r.n.a.code); e.preventDefault() }
      else if (r.n.a.parent) { const j = latest.current.shown.findIndex((x) => x.n.a.code === r.n.a.parent); if (j >= 0) focus(j) }
    }
  }, [])
  const exportCsv = () => {
    const url = URL.createObjectURL(new Blob([toCsv(matching)], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `chart-of-accounts-${new Date().toLocaleDateString('en-CA')}.csv`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  if (!canEdit) return <ReadOnlyChart />
  if (upload) {
    return (
      <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
        <ChartUpload
          onClose={() => setUpload(false)}
          onDone={(msg) => { setUpload(false); setDone(msg); setOpen(null); void qc.invalidateQueries({ queryKey: ['chart'] }); void qc.invalidateQueries({ queryKey: ['accounts'] }) }}
        />
      </Box>
    )
  }

  const cards: { label: string; value: number; note: string; icon: ReactNode; color: string; pick: () => void; on: boolean }[] = [
    { label: 'Total accounts', value: sums.total, note: 'Every account and heading', icon: <FiLayers />, color: tint.blue[0],
      pick: () => changeFilter({ type: 0, status: 'all', posting: 'all' }), on: filter.status === 'all' && filter.posting === 'all' && filter.type === 0 },
    { label: 'Posting accounts', value: sums.posting, note: 'Take postings and in use', icon: <FiGrid />, color: tint.purple[0],
      pick: () => changeFilter((f) => ({ ...f, status: 'active', posting: 'journal' })), on: filter.posting === 'journal' },
    { label: 'Active accounts', value: sums.active, note: 'Can be chosen for new entries', icon: <FiCheckCircle />, color: tint.green[0],
      pick: () => changeFilter((f) => ({ ...f, status: 'active', posting: 'all' })), on: filter.status === 'active' && filter.posting === 'all' },
    { label: 'Switched off', value: sums.inactive, note: 'Kept for their history', icon: <FiPauseCircle />, color: tint.orange[0],
      pick: () => changeFilter((f) => ({ ...f, status: 'inactive', posting: 'all' })), on: filter.status === 'inactive' },
  ]

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1180, mx: 'auto' }}>
        <Box sx={{ display: 'flex', alignItems: 'flex-end', gap: 1.5, flexWrap: 'wrap', mb: 2 }}>
          <Box sx={{ flex: 1, minWidth: 260 }}>
            <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase', mb: 0.5 }} color="text.secondary">Accounting</Typography>
            <ScreenTitle title="Chart of Accounts">
              The ledger accounts the books are kept in, as a hierarchy: each heading totals the accounts under it, and only posting accounts take entries. An account
              number starts with its heading&apos;s number and never changes; an account is switched off, never deleted, so its history stays.
            </ScreenTitle>
          </Box>
          <Button startIcon={<FiDownload />} onClick={exportCsv} disabled={matching.length === 0}>Export</Button>
          {me.admin && (
            <Tooltip title="Advanced mode: add many accounts from an Excel or CSV list">
              <Button startIcon={<FiUpload />} onClick={() => { setDone(null); setUpload(true) }}>Upload from Excel</Button>
            </Tooltip>
          )}
          <Button variant="contained" startIcon={<FiPlus />} onClick={() => addUnder()}>New account</Button>
        </Box>
        {done && <Alert severity="success" onClose={() => setDone(null)} sx={{ mb: 1.5 }}>{done}</Alert>}
        {error && <Alert severity="error" sx={{ mb: 1.5 }}>{(error as Error).message}</Alert>}

        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(4, 1fr)' }, gap: 1.25, mb: 2 }}>
          {cards.map((c) => (
            <Box key={c.label} component="button" type="button" onClick={c.pick} aria-pressed={c.on}
              sx={(t) => ({
                all: 'unset', cursor: 'pointer', ...glass(t), p: 1.5, display: 'block', outline: c.on ? `2px solid ${c.color}` : 'none', outlineOffset: -2,
                '&:focus-visible': { outline: `2px solid ${t.palette.primary.main}` },
              })}>
              <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <Box aria-hidden sx={{ width: 26, height: 26, borderRadius: `${SURFACE_RADIUS - 2}px`, display: 'grid', placeItems: 'center', color: c.color, bgcolor: `${c.color}1F`, fontSize: 15 }}>{c.icon}</Box>
                <Typography variant="caption" color="text.secondary">{c.note}</Typography>
              </Box>
              <Typography variant="h3" component="p" sx={{ mt: 1, fontVariantNumeric: 'tabular-nums' }}>{data ? c.value.toLocaleString() : '–'}</Typography>
              <Typography variant="body2" color="text.secondary">{c.label}</Typography>
            </Box>
          ))}
        </Box>

        <Surface label="Accounts">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', px: 1.5, pt: 1.5, pb: 1.25 }}>
            <SearchField value={search} onChange={setSearch} placeholder="Search by code, name or description" width={300} />
            <TextField select size="small" label="Type" value={filter.type} onChange={(e) => changeFilter((f) => ({ ...f, type: Number(e.target.value) }))} sx={{ width: 170 }}>
              {TYPE_NAMES.map((n, i) => <MenuItem key={i} value={i}>{i === 0 ? 'All types' : n} ({typeCounts[i].toLocaleString()})</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Status" value={filter.status} onChange={(e) => changeFilter((f) => ({ ...f, status: e.target.value as Status }))} sx={{ width: 150 }}>
              <MenuItem value="active">Active</MenuItem>
              <MenuItem value="inactive">Switched off</MenuItem>
              <MenuItem value="all">All statuses</MenuItem>
            </TextField>
            <TextField select size="small" label="Posting" value={filter.posting} onChange={(e) => changeFilter((f) => ({ ...f, posting: e.target.value as Posting | 'all' }))} sx={{ width: 170 }}>
              <MenuItem value="all">All</MenuItem>
              {(Object.keys(POSTING_WORDS) as Posting[]).map((p) => <MenuItem key={p} value={p}>{POSTING_WORDS[p]}</MenuItem>)}
            </TextField>
            <Box sx={{ flex: 1 }} />
            <Tooltip title="Open every heading"><span><IconButton aria-label="Expand all" onClick={expandAll} disabled={filtering || busy}><FiPlusSquare /></IconButton></span></Tooltip>
            <Tooltip title="Close every heading"><span><IconButton aria-label="Collapse all" onClick={collapseAll} disabled={filtering || busy}><FiMinusSquare /></IconButton></span></Tooltip>
          </Box>
          <BusyArea busy={busy} label={pending ? 'Opening the accounts' : 'Loading the accounts'}>
          <TableContainer sx={{ maxHeight: 'calc(100dvh - 390px)', minHeight: 240 }}>
            <Table size="small" stickyHeader role="treegrid" aria-label="Chart of accounts" sx={(t) => ({ ...listTable(t), '& .MuiTableHead-root .MuiTableCell-root.MuiTableCell-stickyHeader': { bgcolor: t.palette.background.paper } })}>
              <TableHead>
                <TableRow>
                  <TableCell sx={{ width: 190 }}>Account code</TableCell>
                  <TableCell>Account name</TableCell>
                  <TableCell sx={{ width: 100 }}>Type</TableCell>
                  <TableCell sx={{ width: 120 }}>Normal balance</TableCell>
                  <TableCell sx={{ width: 140 }}>Posting</TableCell>
                  <TableCell sx={{ width: 110 }}>Status</TableCell>
                  <TableCell sx={{ width: 84 }}><Box component="span" sx={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>Actions</Box></TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {shown.map((r, i) => (
                  <ChartRow key={r.n.a.code} a={r.n.a} index={i} depth={r.depth} opened={r.opened} childCount={r.childCount} hit={r.hit} q={q} filtering={filtering}
                    dark={dark} parentName={r.n.a.parent ? tree.nodes.get(r.n.a.parent)?.a.name ?? null : null} focusable={r.n.a.code === (current ?? shown[0]?.n.a.code)}
                    onToggle={rowToggle} onEdit={rowEdit} onAdd={rowAdd} onMenu={rowMenu} onFocusRow={rowFocus} onRowKey={rowKey} />
                ))}
                {shown.length === 0 && !isFetching && (
                  <TableRow><TableCell colSpan={7} align="center" sx={{ py: 4, color: 'text.secondary' }}>No account matches. Clear the search or choose another filter.</TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
          </BusyArea>
          <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap', px: 1.5, py: 1, borderTop: `1px solid ${t.palette.divider}` })}>
            <Typography variant="body2" color="text.secondary" role="status">
              {pending ? 'Opening the headings…' : filtering
                ? <>{matching.length.toLocaleString()} account{matching.length === 1 ? '' : 's'} match, shown with the headings above them</>
                : <>Showing {shown.length.toLocaleString()} of {typeCounts[filter.type].toLocaleString()} accounts; open a heading to see what is under it</>}
            </Typography>
            <Box sx={{ flex: 1 }} />
            <AboutNote label="Keyboard">↑ and ↓ move between rows, → opens and ← closes a heading, Enter opens the account. Double-click a row to open it too.</AboutNote>
          </Box>
        </Surface>
        <Typography variant="body2" sx={{ display: 'flex', alignItems: 'center', gap: 0.75, mt: 1.25, px: 0.5 }} color="text.secondary">
          Account numbers and their grouping should follow the cooperative&apos;s approved chart of accounts and accounting policies.
        </Typography>

        <Menu anchorEl={menu?.el} open={menu != null} onClose={() => setMenu(null)}>
          {menu && [
            <MenuItem key="edit" onClick={() => { edit(menu.a); setMenu(null) }}><ListItemIcon><FiEdit2 /></ListItemIcon>Open and change</MenuItem>,
            menu.a.summary && !menu.a.disabled && <MenuItem key="under" onClick={() => { addUnder(menu.a); setMenu(null) }}><ListItemIcon><FiPlus /></ListItemIcon>New account under it</MenuItem>,
            <MenuItem key="history" onClick={() => { edit(menu.a, 'history'); setMenu(null) }}><ListItemIcon><FiClock /></ListItemIcon>History</MenuItem>,
          ]}
        </Menu>
        {panel && (
          <AccountPanel key={panel.mode === 'new' ? `new-${panel.under?.code ?? ''}` : panel.account.code} panel={panel} chart={all} dark={dark}
            onClose={() => setPanel(null)} onSaved={saved} onTab={(tab) => setPanel((p) => (p && p.mode === 'edit' ? { ...p, tab } : p))} />
        )}
      </Box>
    </Box>
  )
}

/**
 * One account of the tree. Memoized: opening a heading draws again only the rows whose own account or place changed, not the whole chart. The handlers
 * passed in stay the same between draws.
 */
const ChartRow = memo(function ChartRow({ a, index, depth, opened, childCount, hit, q, filtering, dark, parentName, focusable, onToggle, onEdit, onAdd, onMenu, onFocusRow, onRowKey }: {
  a: ChartAccount; index: number; depth: number; opened: boolean; childCount: number; hit: boolean; q: string; filtering: boolean; dark: boolean
  parentName: string | null; focusable: boolean; onToggle: (code: string) => void; onEdit: (a: ChartAccount) => void; onAdd: (a: ChartAccount) => void
  onMenu: (el: HTMLElement, a: ChartAccount) => void; onFocusRow: (code: string) => void; onRowKey: (e: KeyboardEvent<HTMLTableRowElement>, i: number) => void
}) {
  const color = colorOf(a.type, dark)
  const posting = postingOf(a)
  return (
    <TableRow
      hover tabIndex={focusable ? 0 : -1} onFocus={() => onFocusRow(a.code)} role="row" aria-level={depth + 1} aria-expanded={childCount > 0 ? opened : undefined}
      onKeyDown={(e) => onRowKey(e, index)}
      onDoubleClick={() => onEdit(a)}
      sx={(t) => ({
        // a switched-off account (its Status says so) and a row that only leads to a search hit read quieter in the secondary colour, never faded
        '& .MuiTableCell-root': { color: a.disabled || !hit ? 'text.secondary' : undefined }, '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: -2 },
        bgcolor: a.summary && depth === 0 ? (t.palette.mode === 'dark' ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)') : undefined,
        '& .act': { opacity: 0, transition: 'opacity .15s' }, '&:hover .act, &:focus-within .act, &:focus-visible .act': { opacity: 1 },
      })}
    >
      <TableCell>
        <Box sx={{ display: 'flex', alignItems: 'center', pl: `${depth * INDENT}px` }}>
          {childCount > 0 ? (
            <IconButton size="small" tabIndex={-1} disabled={filtering} aria-label={opened ? 'Collapse' : 'Expand'} onClick={() => onToggle(a.code)} sx={{ mr: 0.25, p: 0.25 }}>
              <FiChevronRight style={{ transition: 'transform .18s ease', transform: opened ? 'rotate(90deg)' : 'none' }} />
            </IconButton>
          ) : <Box sx={{ width: 26, flexShrink: 0 }} />}
          <Typography component="span" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: a.summary ? 700 : 500 }}><Marked text={a.code} q={q} /></Typography>
          {a.inUse && (
            <Tooltip title="The system posts to this account (a product, bank or branch setting), so it cannot be switched off">
              <Box component="span" aria-label="Used by the system" sx={{ display: 'inline-flex', ml: 0.75, color: 'text.secondary' }}><FiLock size={12} /></Box>
            </Tooltip>
          )}
        </Box>
      </TableCell>
      <TableCell>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, minWidth: 0 }}>
          <TypeMark type={a.type} summary={a.summary} dark={dark} />
          <Box sx={{ minWidth: 0 }}>
            <Box component="button" type="button" tabIndex={-1} onClick={() => onEdit(a)}
              sx={{ all: 'unset', cursor: 'pointer', display: 'block', color: 'text.primary', fontSize: 13, fontWeight: a.summary ? 700 : 500, lineHeight: 1.35, '&:hover': { color: 'primary.main', textDecoration: 'underline' } }}>
              <Marked text={a.name ?? '(no name)'} q={q} />
            </Box>
            <Typography variant="caption" sx={{ lineHeight: 1.35, display: 'flex', alignItems: 'center', gap: 0.5 }} color="text.secondary" noWrap>
              {a.description
                ? <Marked text={a.description} q={q} />
                : a.parent ? <><FiCornerDownRight size={10} />Under {parentName ?? a.parent}</> : 'Top heading'}
            </Typography>
          </Box>
        </Box>
      </TableCell>
      <TableCell><Pill color={color}>{TYPE_ONE[a.type] ?? '–'}</Pill></TableCell>
      <TableCell>
        <Typography component="span">{a.normalBalance === 'D' ? 'Debit' : 'Credit'}</Typography>
        {a.contra && <Tooltip title={`A contra account: it runs against its type, whose accounts are normally ${normalOf(a.type) === 'D' ? 'debit' : 'credit'}`}><Box component="span" sx={{ ml: 0.75 }}><Pill color={tint.gray[0]} outlined>Contra</Pill></Box></Tooltip>}
      </TableCell>
      <TableCell>
        {posting === 'heading'
          ? <Typography component="span" color="text.secondary">Heading</Typography>
          : <Pill color={posting === 'journal' ? tint.indigo[0] : tint.teal[0]}>{POSTING_WORDS[posting]}</Pill>}
      </TableCell>
      <TableCell>{a.disabled ? <Pill color={tint.orange[0]}>Switched off</Pill> : <Pill color={tint.green[0]}>Active</Pill>}</TableCell>
      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
        <Box className="act" component="span">
          {a.summary && !a.disabled && (
            <Tooltip title={`New account under ${a.code}`}>
              <IconButton size="small" tabIndex={-1} aria-label={`Add an account under ${a.code}`} onClick={() => onAdd(a)}><FiPlus /></IconButton>
            </Tooltip>
          )}
        </Box>
        <IconButton size="small" tabIndex={-1} aria-label={`More for ${a.code}`} onClick={(e) => onMenu(e.currentTarget, a)}><FiMoreHorizontal /></IconButton>
      </TableCell>
    </TableRow>
  )
})

/** People without the right see the list as before. */
function ReadOnlyChart() {
  const { data, error } = useQuery({ queryKey: ['accounts', 1], queryFn: () => listAccounts(1) })
  const [filter, setFilter] = useState('')
  const rows = (data ?? []).filter((a) => !filter.trim() || a.id.code.includes(filter.trim()) || (a.description ?? '').toLowerCase().includes(filter.trim().toLowerCase()))
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1100, mx: 'auto' }}>
        <ScreenTitle title="Chart of Accounts" sx={{ mb: 2.5, px: 0.5 }}>The ledger accounts the books are kept in. Only administrators and accountants can change them.</ScreenTitle>
        {error && <Alert severity="error" sx={{ mb: 1.5 }}>{(error as Error).message}</Alert>}
        <Surface label="Chart of accounts">
          <Box sx={{ px: 1.5, py: 1.25 }}><SearchField value={filter} onChange={setFilter} placeholder="Find by number or name" width={280} /></Box>
          <Table size="small" sx={listTable}>
            <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Name</TableCell><TableCell>Under</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
            <TableBody>
              {rows.map((a) => (
                <TableRow key={a.id.code} hover>
                  <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{a.id.code}</TableCell><TableCell>{a.description}</TableCell>
                  <TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{a.summaryAccount}</TableCell><TableCell>{a.disabled ? 'Switched off' : 'Active'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Surface>
      </Box>
    </Box>
  )
}

/** A numbered part of the account form, as the design's 01, 02, 03. */
function Part({ n, title, note, children, partRef }: { n: string; title: string; note: string; children: ReactNode; partRef?: React.Ref<HTMLDivElement> }) {
  return (
    <Box ref={partRef} component="section" aria-label={title} sx={{ scrollMarginTop: 12 }}>
      <Box sx={{ display: 'flex', gap: 1.25, alignItems: 'flex-start', mb: 1.75 }}>
        <Box sx={(t) => ({ fontSize: 11, fontWeight: 700, color: 'primary.main', bgcolor: t.palette.mode === 'dark' ? 'rgba(10,132,255,0.16)' : 'rgba(10,108,220,0.08)', borderRadius: `${RADIUS}px`, px: 0.75, py: 0.5 })}>{n}</Box>
        <Box>
          <Typography sx={{ fontWeight: 700 }}>{title}</Typography>
          <Typography variant="body2" color="text.secondary">{note}</Typography>
        </Box>
      </Box>
      <Stack spacing={1.75}>{children}</Stack>
    </Box>
  )
}

/** A choice card, as the design's Debit and Credit. */
function Choice({ on, onClick, letter, title, note, color, disabled }: { on: boolean; onClick: () => void; letter: string; title: string; note: string; color: string; disabled?: boolean }) {
  return (
    <Box component="button" type="button" role="radio" aria-checked={on} disabled={disabled} onClick={onClick}
      sx={(t) => ({
        all: 'unset', cursor: disabled ? 'default' : 'pointer', display: 'flex', alignItems: 'center', gap: 1, p: 1, borderRadius: `${SURFACE_RADIUS - 2}px`, flex: 1, minWidth: 0,
        border: `1px solid ${on ? t.palette.primary.main : t.palette.divider}`, bgcolor: on ? (t.palette.mode === 'dark' ? 'rgba(10,132,255,0.12)' : 'rgba(10,108,220,0.05)') : 'transparent',
        opacity: disabled && !on ? 0.5 : 1, '&:focus-visible': { outline: `2px solid ${t.palette.primary.main}`, outlineOffset: 1 },
      })}>
      <Box aria-hidden sx={{ width: 24, height: 24, borderRadius: `${RADIUS}px`, display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 12, color, bgcolor: `${color}1F`, flexShrink: 0 }}>{letter}</Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="subtitle2" component="p">{title}</Typography>
        <Typography variant="caption" color="text.secondary">{note}</Typography>
      </Box>
    </Box>
  )
}

/**
 * The account panel on the right, after the design: 01 account details (name, description, number), 02 classification (type, heading, normal balance),
 * 03 posting and status, and the reason. A new account's number is generated from the heading it goes under; it can be changed. An existing account also has its
 * history from the audit log.
 */
function AccountPanel({ panel, chart, dark, onClose, onSaved, onTab }: {
  panel: Panel; chart: ChartAccount[]; dark: boolean; onClose: () => void; onSaved: (msg: string, code: string, close?: boolean) => void; onTab: (tab: 'details' | 'history') => void
}) {
  const editing = panel.mode === 'edit' ? panel.account : null
  const under = panel.mode === 'new' ? panel.under : undefined
  const narrow = useMediaQuery('(max-width:600px)')
  const [name, setName] = useState(editing?.name ?? '')
  const [description, setDescription] = useState(editing?.description ?? '')
  const [parent, setParent] = useState<string | null>(editing?.parent ?? under?.code ?? null)
  const [type, setType] = useState<number>(editing?.type ?? under?.type ?? 0)
  const parentRow = chart.find((a) => a.code === parent) ?? null
  // a new account's number follows its heading until the person types another
  const [ownCode, setOwnCode] = useState<string | null>(null)
  const autoCode = parentRow ? nextCode(chart, parentRow) ?? '' : ''
  const code = editing ? editing.code : ownCode ?? autoCode
  const [summary, setSummary] = useState(editing?.summary ?? false)
  const [glOnly, setGlOnly] = useState(editing?.glOnly ?? true)
  const [disabled, setDisabled] = useState(editing?.disabled ?? false)
  const typeNow = editing ? editing.type : parentRow ? parentRow.type : type
  const [balance, setBalance] = useState<'D' | 'C' | null>(editing ? editing.normalBalance : null)
  const normal = balance ?? (typeNow ? normalOf(typeNow) : 'D')
  const [reason, setReason] = useState('')
  const [touched, setTouched] = useState(false)
  const parts = useRef<(HTMLDivElement | null)[]>([])
  const goTo = (i: number) => parts.current[i]?.scrollIntoView({ behavior: 'smooth', block: 'start' })

  const usage = useQuery({ queryKey: ['chart-usage', editing?.code], queryFn: () => getChartUsage(editing!.code), enabled: editing != null })
  const history = useQuery({ queryKey: ['chart-history', editing?.code], queryFn: () => getChartHistory(editing!.code), enabled: editing != null && panel.mode === 'edit' && panel.tab === 'history' })
  const moving = editing != null && (usage.data?.entries ?? 0) === 0
  // headings it can go under: in use, of its type when one is chosen; an existing account only under headings its number starts with
  const parents = chart.filter((a) => a.summary && !a.disabled && (!typeNow || a.type === typeNow)
    && (editing ? editing.code.startsWith(a.code) && a.code !== editing.code : true)).sort(byCode)
  const suggested = (editing ? [] : suggestHeadings(chart.filter((a) => !typeNow || a.type === typeNow), name, ownCode ?? ''))
  const place = (s: HeadingSuggestion) => { setParent(s.heading.code); setType(s.heading.type); setOwnCode(null) }

  const codeIssue = editing ? null : codeProblem(code, parentRow, chart)
  const nameIssue = nameProblem(name, parent, chart, editing?.code)
  const steps = [
    { label: 'Account details', note: 'Name and number', ok: name.trim() !== '' && !nameIssue && !codeIssue },
    { label: 'Classification', note: 'Type and heading', ok: parent != null },
    { label: 'Settings', note: 'Posting and status', ok: true },
  ]
  const ready = steps.every((s) => s.ok) && reason.trim() !== ''
  const missing = [!name.trim() && 'the account name', nameIssue && 'another name', !editing && codeIssue && 'a free account number', parent == null && 'its heading', !reason.trim() && 'the reason'].filter(Boolean)

  const save = useMutation({
    onError: notifyError,
    mutationFn: () => editing
      ? updateChartAccount(editing.code, { name, parent, glOnly, disabled, reason, normalBalance: normal, description })
      : createChartAccount({ code, name, summary, parent, glOnly: summary ? false : glOnly, reason, normalBalance: normal, description }),
    onSuccess: (a) => onSaved(editing ? `${a.code} ${a.name} was saved.` : `${a.code} ${a.name} was added under ${a.parent}.`, a.code),
  })

  const title = editing ? `${editing.code} ${editing.name ?? ''}` : 'Create new account'
  return (
    <Drawer anchor="right" open onClose={save.isPending ? undefined : onClose}
      slotProps={{ paper: { sx: { width: narrow ? '100%' : 460, display: 'flex', flexDirection: 'column' } } }}>
      <Box sx={{ px: 2.5, pt: 2.25, pb: 1.5, display: 'flex', alignItems: 'flex-start', gap: 1 }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: '0.08em', textTransform: 'uppercase' }} color="text.secondary">Chart of accounts</Typography>
          <Typography component="h2" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mt: 0.5 }} noWrap>{title}</Typography>
          <Typography variant="body2" color="text.secondary">
            {editing ? `${TYPE_ONE[editing.type]} ${editing.summary ? 'heading' : 'account'} · the number never changes` : under ? `Under ${under.code} ${under.name ?? ''}` : 'Add an account to the ledger structure.'}
          </Typography>
        </Box>
        <IconButton aria-label="Close" onClick={onClose} disabled={save.isPending}><FiX /></IconButton>
      </Box>
      {editing && (
        <Tabs value={panel.mode === 'edit' ? panel.tab : 'details'} onChange={(_, v) => onTab(v)} sx={(t) => ({ px: 2, borderBottom: `1px solid ${t.palette.divider}` })}>
          <Tab value="details" label="Details" />
          <Tab value="history" label="History" />
        </Tabs>
      )}
      {panel.mode === 'edit' && panel.tab === 'history' ? (
        <Box sx={{ flex: 1, overflowY: 'auto', px: 2.5, py: 2 }}>
          {history.isLoading && <Typography color="text.secondary">Loading the history…</Typography>}
          {history.error && <Alert severity="error">{(history.error as Error).message}</Alert>}
          {history.data?.length === 0 && <Typography color="text.secondary">No change has been recorded for this account since the audit log began; it came from the old system.</Typography>}
          <Stack spacing={1.5}>
            {history.data?.map((h, i) => (
              <Box key={i} sx={(t) => ({ ...glass(t), p: 1.5 })}>
                <Typography variant="subtitle2" component="p">{HISTORY_WORDS[h.action] ?? h.action}</Typography>
                <Typography variant="body2" color="text.secondary">{h.at.replace('T', ' ').slice(0, 19)} · {h.by}{h.reason ? ` · “${h.reason}”` : ''}</Typography>
                {h.changes.length > 0 && (
                  <Table size="small" sx={{ mt: 1, '& td, & th': { fontSize: 12, px: 0.75 } }}>
                    <TableHead><TableRow><TableCell>Field</TableCell><TableCell>Before</TableCell><TableCell>After</TableCell></TableRow></TableHead>
                    <TableBody>
                      {h.changes.map((c) => (
                        <TableRow key={c.field}><TableCell>{c.label}</TableCell><TableCell>{c.before ?? '–'}</TableCell><TableCell>{c.after ?? '–'}</TableCell></TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </Box>
            ))}
          </Stack>
        </Box>
      ) : (
        <>
          <Box role="list" aria-label="Steps" sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1, px: 2.5, pb: 1.5, borderBottom: `1px solid ${t.palette.divider}`, pt: editing ? 1.5 : 0 })}>
            {steps.map((s, i) => (
              <Box key={s.label} sx={{ display: 'contents' }}>
                {i > 0 && <Box aria-hidden sx={(t) => ({ flex: 1, minWidth: 8, height: '1px', bgcolor: t.palette.divider })} />}
                <Box role="listitem" component="button" type="button" onClick={() => goTo(i)}
                  aria-label={`${i + 1} ${s.label}${s.ok ? ', done' : ''}`}
                  sx={{ all: 'unset', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 0.75, whiteSpace: 'nowrap', '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', borderRadius: `${RADIUS}px` } }}>
                  <Box sx={(t) => ({
                    width: 22, height: 22, borderRadius: '50%', display: 'grid', placeItems: 'center', fontSize: 11, fontWeight: 700,
                    color: s.ok ? '#fff' : 'text.secondary', bgcolor: s.ok ? t.palette.success.main : t.palette.action.hover,
                  })}>{s.ok ? '✓' : i + 1}</Box>
                  <Box sx={{ display: { xs: 'none', sm: 'block' } }}>
                    <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.2 }}>{s.label}</Typography>
                    <Typography variant="caption" sx={{ lineHeight: 1.2 }} color="text.secondary">{s.note}</Typography>
                  </Box>
                </Box>
              </Box>
            ))}
          </Box>
          <Box component="form" id="account-form" onSubmit={(e) => { e.preventDefault(); setTouched(true); if (ready) save.mutate() }}
            sx={{ flex: 1, overflowY: 'auto', px: 2.5, py: 2, display: 'grid', gap: 2.5 }}>
            <Part n="01" title="Basic information" note="A clear name, what the account is for, and its number." partRef={(el) => { parts.current[0] = el }}>
              <TextField required autoFocus label="Account name" value={name} onChange={(e) => setName(e.target.value)} slotProps={{ htmlInput: { maxLength: 50 } }}
                error={!!nameIssue || (touched && !name.trim())} helperText={nameIssue ?? (touched && !name.trim() ? 'Give the account name.' : undefined)} />
              <TextField label="Description (optional)" value={description} onChange={(e) => setDescription(e.target.value)} multiline minRows={2}
                slotProps={{ htmlInput: { maxLength: 250 } }} helperText={`${description.length} / 250`} />
              {editing ? (
                <TextField label="Account code" value={editing.code} disabled helperText="The number never changes: the ledger points at it." />
              ) : (
                <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
                  <TextField required label="Account code" value={code} sx={{ flex: 1 }}
                    onChange={(e) => setOwnCode(e.target.value.replace(/\D/g, ''))}
                    error={!!codeIssue && (touched || ownCode != null)}
                    helperText={(touched || ownCode != null) && codeIssue ? codeIssue
                      : ownCode == null ? (parentRow ? `Generated: the next free number under ${parentRow.code}.` : 'Generated once the heading is chosen (02).') : 'Typed by you. Auto-generate goes back to the next free number.'}
                    slotProps={{ htmlInput: { maxLength: 10, inputMode: 'numeric', 'aria-describedby': undefined } }} />
                  <Button onClick={() => setOwnCode(null)} disabled={!parentRow || ownCode == null}>Auto-generate</Button>
                </Box>
              )}
            </Part>

            <Part n="02" title="Account classification" note="Where the account belongs in the ledger." partRef={(el) => { parts.current[1] = el }}>
              <TextField select required label="Account type" value={typeNow || ''} disabled={editing != null || parentRow != null}
                onChange={(e) => { setType(Number(e.target.value)); setParent(null); setOwnCode(null) }}
                helperText={editing ? undefined : parentRow ? 'From its heading.' : 'Choosing a type narrows the headings below.'}>
                {TYPE_ONE.map((t, i) => i > 0 && <MenuItem key={i} value={i}>{i} · {t}</MenuItem>)}
              </TextField>
              <Autocomplete
                options={parents} value={parentRow} onChange={(_, v) => { setParent(v?.code ?? null); if (v) setType(v.type); setOwnCode(null) }}
                getOptionLabel={(p) => `${p.code} ${p.name ?? ''}`} isOptionEqualToValue={(a, b) => a.code === b.code}
                disabled={editing != null && !moving}
                renderOption={(props, p) => <li {...props} key={p.code}><Box sx={{ pl: `${Math.max(0, p.code.length - 1) * 6}px` }}><Typography>{p.code} {p.name}</Typography></Box></li>}
                renderInput={(p) => <TextField {...p} required label="Heading it goes under" error={touched && parent == null}
                  helperText={editing && !moving ? 'It has ledger entries, so it stays where it is.' : touched && parent == null ? 'Choose the heading this account goes under.' : 'The heading that totals it.'} />}
              />
              {suggested.length > 0 && (
                <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 0.75, mt: '-6px !important' }}>
                  <Typography variant="body2" color="text.secondary">Suggested:</Typography>
                  {suggested.map((s) => (
                    <Tooltip key={s.heading.code} describeChild title={`${s.why}. Puts it under ${s.heading.code} ${s.heading.name ?? ''} as ${s.nextCode}.`}>
                      <Chip size="small" label={`${s.heading.code} ${s.heading.name ?? ''}`} onClick={() => place(s)}
                        color={parent === s.heading.code ? 'primary' : 'default'} variant={parent === s.heading.code ? 'filled' : 'outlined'} />
                    </Tooltip>
                  ))}
                </Box>
              )}
              <Box role="radiogroup" aria-label="Normal balance">
                <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.75 }}>Normal balance</Typography>
                <Box sx={{ display: 'flex', gap: 1, flexDirection: { xs: 'column', sm: 'row' } }}>
                  <Choice on={normal === 'D'} onClick={() => setBalance('D')} letter="D" title="Debit" note="Assets and expenses" color={tint.blue[dark ? 1 : 0]} />
                  <Choice on={normal === 'C'} onClick={() => setBalance('C')} letter="C" title="Credit" note="Liabilities, equity and income" color={tint.green[dark ? 1 : 0]} />
                </Box>
                <Typography variant="body2" sx={{ mt: 0.75 }} color={typeNow && normal !== normalOf(typeNow) ? 'warning.main' : 'text.secondary'}>
                  {!typeNow ? 'Suggested from the type once it is chosen.'
                    : normal === normalOf(typeNow) ? `${normal === 'D' ? 'Debit' : 'Credit'}, as every ${TYPE_ONE[typeNow].toLowerCase()} account.`
                    : `A contra account: it runs against its type (normally ${normalOf(typeNow) === 'D' ? 'debit' : 'credit'}), as an allowance for losses or accumulated depreciation does.`}
                </Typography>
              </Box>
            </Part>

            <Part n="03" title="Posting and status" note="How the account can be used." partRef={(el) => { parts.current[2] = el }}>
              {editing == null ? (
                <FormControlLabel control={<Switch checked={!summary} onChange={(e) => setSummary(!e.target.checked)} />}
                  label={<Box><Typography variant="subtitle2" component="p">Posting account</Typography><Typography variant="body2" color="text.secondary">Takes entries. Off: a heading that only totals the accounts under it.</Typography></Box>} />
              ) : (
                <Typography>{editing.summary ? 'A heading: it totals the accounts under it and takes no entries.' : 'A posting account: it takes entries.'}</Typography>
              )}
              {!(editing ? editing.summary : summary) && (
                <FormControlLabel control={<Switch checked={glOnly} onChange={(e) => setGlOnly(e.target.checked)} />}
                  label={<Box><Typography variant="subtitle2" component="p">Takes journal entries</Typography><Typography variant="body2" color="text.secondary">Off: a subsidiary ledger account, posted to only through members&apos; loans, savings or share capital.</Typography></Box>} />
              )}
              {editing && (
                <FormControlLabel control={<Switch checked={!disabled} onChange={(e) => setDisabled(!e.target.checked)} disabled={!disabled && (usage.data?.usedBy.length ?? 0) > 0} />}
                  label={<Box><Typography variant="subtitle2" component="p">Account is active</Typography><Typography variant="body2" color="text.secondary">
                    {(usage.data?.usedBy.length ?? 0) > 0 ? `Used by the system as ${usage.data!.usedBy.join(' and ')}, so it stays on.` : 'Switched off, it cannot be chosen for new entries; its history stays.'}
                  </Typography></Box>} />
              )}
              {editing && usage.data && (
                <Typography variant="body2" color="text.secondary">
                  {usage.data.entries.toLocaleString()} ledger {usage.data.entries === 1 ? 'entry' : 'entries'}{usage.data.children > 0 && ` · ${usage.data.children} account${usage.data.children === 1 ? '' : 's'} under it`}
                </Typography>
              )}
              <AboutNote label="Good to know">Headings group accounts and take no entries; post to the accounts under them. An account that loans, savings or share capital post to is kept by their subsidiary ledger: a journal that changes it must be balanced by a memo, so the members&apos; accounts still agree with it.</AboutNote>
            </Part>

            <Hinted tip="Kept in the audit log with every field before and after">
              <TextField required label="Reason for this change" value={reason} onChange={(e) => setReason(e.target.value)} slotProps={{ htmlInput: { maxLength: 255 } }}
                error={touched && !reason.trim()} helperText={touched && !reason.trim() ? 'Give the reason for this change.' : undefined} />
            </Hinted>
          </Box>
          <Box sx={(t) => ({ px: 2.5, py: 1.5, borderTop: `1px solid ${t.palette.divider}`, display: 'flex', alignItems: 'center', gap: 1 })}>
            <Typography variant="body2" sx={{ flex: 1 }} color="text.secondary">{!ready && touched && missing.length > 0 ? `Still needed: ${missing.join(', ')}.` : ''}</Typography>
            <Button onClick={onClose} disabled={save.isPending}>Cancel</Button>
            <Button type="submit" form="account-form" variant="contained" disabled={save.isPending} startIcon={editing ? <FiPower /> : <FiPlus />}>
              {save.isPending ? 'Saving…' : editing ? 'Save changes' : 'Save account'}
            </Button>
          </Box>
        </>
      )}
    </Drawer>
  )
}

const HISTORY_WORDS: Record<string, string> = {
  'chart.account.create': 'Account created', 'chart.account.update': 'Account changed', 'chart.account.disable': 'Switched off', 'chart.account.enable': 'Switched on',
  'chart.account.import': 'Added from an uploaded list',
}

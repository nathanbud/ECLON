import { Alert, Box, Chip, CircularProgress, Dialog, TextField, ToggleButton, ToggleButtonGroup, Typography, type Theme } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { listFoDrafts, listFoTransactions, type FoDraftRow, type FoSetup, type FoSummary } from './api'
import { glass } from './glass'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const dark = (t: Theme) => t.palette.mode === 'dark'

type Row =
  | { kind: 'txn'; key: string; title: string; sub: string; total: number; txn: FoSummary }
  | { kind: 'draft'; key: string; title: string; sub: string; total: number; balanced: boolean; draft: FoDraftRow }

function Body({ setup, typeIds, onPickTransaction, onPickDraft }: {
  setup: FoSetup
  typeIds: number[]
  onPickTransaction: (t: FoSummary, all: FoSummary[]) => void
  onPickDraft: (d: FoDraftRow) => void
}) {
  const [date, setDate] = useState(setup.systemDate)
  const [input, setInput] = useState('')
  const [q, setQ] = useState('')
  const [what, setWhat] = useState<'txn' | 'draft'>('txn')
  const [active, setActive] = useState(0)
  const activeRef = useRef<HTMLLIElement>(null)

  // Debounce typing so each keystroke doesn't query the ledger.
  useEffect(() => {
    const t = setTimeout(() => setQ(input.trim()), 250)
    return () => clearTimeout(t)
  }, [input])

  const txns = useQuery({
    queryKey: ['fo-find', date, typeIds, q], queryFn: () => listFoTransactions(date, typeIds, q),
    enabled: what === 'txn' && !!date, placeholderData: (p) => p,
  })
  const drafts = useQuery({ queryKey: ['fo-drafts'], queryFn: listFoDrafts, enabled: what === 'draft' })

  const rows: Row[] = what === 'txn'
    ? (txns.data ?? []).map((t) => ({
        kind: 'txn' as const, key: `${t.year}-${t.module}-${t.code}-${t.ctlNo}`, title: `${t.type}  ·  ${t.ctlNo}`,
        sub: `${t.time?.slice(0, 5) ?? ''}${t.clientName ? ` · ${t.clientName}` : ''}${t.explanation ? ` · ${t.explanation}` : ''}`, total: t.total, txn: t,
      }))
    : (drafts.data ?? []).map((d) => ({
        kind: 'draft' as const, key: `draft-${d.id}`, title: `${d.type}  ·  draft ${d.id}`, sub: `${d.date}${d.clientName ? ` · ${d.clientName}` : ''}`,
        total: d.debit, balanced: d.balanced, draft: d,
      }))
  const loading = what === 'txn' ? txns.isFetching : drafts.isFetching
  const error = (what === 'txn' ? txns.error : drafts.error) as Error | null

  useEffect(() => { activeRef.current?.scrollIntoView({ block: 'nearest' }) }, [active, rows.length])

  const pick = (r: Row) => {
    if (r.kind === 'txn') onPickTransaction(r.txn, rows.flatMap((x) => (x.kind === 'txn' ? [x.txn] : [])))
    else onPickDraft(r.draft)
  }
  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((a) => Math.min(a + 1, Math.max(rows.length - 1, 0))) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)) }
    else if (e.key === 'Enter' && rows[active]) { e.preventDefault(); pick(rows[active]) }
  }

  return (
    <Box>
      <Box sx={{ p: 2, pb: 1.5 }}>
        <Typography component="h2" sx={{ fontSize: 17, fontWeight: 700, letterSpacing: '-0.01em', mb: 1.25 }}>Find transaction</Typography>
        <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', alignItems: 'center' }}>
          <ToggleButtonGroup size="small" exclusive value={what} onChange={(_, v) => { if (v) { setWhat(v); setActive(0) } }} aria-label="What to find">
            <ToggleButton value="txn" sx={{ textTransform: 'none', px: 1.5 }}>Posted</ToggleButton>
            <ToggleButton value="draft" sx={{ textTransform: 'none', px: 1.5 }}>My drafts</ToggleButton>
          </ToggleButtonGroup>
          {what === 'txn' && (
            <>
              <TextField label="Date" type="date" value={date} onChange={(e) => { setDate(e.target.value); setActive(0) }} onKeyDown={onKeyDown}
                slotProps={{ inputLabel: { shrink: true } }} sx={{ width: 170 }} />
              <TextField
                label="Control no., client, or words" value={input} autoFocus fullWidth sx={{ flex: 1, minWidth: 200 }}
                onChange={(e) => { setInput(e.target.value); setActive(0) }} onKeyDown={onKeyDown}
                slotProps={{ input: { endAdornment: loading ? <CircularProgress size={16} /> : null } }}
              />
            </>
          )}
        </Box>
      </Box>

      <Box sx={(t) => ({ borderTop: `1px solid ${dark(t) ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.06)'}`, minHeight: 140, maxHeight: 380, overflow: 'auto' })}>
        {error && <Alert severity="error" sx={{ m: 1.5 }}>{error.message}</Alert>}
        {!error && !loading && rows.length === 0 && (
          <Typography sx={{ p: 2.5, fontSize: 13.5 }} color="text.secondary">
            {what === 'txn' ? 'No transactions found for this date.' : 'You have no saved drafts.'}
          </Typography>
        )}
        <Box component="ul" role="listbox" aria-label="Matching transactions" sx={{ listStyle: 'none', m: 0, p: 0.75, display: 'grid', gap: 0.25 }}>
          {rows.map((r, i) => (
            <Box
              component="li" key={r.key} role="option" aria-selected={i === active} ref={i === active ? activeRef : undefined}
              onClick={() => pick(r)} onMouseMove={() => setActive(i)}
              sx={(t) => ({
                display: 'flex', alignItems: 'center', gap: 1.5, px: 1.5, py: 0.9, borderRadius: '5px', cursor: 'pointer',
                backgroundColor: i === active ? (dark(t) ? 'rgba(255,255,255,0.1)' : 'rgba(0,100,220,0.09)') : 'transparent',
              })}
            >
              <Box sx={{ minWidth: 0, flex: 1 }}>
                <Typography noWrap sx={{ fontSize: 14, fontWeight: 600 }}>{r.title}</Typography>
                <Typography noWrap sx={{ fontSize: 12.5 }} color="text.secondary">{r.sub}</Typography>
              </Box>
              {r.kind === 'draft' && <Chip size="small" variant="outlined" color={r.balanced ? 'success' : 'warning'} label={r.balanced ? 'Balanced' : 'Out of balance'} sx={{ height: 20, fontSize: 11 }} />}
              <Typography sx={{ fontSize: 13.5, fontVariantNumeric: 'tabular-nums', fontWeight: 600 }}>{money(r.total)}</Typography>
            </Box>
          ))}
        </Box>
        {what === 'txn' && rows.length >= 200 && (
          <Typography sx={{ px: 2.5, pb: 1.5, fontSize: 12.5 }} color="text.secondary">Showing the latest 200. Type a control number, client or words to narrow the list.</Typography>
        )}
      </Box>
    </Box>
  )
}

/** Find a posted transaction by date, or one of your own drafts. Its contents are mounted only while open, so each use starts fresh. */
export default function FoFindDialog({ open, onClose, setup, typeIds, onPickTransaction, onPickDraft }: {
  open: boolean
  onClose: () => void
  setup: FoSetup
  typeIds: number[]
  onPickTransaction: (t: FoSummary, all: FoSummary[]) => void
  onPickDraft: (d: FoDraftRow) => void
}) {
  return (
    <Dialog
      open={open} onClose={onClose} fullWidth maxWidth="sm" aria-label="Find transaction"
      slotProps={{
        backdrop: { sx: (t: Theme) => ({ backdropFilter: 'blur(10px)', backgroundColor: dark(t) ? 'rgba(0,0,0,0.5)' : 'rgba(40,50,80,0.28)' }) },
        paper: { sx: (t: Theme) => ({ ...glass(t, { elevated: true }), alignSelf: 'flex-start', mt: '10vh', overflow: 'hidden' }) },
      }}
    >
      <Body setup={setup} typeIds={typeIds} onPickTransaction={onPickTransaction} onPickDraft={onPickDraft} />
    </Dialog>
  )
}

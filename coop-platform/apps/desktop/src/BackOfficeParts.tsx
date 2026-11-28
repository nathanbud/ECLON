import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Divider, IconButton, MenuItem, Stack, Table, TableBody, TableCell, TableHead, TableRow, TableSortLabel, TextField, Tooltip, Typography } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { FiArrowDown, FiArrowUp } from 'react-icons/fi'
import type { BackOfficeType } from './api'
import { DEFAULT_SORT, describeSort, nextSort, SORT_KEYS, sortEntries, type Sort, type SortKey } from './backOfficeSort'
import { EntryStepStatus } from './EntryStepStatus'
import { glass } from './glass'
import { fmtDate } from './profile/format'
import { money } from './ui/format'
import { labelOf } from './ui/labels'

/** The pieces both Back Office windows (General Journal Transactions and Disbursement Transactions) are made of, after the old system's windows. */

/** An entry as a Back Office list shows it, whichever its type: a journal, a memo or a voucher. */
export interface Entry {
  id: number; reference: string; date: string; description: string; status: string; createdBy: string; approvedBy: string | null; postedBy: string | null
  total: number; reversal: boolean; documentNo: string | null
}

/** The Trans. type list: every type of the old system with its code and how much the old data used it; the ones not made here are greyed but can be chosen to see why. */
export function TypePicker({ types, value, onChange, extra }: {
  types: BackOfficeType[]; value: string; onChange: (v: string) => void
  /** A choice after the old system's types, below a line (the journal window's Penalty Waiver). */
  extra?: { value: string; label: string; note: string }
}) {
  const name = (v: string) => (extra && v === extra.value ? extra.label : types.find((t) => String(t.id) === v)?.name ?? '')
  return (
    <TextField select size="small" label="Trans. type" value={value} onChange={(e) => onChange(e.target.value)} sx={{ width: 260 }}
      slotProps={{ select: { renderValue: (v) => name(String(v)), MenuProps: { slotProps: { paper: { sx: { maxHeight: 440 } } } } } }}>
      {types.map((t) => {
        const here = !!t.entry
        return (
          <MenuItem key={t.id} value={String(t.id)} sx={{ display: 'block', py: 0.5 }}>
            <Typography sx={{ fontWeight: here ? 600 : 400 }} color={here ? 'text.primary' : 'text.secondary'}>{t.name}</Typography>
            <Typography variant="caption" color="text.secondary">
              {[t.code, t.entries > 0 ? `${t.entries.toLocaleString('en-US')} in the old data` : 'never used', here ? null : t.madeElsewhere ? 'made elsewhere' : 'not available yet'].filter(Boolean).join(' · ')}
            </Typography>
          </MenuItem>
        )
      })}
      {extra && <Divider />}
      {extra && (
        <MenuItem value={extra.value} sx={{ display: 'block', py: 0.5 }}>
          <Typography variant="subtitle2" component="p">{extra.label}</Typography>
          <Typography variant="caption" color="text.secondary">{extra.note}</Typography>
        </MenuItem>
      )}
    </TextField>
  )
}

/** A type of the old system that this window does not make: what it is, how much it was used, and where it is made if somewhere else. */
export function NotMadeHere({ type, otherwise }: { type: BackOfficeType; otherwise: string }) {
  return (
    <Panel>
      <Typography variant="h6" component="h2" sx={{ mb: 0.5 }}>{type.name} <Typography component="span" color="text.secondary">({type.code || `type ${type.id}`})</Typography></Typography>
      <Typography sx={{ mb: 1.5 }} color="text.secondary">
        {type.entries > 0
          ? `The old system has ${type.entries.toLocaleString('en-US')} of these, the last dated ${fmtDate(type.lastUsed ?? '') ?? type.lastUsed}.`
          : 'The old system never used this type.'}
      </Typography>
      <Alert severity="info">
        {type.madeElsewhere ?? `This application does not make this type of entry yet, so it can only be seen here. ${otherwise}`}
      </Alert>
    </Panel>
  )
}

export function Toolbar({ children }: { children: ReactNode }) {
  return <Stack direction="row" spacing={0.5} role="toolbar" aria-label="Entry" sx={(t) => ({ ...glass(t), p: 0.75, mb: 1.5, alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 })}>{children}</Stack>
}

export function ToolButton({ icon, label, hint, ...rest }: { icon: ReactNode; label: string; hint?: string; onClick?: () => void; disabled?: boolean; form?: string; type?: 'submit' }) {
  const b = <Button size="small" startIcon={icon} {...rest}>{label}</Button>
  return hint ? <Tooltip title={hint}><span>{b}</span></Tooltip> : b
}

export function Panel({ children, sx }: { children: ReactNode; sx?: object }) {
  return <Box sx={(t) => ({ ...glass(t), p: 1.75, ...sx })}>{children}</Box>
}

export function ReadOnly({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ minWidth: 110 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="subtitle2" component="p">{value}</Typography>
    </Box>
  )
}

const COLUMNS: { key: SortKey; label: string; right?: boolean }[] = [
  { key: 'reference', label: 'Control No.' }, { key: 'date', label: 'Date' }, { key: 'description', label: 'Description' },
  { key: 'total', label: 'Amount', right: true }, { key: 'status', label: 'Status' }, { key: 'createdBy', label: 'Made by' },
]

/** The entries as a table; with {@code onSort} each heading sorts by its column (pressed again, the other way). */
export function EntryList({ entries, loading, error, onOpen, empty = 'No entries of this type yet.', sort, onSort }: {
  entries: Entry[]; loading: boolean; error: Error | null; onOpen: (id: number) => void; empty?: string; sort?: Sort; onSort?: (key: SortKey) => void
}) {
  if (error) return <Alert severity="error">{error.message}</Alert>
  if (loading) return <Typography color="text.secondary">Loading…</Typography>
  if (entries.length === 0) return <Alert severity="info">{empty}</Alert>
  return (
    <Table size="small" aria-label="Entries">
      <TableHead>
        <TableRow>
          {COLUMNS.map((c) => (
            <TableCell key={c.key} align={c.right ? 'right' : 'left'} sortDirection={sort?.key === c.key ? sort.dir : false}>
              {onSort
                ? <TableSortLabel active={sort?.key === c.key} direction={sort?.key === c.key ? sort.dir : SORT_KEYS.find((k) => k.key === c.key)?.first} onClick={() => onSort(c.key)}>{c.label}</TableSortLabel>
                : c.label}
            </TableCell>
          ))}
        </TableRow>
      </TableHead>
      <TableBody>
        {entries.map((e) => (
          <TableRow key={e.id} hover onClick={() => onOpen(e.id)} sx={{ cursor: 'pointer' }}>
            <TableCell>{e.reference}{e.reversal ? ' (reversal)' : ''}</TableCell>
            <TableCell>{fmtDate(e.date)}</TableCell>
            <TableCell>{e.description}</TableCell>
            <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(e.total)}</TableCell>
            <TableCell><EntryStepStatus status={e.status} /></TableCell>
            <TableCell>{e.createdBy}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

export function FindDialog({ entries, loading, error, typeLabel, onClose, onOpen }: { entries: Entry[]; loading: boolean; error: Error | null; typeLabel: string; onClose: () => void; onOpen: (id: number) => void }) {
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  // the order lasts while the pop-up is open; it opens on the date, newest first
  const [sort, setSort] = useState<Sort>(DEFAULT_SORT)
  const text = q.trim().toLowerCase()
  const found = sortEntries(entries.filter((e) => (!status || e.status === status) && (!text || `${e.reference} ${e.description} ${e.createdBy}`.toLowerCase().includes(text))), sort)
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Find a {typeLabel.toLowerCase()}</DialogTitle>
      <DialogContent>
        <Stack direction="row" spacing={1.5} sx={{ my: 1 }}>
          <TextField autoFocus size="small" label="Control No., description or who made it" value={q} onChange={(e) => setQ(e.target.value)} sx={{ flex: 1 }} />
          <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ width: 170 }}>
            <MenuItem value="">All</MenuItem>
            {['DRAFT', 'FOR_REVIEW', 'APPROVED', 'POSTED', 'CANCELLED'].map((s) => <MenuItem key={s} value={s}>{labelOf(s)}</MenuItem>)}
          </TextField>
          <TextField select size="small" label="Sort by" value={sort.key} sx={{ width: 170 }}
            onChange={(e) => { const key = e.target.value as SortKey; setSort({ key, dir: SORT_KEYS.find((k) => k.key === key)?.first ?? 'asc' }) }}>
            {SORT_KEYS.map((k) => <MenuItem key={k.key} value={k.key}>{k.label}</MenuItem>)}
          </TextField>
          <Tooltip title="Reverse the order">
            <IconButton aria-label={`Order: ${describeSort(sort)}. Reverse it`} onClick={() => setSort((s) => nextSort(s, s.key))}>
              {sort.dir === 'asc' ? <FiArrowUp /> : <FiArrowDown />}
            </IconButton>
          </Tooltip>
        </Stack>
        <Typography variant="body2" sx={{ mb: 1 }} color="text.secondary" aria-live="polite">
          {found.length} of {entries.length} · {describeSort(sort)}. Press a column heading to sort by it.
        </Typography>
        <EntryList entries={found} loading={loading} error={error} onOpen={onOpen} sort={sort} onSort={(key) => setSort((s) => nextSort(s, key))}
          empty={entries.length === 0 ? 'No entries of this type yet.' : 'Nothing matches. Clear the search or choose another status.'} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  )
}

export function Totals({ debit, credit }: { debit: number; credit: number }) {
  const difference = Math.round((debit - credit) * 100) / 100
  return (
    <Stack direction="row" spacing={3} sx={{ mt: 1.5, justifyContent: 'flex-end', fontVariantNumeric: 'tabular-nums' }} aria-label="Totals">
      <Typography>Debit <b>{money(debit)}</b></Typography>
      <Typography>Credit <b>{money(credit)}</b></Typography>
      <Typography color={difference === 0 ? 'success.main' : 'error.main'}>Difference <b>{money(Math.abs(difference))}</b></Typography>
    </Stack>
  )
}

/** Asks before an entry being made is thrown away. */
export function DiscardDialog({ onKeep, onDiscard }: { onKeep: () => void; onDiscard: () => void }) {
  return (
    <Dialog open onClose={onKeep} maxWidth="xs" fullWidth>
      <DialogTitle>Discard the entry?</DialogTitle>
      <DialogContent><Typography>The entry you are making has not been saved. Discarding it loses what you typed.</Typography></DialogContent>
      <DialogActions>
        <Button onClick={onKeep}>Keep editing</Button>
        <Button color="warning" variant="contained" onClick={onDiscard}>Discard</Button>
      </DialogActions>
    </Dialog>
  )
}

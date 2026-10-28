import { Autocomplete, Box, Button, IconButton, MenuItem, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { FiPlus, FiTrash2 } from 'react-icons/fi'
import { getChequeSetup, type ChequeIn } from './api'
import MoneyField from './MoneyField'
import { money as peso } from './ui/format'

/** One cheque as typed: the bank and kind are ids from the cheque setup lists. */
export interface ChequeRow { number: string; bankId: number | null; date: string; kind: number | null; amount: string }

export const emptyCheque = (): ChequeRow => ({ number: '', bankId: null, date: new Date().toISOString().slice(0, 10), kind: 1, amount: '' })

/** The rows as the server wants them; rows with nothing typed at all are left out. */
export const chequesToApi = (rows: ChequeRow[]): ChequeIn[] =>
  rows.filter((r) => r.number.trim() !== '' || r.bankId != null || r.amount.trim() !== '')
    .map((r) => ({ number: r.number, bankCode: r.bankId, date: r.date || null, kind: r.kind, amount: r.amount.trim() === '' ? null : Number(r.amount) }))

export const chequeSum = (rows: ChequeRow[]) => rows.reduce((t, r) => t + (Number.isFinite(Number(r.amount)) ? Number(r.amount) : 0), 0)


/**
 * The cheque part of a payment at the window: one line for each cheque (number, bank it is drawn on, date, kind, amount). Whatever the
 * cheques do not cover is taken in cash. The server checks every cheque again when the payment is posted.
 */
export default function ChequeFields({ rows, onChange, total }: { rows: ChequeRow[]; onChange: (rows: ChequeRow[]) => void; total: number }) {
  const { data } = useQuery({ queryKey: ['cheque-setup'], queryFn: getChequeSetup, staleTime: 10 * 60_000 })
  const set = (i: number, patch: Partial<ChequeRow>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const sum = chequeSum(rows)
  const cash = total - sum
  return (
    <Box sx={{ display: 'grid', gap: 1 }}>
      {rows.map((r, i) => (
        <Box key={i} sx={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 1, alignItems: 'start', p: 1, border: 1, borderColor: 'divider', borderRadius: 1 }}>
          <Box sx={{ display: 'grid', gap: 1 }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1 }}>
              <TextField size="small" label="Cheque number" value={r.number} onChange={(e) => set(i, { number: e.target.value.replace(/[^0-9]/g, '').slice(0, 15) })} />
              <TextField size="small" type="date" label="Date on cheque" value={r.date} onChange={(e) => set(i, { date: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
            </Box>
            <Autocomplete size="small" options={data?.banks ?? []} getOptionLabel={(b) => b.name} isOptionEqualToValue={(a, b) => a.id === b.id}
              value={data?.banks.find((b) => b.id === r.bankId) ?? null} onChange={(_, b) => set(i, { bankId: b ? b.id : null })}
              renderInput={(params) => <TextField {...params} label="Drawn on bank" />} />
            <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1 }}>
              <TextField size="small" select label="Kind" value={r.kind ?? ''} onChange={(e) => set(i, { kind: Number(e.target.value) })}>
                {(data?.kinds ?? []).map((k) => <MenuItem key={k.id} value={k.id}>{k.name}{k.days > 0 ? ` · ${k.days} days` : ' · no wait'}</MenuItem>)}
              </TextField>
              <MoneyField size="small" label="Cheque amount" value={r.amount} onValueChange={(v) => set(i, { amount: v })} />
            </Box>
          </Box>
          <IconButton aria-label="Remove this cheque" size="small" onClick={() => onChange(rows.filter((_, j) => j !== i))}><FiTrash2 /></IconButton>
        </Box>
      ))}
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
        <Button size="small" startIcon={<FiPlus />} onClick={() => onChange([...rows, emptyCheque()])}>{rows.length ? 'Add another cheque' : 'Pay partly or wholly by cheque'}</Button>
        {rows.length > 0 && (
          <Typography variant="caption" color={cash < 0 ? 'error' : 'text.secondary'}>
            Cheques {peso(sum)} · cash {cash < 0 ? 'over by ' + peso(-cash) : peso(cash)}
          </Typography>
        )}
      </Box>
    </Box>
  )
}

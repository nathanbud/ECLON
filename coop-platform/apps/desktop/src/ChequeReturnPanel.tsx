import { Alert, Box, Button, MenuItem, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  askChequeReturn, checkChequeReturn, findReturnableCheques, getBankCashBanks, getFoHandoverDrawers, getFoHandoverMine, postChequeReturn,
  type ChequeReturnCandidate, type ChequeReturnReq,
} from './api'
import { money } from './FoTransactionForm'
import { glass } from './glass'

/**
 * A cheque the bank sent back. Find it by number, say where it is now (with a cashier or teller, in the vault, or in the bank), ask a different
 * supervisor to approve, then post: the member savings account is debited and the place holding the cheque is credited, as a General Journal.
 * Only cheques that paid a savings deposit are handled. Hidden for people who may not do this.
 */
export function ChequeReturnPanel() {
  const qc = useQueryClient()
  const mine = useQuery({ queryKey: ['fo-handover-mine'], queryFn: getFoHandoverMine, refetchInterval: 30_000 })
  const [number, setNumber] = useState('')
  const [searched, setSearched] = useState('')
  const [picked, setPicked] = useState<ChequeReturnCandidate | null>(null)
  const [where, setWhere] = useState<string>('VAULT')
  const [note, setNote] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const found = useQuery({ queryKey: ['cheque-return-find', searched], queryFn: () => findReturnableCheques(searched), enabled: searched !== '', retry: false })
  const drawers = useQuery({ queryKey: ['fo-handover-drawers'], queryFn: getFoHandoverDrawers, enabled: picked !== null })
  const banks = useQuery({ queryKey: ['bank-cash-banks'], queryFn: getBankCashBanks, enabled: picked !== null, retry: false })

  const req = (): ChequeReturnReq | null => {
    if (!picked) return null
    const base = { year: picked.year, ctlNo: picked.ctlNo, seq: picked.seq }
    if (where === 'VAULT') return { ...base, where: 'VAULT' }
    if (where.startsWith('D:')) { const [, e, n] = where.split(':'); return { ...base, where: 'DRAWER', event: Number(e), number: Number(n) } }
    if (where.startsWith('B:')) return { ...base, where: 'BANK', bankId: Number(where.slice(2)) }
    return null
  }
  const r = req()
  const state = useQuery({
    queryKey: ['cheque-return-check', r], queryFn: () => checkChequeReturn(r!), enabled: r !== null, refetchInterval: 8_000, retry: false,
  })
  const ask = useMutation({
    mutationFn: () => askChequeReturn(r!),
    onSuccess: () => { setError(null); void qc.invalidateQueries({ queryKey: ['cheque-return-check'] }) },
    onError: (e) => setError((e as Error).message),
  })
  const post = useMutation({
    mutationFn: () => postChequeReturn(r!),
    onSuccess: (p) => {
      setError(null); setNote(`Recorded. Journal ${p.documentNo}: ${money(p.amount)} taken back from the savings of ${p.client}, cheque taken out of ${p.where}.`)
      setPicked(null); setSearched(''); setNumber('')
      void qc.invalidateQueries({ queryKey: ['fo-handover-drawers'] }); void qc.invalidateQueries({ queryKey: ['cheque-return-find'] })
    },
    onError: (e) => setError((e as Error).message),
  })
  if (mine.data?.headCashier !== true) return null
  const rows = found.data ?? []
  const s = state.data
  return (
    <Box sx={(t) => ({ p: 1.5, mt: 1.5, ...glass(t) })}>
      <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.75 }} color="text.secondary">Returned cheque</Typography>
      {note && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setNote(null)}>{note}</Alert>}
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {found.isError && searched !== '' && <Alert severity="error" sx={{ mb: 1 }}>{(found.error as Error).message}</Alert>}
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
        <TextField size="small" label="Cheque number" value={number} onChange={(e) => setNumber(e.target.value.replace(/[^0-9]/g, '').slice(0, 15))}
          onKeyDown={(e) => { if (e.key === 'Enter' && number) { setPicked(null); setSearched(number) } }} sx={{ width: 220 }} />
        <Button variant="outlined" disabled={!number} onClick={() => { setPicked(null); setSearched(number) }}>Find</Button>
      </Box>
      {searched !== '' && found.isSuccess && rows.length === 0 && <Typography sx={{ fontSize: 13.5, mt: 1 }} color="text.secondary">No received cheque has that number.</Typography>}
      {rows.length > 0 && (
        <Table size="small" sx={{ mt: 1 }} aria-label="Cheques found">
          <TableHead><TableRow><TableCell>Received</TableCell><TableCell>Receipt</TableCell><TableCell>Member</TableCell><TableCell>Bank</TableCell><TableCell align="right">Amount</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {rows.map((c) => (
              <TableRow key={`${c.year}/${c.ctlNo}/${c.seq}`} selected={picked?.ctlNo === c.ctlNo && picked?.seq === c.seq && picked?.year === c.year}>
                <TableCell>{c.receiptDate}</TableCell><TableCell>{c.documentNo}</TableCell><TableCell>{c.client}</TableCell><TableCell>{c.bank ?? ''}</TableCell>
                <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums' }}>{money(c.amount)}</TableCell>
                <TableCell align="right">
                  {c.returned ? <Typography sx={{ fontSize: 12.5 }} color="text.secondary">Already returned</Typography>
                    : c.problem ? <Typography sx={{ fontSize: 12.5 }} color="error">{c.problem}</Typography>
                    : <Button size="small" onClick={() => { setPicked(c); setError(null) }}>Choose</Button>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {picked && (
        <Box sx={{ mt: 1.5, display: 'grid', gap: 1 }}>
          <TextField select size="small" label="Where is the cheque now?" value={where} onChange={(e) => setWhere(e.target.value)} sx={{ maxWidth: 420 }}>
            <MenuItem value="VAULT">In the vault</MenuItem>
            {(drawers.data ?? []).filter((d) => d.event !== 50).map((d) => <MenuItem key={`${d.event}:${d.number}`} value={`D:${d.event}:${d.number}`}>With {d.label} · {d.who}</MenuItem>)}
            {(banks.data ?? []).map((b) => <MenuItem key={b.bankId} value={`B:${b.bankId}`}>Deposited in the bank · {b.name}</MenuItem>)}
          </TextField>
          <Typography sx={{ fontSize: 13.5 }}>
            Takes <b style={{ fontVariantNumeric: 'tabular-nums' }}>{money(picked.amount)}</b> back from the savings account of {picked.client}.
          </Typography>
          {s?.problems.map((p) => <Alert key={p} severity="warning">{p}</Alert>)}
          {s?.approval === 'PENDING' && <Alert severity="info">Waiting for a supervisor to approve.</Alert>}
          {s?.approval === 'REJECTED' && <Alert severity="error">A supervisor rejected this. You can ask again.</Alert>}
          <Box sx={{ display: 'flex', gap: 1 }}>
            {s?.approval === 'APPROVED'
              ? <Button variant="contained" disabled={!s.ready || post.isPending} onClick={() => post.mutate()}>{post.isPending ? 'Posting…' : 'Post the return'}</Button>
              : <Button variant="contained" disabled={!s || s.problems.length > 0 || s.approval === 'PENDING' || ask.isPending} onClick={() => ask.mutate()}>Ask a supervisor</Button>}
            <Button onClick={() => setPicked(null)}>Cancel</Button>
          </Box>
        </Box>
      )}
    </Box>
  )
}

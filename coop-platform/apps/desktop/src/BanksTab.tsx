import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import {
  finalizeBankRecon, getBankEntries, getBankSheet, listBankRecons, listBanks, saveBankRecon,
  type BankAccount, type BankKey, type BankRecon,
} from './api'
import { glass } from './glass'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const iso = (d: Date) => d.toLocaleDateString('en-CA')
const addDays = (date: string, n: number) => { const d = new Date(`${date}T00:00:00`); d.setDate(d.getDate() + n); return iso(d) }
const keyOf = (k: BankKey) => `${k.year}-${k.module}-${k.code}-${k.ctlNo}-${k.seq}`
const num = { fontVariantNumeric: 'tabular-nums' } as const

/** Bank accounts of the cooperative with their ledger balance, the entries behind it, and reconciliation against the bank statement. */
export default function BanksTab({ branchCode }: { branchCode: number }) {
  const [entries, setEntries] = useState<BankAccount | null>(null)
  const [recon, setRecon] = useState<BankAccount | null>(null)
  const list = useQuery({ queryKey: ['banks', branchCode], queryFn: () => listBanks(branchCode), retry: false })
  return (
    <Box>
      {list.error && <Alert severity="error" sx={{ mb: 2 }}>{(list.error as Error).message}</Alert>}
      {list.data && list.data.length === 0 && <Alert severity="info">No bank accounts are set up for this branch.</Alert>}
      {list.data && list.data.length > 0 && (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Bank accounts">
            <TableHead>
              <TableRow>
                <TableCell>Bank account</TableCell><TableCell>Account no.</TableCell><TableCell>Ledger</TableCell><TableCell>Last activity</TableCell>
                <TableCell>Reconciled through</TableCell><TableCell align="right">Book balance</TableCell><TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {list.data.map((b) => (
                <TableRow key={b.id} hover>
                  <TableCell>{b.name}</TableCell><TableCell>{b.accountNo ?? '—'}</TableCell><TableCell>{b.ledgerAccount}</TableCell>
                  <TableCell>{b.lastActivity ?? '—'}</TableCell><TableCell>{b.reconciledThrough ?? 'never'}</TableCell>
                  <TableCell align="right" sx={num}>{money(b.bookBalance)}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    <Button size="small" onClick={() => setEntries(b)}>Entries</Button>
                    <Button size="small" onClick={() => setRecon(b)}>Reconcile</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
      {entries && <EntriesDialog branchCode={branchCode} bank={entries} onClose={() => setEntries(null)} />}
      {recon && <ReconcileDialog branchCode={branchCode} bank={recon} onClose={() => setRecon(null)} />}
    </Box>
  )
}

function EntriesDialog({ branchCode, bank, onClose }: { branchCode: number; bank: BankAccount; onClose: () => void }) {
  const end = bank.lastActivity ?? iso(new Date())
  const [from, setFrom] = useState(addDays(end, -60))
  const [to, setTo] = useState(end)
  const q = useQuery({ queryKey: ['bank-entries', branchCode, bank.id, from, to], queryFn: () => getBankEntries(branchCode, bank.id, from, to), enabled: !!from && !!to, retry: false })
  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle>{bank.name} ({bank.ledgerAccount})</DialogTitle>
      <DialogContent>
        <Stack direction="row" spacing={2} sx={{ my: 1 }}>
          <TextField size="small" type="date" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="date" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        </Stack>
        {q.error && <Alert severity="error" sx={{ mb: 1 }}>{(q.error as Error).message}</Alert>}
        {q.data?.truncated && <Alert severity="warning" sx={{ mb: 1 }}>Only the first 3,000 entries are shown. Choose a shorter range.</Alert>}
        {q.data && (
          <Table size="small" aria-label="Bank entries">
            <TableHead>
              <TableRow><TableCell>Date</TableCell><TableCell>Transaction</TableCell><TableCell>Document</TableCell><TableCell>Explanation</TableCell><TableCell align="right">Amount</TableCell><TableCell align="right">Balance</TableCell></TableRow>
            </TableHead>
            <TableBody>
              <TableRow><TableCell colSpan={5}><em>Opening balance</em></TableCell><TableCell align="right" sx={num}>{money(q.data.opening)}</TableCell></TableRow>
              {q.data.entries.map((e) => (
                <TableRow key={keyOf(e.key)} hover>
                  <TableCell>{e.date}</TableCell><TableCell>{e.transaction ?? `Type ${e.key.code}`}</TableCell><TableCell>{e.documentNo ?? '—'}</TableCell>
                  <TableCell>{e.explanation ?? ''}</TableCell>
                  <TableCell align="right" sx={{ color: e.amount < 0 ? 'error.main' : undefined, ...num }}>{money(e.amount)}</TableCell>
                  <TableCell align="right" sx={num}>{money(e.balanceAfter)}</TableCell>
                </TableRow>
              ))}
              <TableRow><TableCell colSpan={5} sx={{ fontWeight: 700 }}>Closing balance</TableCell><TableCell align="right" sx={{ fontWeight: 700, ...num }}>{money(q.data.closing)}</TableCell></TableRow>
            </TableBody>
          </Table>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  )
}

function ReconcileDialog({ branchCode, bank, onClose }: { branchCode: number; bank: BankAccount; onClose: () => void }) {
  const qc = useQueryClient()
  const [date, setDate] = useState(iso(new Date()))
  const [balance, setBalance] = useState('')
  const [note, setNote] = useState('')
  const [outstanding, setOutstanding] = useState<Set<string>>(new Set())
  const [saved, setSaved] = useState<BankRecon | null>(null)

  const sheet = useQuery({ queryKey: ['bank-sheet', branchCode, bank.id, date], queryFn: () => getBankSheet(branchCode, bank.id, date), enabled: !!date, retry: false })
  const history = useQuery({ queryKey: ['bank-recons', branchCode, bank.id], queryFn: () => listBankRecons(branchCode, bank.id), retry: false })

  // start from what the sheet says (what was left outstanding last time, or the open draft)
  useEffect(() => {
    if (!sheet.data) return
    setOutstanding(new Set(sheet.data.items.filter((i) => i.outstanding).map((i) => keyOf(i.key))))
    if (sheet.data.draftStatementBalance != null && balance === '') setBalance(String(sheet.data.draftStatementBalance))
    setSaved(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sheet.data])

  const items = sheet.data?.items ?? []
  const outstandingTotal = items.filter((i) => outstanding.has(keyOf(i.key))).reduce((s, i) => s + i.amount, 0)
  const statement = Number(balance)
  const haveBalance = balance.trim() !== '' && Number.isFinite(statement)
  const book = sheet.data?.bookBalance ?? 0
  const difference = haveBalance ? Math.round((statement - (book - outstandingTotal)) * 100) / 100 : null

  const refresh = () => { void qc.invalidateQueries({ queryKey: ['banks'] }); void qc.invalidateQueries({ queryKey: ['bank-recons'] }); void qc.invalidateQueries({ queryKey: ['bank-sheet'] }) }
  const save = useMutation({
    mutationFn: () => saveBankRecon(branchCode, bank.id, { statementDate: date, statementBalance: statement, outstanding: items.filter((i) => outstanding.has(keyOf(i.key))).map((i) => i.key), note }),
    onSuccess: (r) => { setSaved(r); refresh() },
  })
  const finalize = useMutation({ mutationFn: (id: number) => finalizeBankRecon(id), onSuccess: (r) => { setSaved(r); refresh() } })
  const error = (sheet.error ?? save.error ?? finalize.error) as Error | null
  const toggle = (k: string) => setOutstanding((s) => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n })

  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle>Reconcile {bank.name} ({bank.ledgerAccount})</DialogTitle>
      <DialogContent>
        <Stack direction="row" spacing={2} useFlexGap sx={{ my: 1, flexWrap: 'wrap', alignItems: 'center' }}>
          <TextField size="small" type="date" label="Statement date" value={date} onChange={(e) => setDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="number" label="Balance on the bank statement" value={balance} onChange={(e) => setBalance(e.target.value)} slotProps={{ htmlInput: { step: '0.01' } }} sx={{ width: 240 }} />
          <TextField size="small" label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} sx={{ flex: 1, minWidth: 200 }} />
        </Stack>
        {sheet.data?.previousDate && <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">Last reconciled through {sheet.data.previousDate}. Entries that were outstanding then are ticked.</Typography>}
        <Typography sx={{ fontSize: 13, mb: 1 }} color="text.secondary">Tick the entries the bank has <strong>not</strong> cleared yet (deposits in transit, cheques not cashed).</Typography>
        {error && <Alert severity="error" sx={{ mb: 1 }}>{error.message}</Alert>}
        <Box sx={{ maxHeight: 320, overflow: 'auto', mb: 2 }}>
          <Table size="small" stickyHeader aria-label="Entries to reconcile">
            <TableHead>
              <TableRow><TableCell padding="checkbox">Outstanding</TableCell><TableCell>Date</TableCell><TableCell>Transaction</TableCell><TableCell>Document</TableCell><TableCell>Explanation</TableCell><TableCell align="right">Amount</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {items.map((i) => {
                const k = keyOf(i.key)
                return (
                  <TableRow key={k} hover onClick={() => toggle(k)} sx={{ cursor: 'pointer' }}>
                    <TableCell padding="checkbox"><Checkbox size="small" checked={outstanding.has(k)} onChange={() => toggle(k)} onClick={(e) => e.stopPropagation()} slotProps={{ input: { 'aria-label': `Outstanding: ${i.documentNo ?? ''}` } }} /></TableCell>
                    <TableCell>{i.date}</TableCell><TableCell>{i.transaction ?? `Type ${i.key.code}`}</TableCell><TableCell>{i.documentNo ?? '—'}</TableCell><TableCell>{i.explanation ?? ''}</TableCell>
                    <TableCell align="right" sx={{ color: i.amount < 0 ? 'error.main' : undefined, ...num }}>{money(i.amount)}</TableCell>
                  </TableRow>
                )
              })}
              {sheet.data && items.length === 0 && <TableRow><TableCell colSpan={6}>No ledger entries in this period.</TableCell></TableRow>}
            </TableBody>
          </Table>
        </Box>
        <Table size="small" aria-label="Reconciliation totals" sx={{ maxWidth: 480 }}>
          <TableBody>
            <TableRow><TableCell>Book balance on {date}</TableCell><TableCell align="right" sx={num}>{money(book)}</TableCell></TableRow>
            <TableRow><TableCell>Less: outstanding entries ({outstanding.size})</TableCell><TableCell align="right" sx={num}>{money(outstandingTotal)}</TableCell></TableRow>
            <TableRow><TableCell>Expected on the bank statement</TableCell><TableCell align="right" sx={num}>{money(book - outstandingTotal)}</TableCell></TableRow>
            <TableRow><TableCell>Balance on the bank statement</TableCell><TableCell align="right" sx={num}>{haveBalance ? money(statement) : '—'}</TableCell></TableRow>
            <TableRow>
              <TableCell sx={{ fontWeight: 700 }}>Difference</TableCell>
              <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{difference == null ? '—' : money(difference)}</TableCell>
            </TableRow>
          </TableBody>
        </Table>
        {difference != null && <Alert severity={difference === 0 ? 'success' : 'warning'} sx={{ mt: 1, maxWidth: 480 }}>{difference === 0 ? 'The books agree with the bank.' : 'The books and the bank do not agree yet. Check the ticked entries, or look for bank charges and interest that are not recorded.'}</Alert>}
        {saved && (
          <Alert severity={saved.status === 'FINAL' ? 'success' : 'info'} sx={{ mt: 1 }}>
            {saved.status === 'FINAL' ? `Finalized by ${saved.finalizedBy}.` : `Draft saved by ${saved.preparedBy}. Someone else has to finalize it${saved.reconciled ? '' : ' once the difference is zero'}.`}
          </Alert>
        )}
        {history.data && history.data.length > 0 && (
          <Box sx={{ mt: 2 }}>
            <Typography sx={{ fontWeight: 600, fontSize: 14, mb: 0.5 }}>Earlier reconciliations</Typography>
            <Table size="small" aria-label="Earlier reconciliations">
              <TableBody>
                {history.data.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{r.statementDate}</TableCell><TableCell align="right" sx={num}>{money(r.statementBalance)}</TableCell>
                    <TableCell><Chip size="small" label={r.status} color={r.status === 'FINAL' ? 'success' : 'default'} /></TableCell>
                    <TableCell>{r.preparedBy}{r.finalizedBy ? ` / ${r.finalizedBy}` : ''}</TableCell>
                    <TableCell align="right">{r.status === 'DRAFT' && r.reconciled && <Button size="small" disabled={finalize.isPending} onClick={() => finalize.mutate(r.id)}>Finalize</Button>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={save.isPending || !haveBalance || !sheet.data} onClick={() => save.mutate()}>Save draft</Button>
      </DialogActions>
    </Dialog>
  )
}

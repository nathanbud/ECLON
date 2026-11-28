import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState, type KeyboardEvent } from 'react'
import { getTransaction } from './api'
import { type Id } from './clients'
import { money } from './ui/format'
import { Loading } from './ui/Loading'
import { RADIUS } from './theme'
import { fmtDate } from './profile/format'

/** Which transaction a ledger row came from: the year of its date, the transaction code and the control number. */
export interface TxKey { year: number; code: number; ctlNo: number }

/** The transaction behind a ledger row, or null for a row that carries no control number. */
export const txKeyOf = (e: { date: string; transactionCode: number; controlNo?: number | null }): TxKey | null =>
  e.controlNo ? { year: Number(e.date.slice(0, 4)), code: e.transactionCode, ctlNo: e.controlNo } : null

/** What a ledger row needs to open its transaction: a pointer, hover, and the keyboard (Enter). Spread onto a TableRow. */
export function txRowProps(e: { date: string; transactionCode: number; controlNo?: number | null }, open: (k: TxKey) => void) {
  const k = txKeyOf(e)
  if (!k) return {}
  return {
    hover: true, tabIndex: 0, title: 'Show the transaction', sx: { cursor: 'pointer' },
    onClick: () => open(k),
    onKeyDown: (ev: KeyboardEvent) => { if (ev.key === 'Enter') { ev.preventDefault(); open(k) } },
  }
}

/** The old system prints a ledger account as 3 digits, then pairs, then what is left: 10110129 reads 101.10.12.9. */
export const glCode = (c: string | null | undefined) => {
  if (!c) return ''
  const parts = [c.slice(0, 3)]
  for (let i = 3; i < c.length; i += 2) parts.push(c.slice(i, i + 2))
  return parts.join('.')
}

const num = { fontVariantNumeric: 'tabular-nums' } as const

function Fact({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <Box sx={{ display: 'flex', gap: 1, minWidth: 0, py: 0.25 }}>
      <Typography component="span" color="text.secondary" sx={{ width: 96, flexShrink: 0 }}>{label}</Typography>
      <Typography component="span" sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>{value || '—'}</Typography>
    </Box>
  )
}

/**
 * Where a ledger entry came from: the posted transaction (a loan voucher, cash receipt, general journal and so on) with its header, the general ledger and subsidiary ledger
 * lines it wrote, and its cheques, laid out like the old system's transaction window. Read only.
 */
export default function TransactionDialog({ id, tx, onClose }: { id: Id; tx: TxKey; onClose: () => void }) {
  const [tab, setTab] = useState(0)
  const { data, error, isLoading } = useQuery({ queryKey: ['member-transaction', id, tx], queryFn: () => getTransaction(id.branchCode, id.clientId, tx.year, tx.code, tx.ctlNo), staleTime: 5 * 60_000 })
  const h = data?.header
  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth aria-labelledby="tx-title">
      <DialogTitle id="tx-title" sx={{ display: 'flex', alignItems: 'baseline', gap: 2, flexWrap: 'wrap' }}>
        <Box component="span" sx={{ flex: 1, minWidth: 0, fontSize: 17 }}>
          Transaction{h?.clientNo ? ` : ${h.clientNo}` : ''}{h?.client ? ` - ${h.client}` : ''} <Typography component="span" color="text.secondary">Control no. {String(tx.ctlNo).padStart(8, '0')}</Typography>
        </Box>
        {h && <Box component="span" sx={{ fontSize: 22, fontWeight: 700, letterSpacing: '0.04em', color: h.status === 'APPROVED' ? 'success.main' : 'warning.main' }}>{h.status}</Box>}
      </DialogTitle>
      <DialogContent>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {isLoading && <Loading what="the transaction" />}
        {data && h && (
          <>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '3fr 2fr' }, gap: 2, mb: 1.5 }}>
              <Box>
                <Fact label="Trans. type" value={h.type ? `${h.type}${h.typeShort ? ` (${h.typeShort})` : ''}` : null} />
                <Fact label="Control no." value={h.controlNo} />
                <Fact label="Member" value={[h.clientNo, h.client].filter(Boolean).join('  ')} />
                <Fact label="Dept" value={h.department} />
                <Fact label="And/Or" value={h.andOr} />
              </Box>
              <Box>
                <Fact label="Ref. number" value={h.docNo} />
                <Fact label="Trans. date" value={fmtDate(h.date)} />
                <Fact label="Batch no." value={h.batchNo} />
              </Box>
            </Box>

            <Tabs value={tab} onChange={(_, v: number) => setTab(v)} sx={{ minHeight: 36, '& .MuiTab-root': { minHeight: 36, textTransform: 'none' } }}>
              <Tab label="GL/SL Entries" /><Tab label={`COCI Details${data.cheques.length ? ` (${data.cheques.length})` : ''}`} />
            </Tabs>
            <Box sx={{ border: 1, borderColor: 'divider', maxHeight: 300, overflow: 'auto' }}>
              {tab === 0 && (
                <Table size="small" stickyHeader aria-label="General ledger and subsidiary ledger entries">
                  <TableHead>
                    <TableRow>
                      <TableCell>P</TableCell><TableCell>SC</TableCell><TableCell>ST</TableCell><TableCell>SE</TableCell><TableCell>SN</TableCell><TableCell>G/L Acct.</TableCell><TableCell>Account title</TableCell>
                      <TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell><TableCell>Member ID</TableCell><TableCell>Name of member</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {data.lines.map((l) => (
                      <TableRow key={l.seq} hover>
                        <TableCell>{l.posted ? 'P' : ''}</TableCell><TableCell>{l.sc ?? ''}</TableCell><TableCell>{l.st ?? ''}</TableCell><TableCell>{l.se ?? ''}</TableCell><TableCell>{l.sn ?? ''}</TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap', ...num }}>{glCode(l.glCode)}</TableCell><TableCell>{l.title ?? ''}</TableCell>
                        <TableCell align="right" sx={num}>{l.debit ? money(l.debit) : ''}</TableCell><TableCell align="right" sx={num}>{l.credit ? money(l.credit) : ''}</TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap', ...num }}>{l.clientNo ?? ''}</TableCell><TableCell>{l.clientName ?? ''}</TableCell>
                      </TableRow>
                    ))}
                    {data.lines.length === 0 && <TableRow><TableCell colSpan={11}>This transaction has no entries.</TableCell></TableRow>}
                  </TableBody>
                </Table>
              )}
              {tab === 1 && (
                data.cheques.length === 0
                  ? <Typography color="text.secondary" sx={{ p: 2 }}>No cheques came with this transaction.</Typography>
                  : (
                    <Table size="small" stickyHeader aria-label="Cheques">
                      <TableHead>
                        <TableRow><TableCell>Seq</TableCell><TableCell>Type</TableCell><TableCell>Cheque no.</TableCell><TableCell>Bank</TableCell><TableCell>Cheque date</TableCell><TableCell align="right">Amount</TableCell><TableCell>Payee</TableCell><TableCell>Cleared</TableCell></TableRow>
                      </TableHead>
                      <TableBody>
                        {data.cheques.map((c) => (
                          <TableRow key={c.seq} hover>
                            <TableCell>{c.seq}</TableCell><TableCell>{c.type ?? ''}</TableCell><TableCell sx={num}>{c.number ?? ''}</TableCell><TableCell>{c.bank ?? ''}</TableCell>
                            <TableCell>{fmtDate(c.date) ?? ''}</TableCell><TableCell align="right" sx={num}>{money(c.amount)}</TableCell><TableCell>{c.payee ?? ''}</TableCell>
                            <TableCell>{c.cleared ? (c.clearedOn ? `Cleared ${fmtDate(c.clearedOn)}` : 'Cleared') : 'Not cleared'}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )
              )}
            </Box>

            <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap', justifyContent: 'flex-end', my: 1, fontSize: 13, ...num }}>
              <Box component="span">Difference: <b>{money(data.difference)}</b></Box>
              <Box component="span">Debit: <b>{money(data.debit)}</b></Box>
              <Box component="span">Credit: <b>{money(data.credit)}</b></Box>
            </Box>

            <Fact label="Source reference" value={h.sourceReference} />
            <Typography variant="overline" sx={{ display: 'block', mt: 1, mb: 0.25 }} color="text.secondary">Description</Typography>
            <Typography sx={{ whiteSpace: 'pre-wrap', minHeight: 40, p: 1, border: 1, borderColor: 'divider', borderRadius: `${RADIUS}px` }}>{h.explanation ?? ''}</Typography>
          </>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose} autoFocus>Close</Button></DialogActions>
    </Dialog>
  )
}

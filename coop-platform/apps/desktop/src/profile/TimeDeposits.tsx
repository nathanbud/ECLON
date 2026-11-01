import { type Id } from '../clients'
import { useState } from 'react'
import { getTimeDepositHistory, getTimeDeposits, type TimeDeposit } from '../api'
import { useQuery } from '@tanstack/react-query'
import { Alert, Box, Button, FormControlLabel, Skeleton, Switch, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { FiInfo, FiPlus } from 'react-icons/fi'
import { DetailPanel, EmptyState, TableCard } from './fields'
import { fmtDate, peso } from './format'
import TimeDepositPlaceDialog from '../TimeDepositPlaceDialog'
import TimeDepositInterestDialog from '../TimeDepositInterestDialog'
import TimeDepositCloseDialog from '../TimeDepositCloseDialog'

const TD_STATUS: Record<string, string> = { ACTIVE: 'Running', MATURED: 'Matured', CLOSED: 'Closed', NO_LEDGER: 'Certificate only' }

/** Time deposits of the member, read only. The old master row is never closed when a certificate is paid out, so the status is worked out from the ledger. */
export function TimeDepositsTab({ id, teller = false }: { id: Id; teller?: boolean }) {
  const [showClosed, setShowClosed] = useState(false)
  const [open, setOpen] = useState<{ typeCode: number; refNo: number } | null>(null)
  const [placing, setPlacing] = useState(false)
  const [closing, setClosing] = useState<{ d: TimeDeposit; mode: 'PAYOUT' | 'RENEW' } | null>(null)
  const [interestFor, setInterestFor] = useState<TimeDeposit | null>(null)
  const { data, error, isLoading } = useQuery({ queryKey: ['member-time-deposits', id, showClosed], queryFn: () => getTimeDeposits(id.branchCode, id.clientId, showClosed), staleTime: 60_000 })
  if (error) return <Alert severity="error">{(error as Error).message}</Alert>
  if (isLoading) return <Skeleton variant="rounded" height={160} />
  const num = { fontVariantNumeric: 'tabular-nums' } as const
  return (
    <>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 1, mb: 0.5 }}>
        {teller && <Button size="small" variant="outlined" startIcon={<FiPlus />} onClick={() => setPlacing(true)}>Place a time deposit</Button>}
        <FormControlLabel control={<Switch size="small" checked={showClosed} onChange={(_, v) => setShowClosed(v)} />} label="Show closed certificates" />
      </Box>
      {!data || data.deposits.length === 0 ? <EmptyState icon={<FiInfo />} title="No time deposits" hint="This client has no running time deposit certificates." /> : (
        <TableCard>
          <Table size="small" aria-label="Time deposits">
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell><TableCell>Certificate</TableCell><TableCell>Placed</TableCell><TableCell>Matures</TableCell><TableCell align="right">Term (days)</TableCell>
                <TableCell align="right">Rate %</TableCell><TableCell>Status</TableCell><TableCell align="right">Balance</TableCell><TableCell align="right">Accrued interest</TableCell><TableCell align="right">Interest at maturity (estimate)</TableCell><TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.deposits.map((d) => (
                <TableRow key={`${d.typeCode}-${d.refNo}`} hover selected={open?.typeCode === d.typeCode && open.refNo === d.refNo} sx={{ cursor: 'pointer' }} onClick={() => setOpen(open?.typeCode === d.typeCode && open.refNo === d.refNo ? null : { typeCode: d.typeCode, refNo: d.refNo })}>
                  <TableCell>{d.type ?? `Type ${d.typeCode}`}</TableCell><TableCell>{d.certificate ?? d.refNo}</TableCell>
                  <TableCell>{fmtDate(d.placed) ?? '—'}</TableCell><TableCell>{fmtDate(d.maturity) ?? '—'}</TableCell>
                  <TableCell align="right" sx={num}>{d.termDays}</TableCell>
                  <TableCell align="right" sx={num}>{d.ratePercent == null ? '—' : d.ratePercent.toFixed(2)}</TableCell>
                  <TableCell>{TD_STATUS[d.status] ?? d.status}</TableCell>
                  <TableCell align="right" sx={num}>{d.status === 'NO_LEDGER' ? (d.principal == null ? '—' : peso(d.principal)) : peso(d.balance)}</TableCell>
                  <TableCell align="right" sx={num}>{d.accruedInterest ? peso(d.accruedInterest) : '—'}</TableCell>
                  <TableCell align="right" sx={num}>{d.estimatedInterest == null ? '—' : peso(d.estimatedInterest)}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {teller && d.status === 'ACTIVE' && (d.typeCode === 2 || d.typeCode === 4) && (
                      <Button size="small" onClick={(e) => { e.stopPropagation(); setInterestFor(d) }}>Withdraw interest</Button>
                    )}
                    {teller && d.status === 'ACTIVE' && [1, 2, 3, 4].includes(d.typeCode) && (
                      <Button size="small" color="warning" onClick={(e) => { e.stopPropagation(); setClosing({ d, mode: 'PAYOUT' }) }}>Pre-terminate</Button>
                    )}
                    {teller && d.status === 'MATURED' && [1, 2, 3, 4].includes(d.typeCode) && (
                      <>
                        <Button size="small" onClick={(e) => { e.stopPropagation(); setClosing({ d, mode: 'PAYOUT' }) }}>Pay out</Button>
                        <Button size="small" onClick={(e) => { e.stopPropagation(); setClosing({ d, mode: 'RENEW' }) }}>Renew</Button>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              <TableRow>
                <TableCell colSpan={7} sx={{ fontWeight: 700 }}>Total time deposits</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{peso(data.totalBalance)}</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700, ...num }}>{data.totalAccrued ? peso(data.totalAccrued) : '—'}</TableCell>
                <TableCell />
                <TableCell />
              </TableRow>
            </TableBody>
          </Table>
        </TableCard>
      )}
      <Typography sx={{ fontSize: 12, mt: 1 }} color="text.secondary">
        Interest at maturity is an estimate: the rate over the term on a 30-day month, 360-day year. A renewal also carries a 10.00 certificate fee. A running Lock In pays its interest by the year on request (Withdraw interest, from an anniversary on). A matured certificate is paid out or renewed, and a running one pre-terminated (a termination charge, and interest at a reduced rate for the days held), only when someone asks for it here; the voucher then waits on the Front Office Disburse tab. The TD Maturities tab of the Front Office lists what is due.
      </Typography>
      {open && <TimeDepositHistory key={`${open.typeCode}-${open.refNo}`} id={id} typeCode={open.typeCode} refNo={open.refNo} onClose={() => setOpen(null)} />}
      {placing && <TimeDepositPlaceDialog clientId={id.clientId} onClose={() => setPlacing(false)} />}
      {interestFor && <TimeDepositInterestDialog clientId={id.clientId} typeCode={interestFor.typeCode} refNo={interestFor.refNo}
        label={`${interestFor.type ?? 'Time deposit'} ${interestFor.certificate ?? interestFor.refNo}`} onClose={() => setInterestFor(null)} />}
      {closing && <TimeDepositCloseDialog clientId={id.clientId} typeCode={closing.d.typeCode} refNo={closing.d.refNo} initialMode={closing.mode}
        label={`${closing.d.type ?? 'Time deposit'} ${closing.d.certificate ?? closing.d.refNo}`} onClose={() => setClosing(null)} />}
    </>
  )
}

function TimeDepositHistory({ id, typeCode, refNo, onClose }: { id: Id; typeCode: number; refNo: number; onClose: () => void }) {
  const [page, setPage] = useState(0)
  const size = 25
  const { data, error } = useQuery({ queryKey: ['member-td-history', id, typeCode, refNo, page], queryFn: () => getTimeDepositHistory(id.branchCode, id.clientId, typeCode, refNo, page, size) })
  const num = { fontVariantNumeric: 'tabular-nums' } as const
  return (
    <DetailPanel
      title={data ? `${data.deposit.type ?? 'Time deposit'} ${data.deposit.certificate ?? ''}` : 'Time deposit'} onClose={onClose}
      footer={data && data.totalEntries > size ? <>
        <Box sx={{ flex: 1 }} />
        <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Newer</Button>
        <Button disabled={(page + 1) * size >= data.totalEntries} onClick={() => setPage((p) => p + 1)}>Older</Button>
      </> : undefined}
    >
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {data && data.entries.length === 0 && <Alert severity="info">This certificate has no entries in the ledger.</Alert>}
        {data && data.entries.length > 0 && (
          <Table size="small" aria-label="Time deposit entries">
            <TableHead>
              <TableRow><TableCell>Date</TableCell><TableCell>TR</TableCell><TableCell>Document</TableCell><TableCell>Entry</TableCell><TableCell>Cheque</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell><TableCell align="right">Principal after</TableCell></TableRow>
            </TableHead>
            <TableBody>
              {data.entries.map((e, i) => (
                <TableRow key={`${e.documentNo}-${i}`}>
                  <TableCell>{fmtDate(e.date) ?? '—'}</TableCell><TableCell>{e.transaction ?? `Code ${e.transactionCode}`}</TableCell><TableCell>{e.documentNo ?? '—'}</TableCell><TableCell>{e.eventName}</TableCell>
                  <TableCell>{e.chequeNo ? `${e.chequeNo} · ${e.chequeCleared ? (e.clearedOn ? `cleared ${fmtDate(e.clearedOn)}` : 'cleared') : 'not cleared'}` : ''}</TableCell>
                  <TableCell align="right" sx={{ color: 'error.main', ...num }}>{e.amount < 0 ? peso(-e.amount) : ''}</TableCell><TableCell align="right" sx={num}>{e.amount > 0 ? peso(e.amount) : ''}</TableCell>
                  <TableCell align="right" sx={num}>{e.event === 11 ? peso(e.balanceAfter) : ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
    </DetailPanel>
  )
}

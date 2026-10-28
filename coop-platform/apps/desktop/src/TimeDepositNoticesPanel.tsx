import { Alert, Box, Button, Chip, MenuItem, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getTdMaturities, type TdNotice } from './api'
import TimeDepositCloseDialog from './TimeDepositCloseDialog'
import { glass } from './glass'
import { money as peso } from './ui/format'


/**
 * Time deposits that matured and still hold money, and those maturing soon, so staff can reach the member and then pay out or renew each one by hand. Nothing
 * here runs by itself: a certificate stays as it is until someone encodes its voucher.
 */
export function TimeDepositNoticesPanel() {
  const [days, setDays] = useState(30)
  const [act, setAct] = useState<{ n: TdNotice; mode: 'PAYOUT' | 'RENEW' } | null>(null)
  const { data, error, isLoading } = useQuery({ queryKey: ['td-maturities', days], queryFn: () => getTdMaturities(days), refetchInterval: 60_000 })
  const num = { fontVariantNumeric: 'tabular-nums' } as const
  return (
    <Box sx={(t) => ({ p: 1.5, flex: 1, minHeight: 0, overflow: 'auto', ...glass(t) })}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1, flexWrap: 'wrap' }}>
        <Typography sx={{ fontSize: 12, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' }} color="text.secondary">Time deposit maturities</Typography>
        <TextField select size="small" label="Maturing within" value={days} onChange={(e) => setDays(Number(e.target.value))} sx={{ width: 160 }}>
          {[7, 14, 30, 60, 90].map((d) => <MenuItem key={d} value={d}>{d} days</MenuItem>)}
        </TextField>
        {data && <Chip size="small" color={data.overdue ? 'warning' : 'default'} label={`${data.overdue} matured, waiting`} />}
        {data && <Chip size="small" label={`${data.maturingSoon} within a week`} />}
        {data && <Typography sx={{ fontSize: 13 }}>Holding <b>{peso(data.totalBalance)}</b></Typography>}
      </Box>
      {error && <Alert severity="error">{(error as Error).message}</Alert>}
      {isLoading && <Typography color="text.secondary">Loading…</Typography>}
      {data && data.notices.length === 0 && <Typography sx={{ fontSize: 13.5 }} color="text.secondary">No certificate is maturing in these days, and none is waiting after maturity.</Typography>}
      {data && data.notices.length > 0 && (
        <Table size="small" stickyHeader aria-label="Time deposit maturities">
          <TableHead>
            <TableRow>
              <TableCell>Matures</TableCell><TableCell>When</TableCell><TableCell>Member</TableCell><TableCell>Certificate</TableCell><TableCell align="right">Term</TableCell>
              <TableCell align="right">Rate %</TableCell><TableCell align="right">Balance</TableCell><TableCell align="right">Interest (estimate)</TableCell><TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {data.notices.map((n) => (
              <TableRow key={`${n.typeCode}-${n.refNo}`} hover>
                <TableCell>{n.maturity}</TableCell>
                <TableCell sx={{ color: n.overdue ? 'warning.main' : undefined, fontWeight: n.overdue ? 700 : 400 }}>
                  {n.daysLeft === 0 ? 'Today' : n.overdue ? `${-n.daysLeft} day${-n.daysLeft === 1 ? '' : 's'} ago` : `in ${n.daysLeft} day${n.daysLeft === 1 ? '' : 's'}`}
                </TableCell>
                <TableCell>{n.client ?? n.clientId}</TableCell>
                <TableCell>{n.type ?? `Type ${n.typeCode}`} {n.certificate ?? n.refNo}</TableCell>
                <TableCell align="right" sx={num}>{n.termDays}</TableCell>
                <TableCell align="right" sx={num}>{n.ratePercent == null ? '—' : n.ratePercent.toFixed(2)}</TableCell>
                <TableCell align="right" sx={num}>{peso(n.balance)}</TableCell>
                <TableCell align="right" sx={num}>{peso(n.estimatedInterest)}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {n.canClose ? (
                    <>
                      <Button size="small" onClick={() => setAct({ n, mode: 'PAYOUT' })}>Pay out</Button>
                      <Button size="small" onClick={() => setAct({ n, mode: 'RENEW' })}>Renew</Button>
                    </>
                  ) : <Typography component="span" sx={{ fontSize: 12 }} color="text.secondary">Not due yet</Typography>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {act && <TimeDepositCloseDialog clientId={act.n.clientId} typeCode={act.n.typeCode} refNo={act.n.refNo} initialMode={act.mode}
        label={`${act.n.client ?? act.n.clientId} · ${act.n.type ?? 'Time deposit'} ${act.n.certificate ?? act.n.refNo}`} onClose={() => setAct(null)} />}
    </Box>
  )
}

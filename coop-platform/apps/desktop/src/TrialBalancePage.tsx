import {
  Alert, Container, Paper, Table, TableBody, TableCell, TableContainer, TableFooter, TableHead, TableRow, TextField,
} from '@mui/material'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getTrialBalance } from './api'
import { money } from './ui/format'
import { useMe } from './session'
import { BranchSelect } from './ui/BranchSelect'
import { BusyArea, Loading } from './ui/Loading'


function monthStart() {
  const d = new Date()
  return new Date(d.getFullYear(), d.getMonth(), 1).toLocaleDateString('en-CA')
}

export default function TrialBalancePage() {
  const me = useMe()
  const [branchCode, setBranchCode] = useState(me.branchCode ?? 1)
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(new Date().toLocaleDateString('en-CA'))

  const { data, error, isFetching } = useQuery({
    queryKey: ['trial-balance', branchCode, from, to],
    queryFn: () => getTrialBalance(branchCode, from, to),
    enabled: Number.isInteger(branchCode) && branchCode > 0 && !!from && !!to,
    placeholderData: keepPreviousData,
  })

  return (
    <Container sx={{ py: 3 }}>
      <BranchSelect value={branchCode} onChange={setBranchCode} sx={{ mr: 2, mb: 2 }} />
      <TextField label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ mr: 2, mb: 2 }} />
      <TextField label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ mb: 2 }} />
      {error && <Alert severity="error" sx={{ mb: 2 }}>{(error as Error).message}</Alert>}
      {isFetching && !data && <Loading what="the trial balance" />}
      {data && (
        <Alert severity={data.balanced ? 'success' : 'warning'} sx={{ mb: 2 }}>
          {data.balanced ? 'Debits equal credits.' : `Out of balance by ${money(Math.abs(data.totalDebit - data.totalCredit))}.`}
        </Alert>
      )}
      <BusyArea busy={isFetching && !!data} label="Loading the trial balance"><TableContainer component={Paper}>
        <Table size="small">
          <TableHead>
            <TableRow><TableCell>Code</TableCell><TableCell>Account</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow>
          </TableHead>
          <TableBody>
            {data?.lines.map((l) => (
              <TableRow key={l.accountCode} hover>
                <TableCell>{l.accountCode}</TableCell>
                <TableCell>{l.description}</TableCell>
                <TableCell align="right">{money(l.debit)}</TableCell>
                <TableCell align="right">{money(l.credit)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
          {data && (
            <TableFooter>
              <TableRow>
                <TableCell colSpan={2}><strong>Total</strong></TableCell>
                <TableCell align="right"><strong>{money(data.totalDebit)}</strong></TableCell>
                <TableCell align="right"><strong>{money(data.totalCredit)}</strong></TableCell>
              </TableRow>
            </TableFooter>
          )}
        </Table>
      </TableContainer></BusyArea>
    </Container>
  )
}

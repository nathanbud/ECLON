import {
  Alert, Container, Paper, Table, TableBody, TableCell, TableContainer, TableFooter, TableHead, TableRow, TextField,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getTrialBalance } from './api'

const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function monthStart() {
  const d = new Date()
  return new Date(d.getFullYear(), d.getMonth(), 1).toLocaleDateString('en-CA')
}

export default function TrialBalancePage() {
  const [branch, setBranch] = useState('1')
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(new Date().toLocaleDateString('en-CA'))
  const branchCode = Number(branch)

  const { data, error, isFetching } = useQuery({
    queryKey: ['trial-balance', branchCode, from, to],
    queryFn: () => getTrialBalance(branchCode, from, to),
    enabled: Number.isInteger(branchCode) && branchCode > 0 && !!from && !!to,
  })

  return (
    <Container sx={{ py: 3 }}>
      <TextField label="Branch code" value={branch} onChange={(e) => setBranch(e.target.value)} sx={{ mr: 2, mb: 2, width: 140 }} />
      <TextField label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ mr: 2, mb: 2 }} />
      <TextField label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} sx={{ mb: 2 }} />
      {error && <Alert severity="error" sx={{ mb: 2 }}>{(error as Error).message}</Alert>}
      {data && (
        <Alert severity={data.balanced ? 'success' : 'warning'} sx={{ mb: 2 }}>
          {data.balanced ? 'Debits equal credits.' : `Out of balance by ${money(Math.abs(data.totalDebit - data.totalCredit))}.`}
        </Alert>
      )}
      <TableContainer component={Paper} sx={{ opacity: isFetching ? 0.6 : 1 }}>
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
      </TableContainer>
    </Container>
  )
}

import {
  Alert, Container, Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField,
} from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { listAccounts } from './api'

export default function AccountsPage() {
  const [branch, setBranch] = useState('1')
  const [filter, setFilter] = useState('')
  const branchCode = Number(branch)

  const { data, error, isFetching } = useQuery({
    queryKey: ['accounts', branchCode],
    queryFn: () => listAccounts(branchCode),
    enabled: Number.isInteger(branchCode) && branchCode > 0,
  })

  const rows = useMemo(() => {
    const f = filter.trim().toLowerCase()
    if (!f) return data ?? []
    return (data ?? []).filter((a) =>
      a.id.code.toLowerCase().includes(f) || (a.description ?? '').toLowerCase().includes(f))
  }, [data, filter])

  return (
    <Container sx={{ py: 3 }}>
      <TextField label="Branch code" value={branch} onChange={(e) => setBranch(e.target.value)} sx={{ mr: 2, width: 140 }} />
      <TextField label="Filter by code or description" value={filter} onChange={(e) => setFilter(e.target.value)} sx={{ mb: 2, width: 360 }} />
      {error && <Alert severity="error" sx={{ mb: 2 }}>{(error as Error).message}</Alert>}
      <TableContainer component={Paper} sx={{ opacity: isFetching ? 0.6 : 1 }}>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Code</TableCell><TableCell>Description</TableCell><TableCell>Type</TableCell>
              <TableCell>Level</TableCell><TableCell>Summary acct</TableCell><TableCell>Balance type</TableCell><TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((a) => (
              <TableRow key={a.id.code} hover>
                <TableCell>{a.id.code}</TableCell>
                <TableCell>{a.description}</TableCell>
                <TableCell>{a.accountType}</TableCell>
                <TableCell>{a.level}</TableCell>
                <TableCell>{a.summaryAccount}</TableCell>
                <TableCell>{a.balanceType}</TableCell>
                <TableCell>{a.disabled ? 'Disabled' : 'Active'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Container>
  )
}

import { Alert, Box, Button, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { getLookups, getMember, getSavings, type SavingsAccount } from './api'
import { displayName, memberNo, type Id } from './clients'
import { SavingsCashDialog } from './ClientProfilePage'
import { FindBody } from './FindClientDialog'
import { glass } from './glass'

const peso = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/**
 * Cash deposits to and withdrawals from a member's savings account. This lives in the Front Office only: the teller finds the member here, and the posting rules (open drawer,
 * limits, holds, supervisor approvals) are the cashier's. The member's profile only shows the accounts.
 */
export default function SavingsTellerPanel() {
  const { data: lk } = useQuery({ queryKey: ['lookups'], queryFn: getLookups, staleTime: Infinity })
  const [id, setId] = useState<Id | null>(null)
  const [cash, setCash] = useState<{ account: SavingsAccount; mode: 'deposit' | 'withdraw' } | null>(null)
  const member = useQuery({ queryKey: ['member', id], queryFn: () => getMember(id!.branchCode, id!.clientId), enabled: !!id })
  const savings = useQuery({ queryKey: ['member-savings', id], queryFn: () => getSavings(id!.branchCode, id!.clientId), enabled: !!id })
  const num = { fontVariantNumeric: 'tabular-nums' } as const

  if (!id) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', pt: '6vh' }}>
        <Box sx={(t) => ({ width: '100%', maxWidth: 580, overflow: 'hidden', ...glass(t, { elevated: true }) })}>
          <Typography sx={{ px: 2, pt: 1.5, fontSize: 13 }} color="text.secondary">Find the member to take a savings deposit from or pay a withdrawal to.</Typography>
          <FindBody inline lk={lk} onPick={setId} />
        </Box>
      </Box>
    )
  }
  return (
    <Box sx={{ minWidth: 0 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 1 }}>
        <Typography sx={{ fontWeight: 700, fontSize: 16 }}>{member.data ? displayName(member.data) : '…'}</Typography>
        <Typography sx={{ fontSize: 13, ...num }} color="text.secondary">{memberNo(id)}</Typography>
        <Box sx={{ flex: 1 }} />
        <Button size="small" onClick={() => { setId(null); setCash(null) }}>Change member</Button>
      </Box>
      {savings.error && <Alert severity="error">{(savings.error as Error).message}</Alert>}
      {savings.isLoading && <Skeleton variant="rounded" height={120} />}
      {savings.data && savings.data.accounts.length === 0 && <Typography color="text.secondary">This member has no savings accounts.</Typography>}
      {savings.data && savings.data.accounts.length > 0 && (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Savings accounts">
            <TableHead>
              <TableRow>
                <TableCell>Type</TableCell><TableCell>Account no.</TableCell><TableCell>Status</TableCell>
                <TableCell align="right">Balance</TableCell><TableCell align="right">On hold</TableCell><TableCell align="right">Available</TableCell><TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {savings.data.accounts.map((a) => (
                <TableRow key={`${a.typeCode}-${a.refNo}`} hover>
                  <TableCell>{a.type ?? `Type ${a.typeCode}`}</TableCell><TableCell>{a.refNoText ?? a.refNo}</TableCell><TableCell>{a.statusName}</TableCell>
                  <TableCell align="right" sx={num}>{peso(a.balance)}</TableCell>
                  <TableCell align="right" sx={num}>{a.held ? peso(a.held) : '—'}</TableCell>
                  <TableCell align="right" sx={num}>{peso(a.available)}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {a.status === 15 && (
                      <>
                        <Button size="small" onClick={() => setCash({ account: a, mode: 'deposit' })}>Deposit</Button>
                        <Button size="small" onClick={() => setCash({ account: a, mode: 'withdraw' })}>Withdraw</Button>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
      {cash && <SavingsCashDialog id={id} account={cash.account} mode={cash.mode} onClose={() => { setCash(null); void savings.refetch() }} />}
    </Box>
  )
}

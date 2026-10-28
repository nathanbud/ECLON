import { type Id } from '../clients'
import { useQuery } from '@tanstack/react-query'
import { getCoMakerNotice } from '../api'
import { Alert, Box } from '@mui/material'
import { FiInfo } from 'react-icons/fi'
import { fmtDate, peso } from './format'

/** Says on a member's accounts when part of the money is held because they are a co-maker for someone else's loan, or has agreed to be. */
export function CoMakerNote({ id }: { id: Id }) {
  const { data } = useQuery({ queryKey: ['comaker-notice', id], queryFn: () => getCoMakerNotice(id.branchCode, id.clientId), staleTime: 60_000 })
  if (!data || (data.placed.length === 0 && data.pending.length === 0)) return null
  return (
    <Alert severity="info" icon={<FiInfo />} sx={{ mb: 1.5, alignItems: 'flex-start' }}>
      {data.placed.length > 0 && (
        <Box>
          <strong>{peso(data.totalHeld)} is on hold because this member is a co-maker.</strong>
          {data.placed.map((p, i) => (
            <Box key={i} sx={{ fontSize: 13 }}>
              {peso(p.amount)} on {p.accountType ?? 'account'} {p.refNo} · co-maker for {p.borrower}{p.reference ? ` (${p.reference})` : ''}{p.since ? ` since ${fmtDate(p.since)}` : ''}
            </Box>
          ))}
        </Box>
      )}
      {data.pending.length > 0 && (
        <Box sx={{ mt: data.placed.length > 0 ? 1 : 0 }}>
          <strong>Agreed to co-make, not held yet.</strong> It will be put on hold when the loan is released.
          {data.pending.map((p, i) => (
            <Box key={i} sx={{ fontSize: 13 }}>{peso(p.amount)} on {p.accountType ?? 'account'} {p.refNo} · co-maker for {p.borrower ?? 'a borrower'} (application {p.applicationId})</Box>
          ))}
        </Box>
      )}
    </Alert>
  )
}

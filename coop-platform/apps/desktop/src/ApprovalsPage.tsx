import { Alert, Box, Skeleton, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { FiCheckCircle } from 'react-icons/fi'
import { listChanges } from './api'
import type { Id } from './clients'
import { appBackground, glass } from './glass'
import { useMe } from './session'

const when = (at: string) => new Date(at.replace(' ', 'T')).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** Changes waiting for approval. Membership Officers see everyone's; other people see only their own. */
export default function ApprovalsPage({ onOpenClient }: { onOpenClient: (id: Id) => void }) {
  const me = useMe()
  const { data, error, isLoading } = useQuery({ queryKey: ['changes', 'PENDING'], queryFn: () => listChanges('PENDING'), refetchInterval: 30_000 })

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1000, mx: 'auto' }}>
        <Typography component="h1" sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', mb: 0.5 }}>Approvals</Typography>
        <Typography sx={{ fontSize: 13, mb: 2 }} color="text.secondary">
          {me.canApprove ? 'Changes to client profiles waiting for a Membership Officer.' : 'Your changes that are waiting for a Membership Officer.'}
        </Typography>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {isLoading && <Skeleton variant="rounded" height={160} />}
        {data && data.length === 0 && (
          <Box sx={(t) => ({ p: 4, textAlign: 'center', ...glass(t) })}>
            <Box sx={{ fontSize: 32, color: 'success.main', display: 'flex', justifyContent: 'center', mb: 1 }}><FiCheckCircle /></Box>
            <Typography sx={{ fontWeight: 600 }}>Nothing is waiting</Typography>
            <Typography sx={{ fontSize: 13 }} color="text.secondary">New requests appear here.</Typography>
          </Box>
        )}
        {data && data.length > 0 && (
          <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
            <Table size="small" aria-label="Changes waiting for approval">
              <TableHead>
                <TableRow><TableCell>Client</TableCell><TableCell>Changes</TableCell><TableCell>Requested by</TableCell><TableCell>When</TableCell></TableRow>
              </TableHead>
              <TableBody>
                {data.map((c) => (
                  <TableRow
                    key={c.id} hover tabIndex={0} sx={{ cursor: 'pointer' }}
                    onClick={() => onOpenClient({ branchCode: c.branchCode, clientId: c.clientId })}
                    onKeyDown={(e) => { if (e.key === 'Enter') onOpenClient({ branchCode: c.branchCode, clientId: c.clientId }) }}
                  >
                    <TableCell sx={{ fontWeight: 600 }}>{c.clientName ?? `Client ${c.clientId}`}</TableCell>
                    <TableCell>{c.items.map((i) => i.label).join(', ')}</TableCell>
                    <TableCell>{c.requestedByName}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{when(c.requestedAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </Box>
    </Box>
  )
}

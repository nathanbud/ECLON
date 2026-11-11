import { Alert, Box, Button, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { decideWorkflow, getWorkflowWaiting, type WorkflowWaiting } from './api'
import { glass } from './glass'
import { ReasonDialog } from './ui/dialogs'
import { money } from './ui/format'


/**
 * The extra approvals waiting for the signed-in person: items whose usual approver has already approved and whose workflow now asks for this person's role (and who did not make
 * the item or approve an earlier step). Taking the last step finishes the item in its own module.
 */
export default function WorkflowInbox() {
  const qc = useQueryClient()
  const list = useQuery({ queryKey: ['workflow-waiting'], queryFn: getWorkflowWaiting, refetchInterval: 30_000 })
  const [rejecting, setRejecting] = useState<WorkflowWaiting | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['workflow-waiting'] })
    for (const k of ['po-list', 'pr-list', 'inv-adjustments', 'inv-products', 'inv-ledger']) void qc.invalidateQueries({ queryKey: [k] })
  }
  const approve = useMutation({ mutationFn: (w: WorkflowWaiting) => decideWorkflow(w.entityType, w.entityId, 'approve'), onSuccess: () => { setError(null); refresh() }, onError: (e) => setError((e as Error).message) })
  const items = list.data ?? []
  return (
    <Box sx={{ mt: 3 }}>
      <Typography component="h2" sx={{ fontSize: 15, fontWeight: 700, mb: 0.5 }}>Other approvals waiting for you</Typography>
      <Typography sx={{ fontSize: 13, mb: 1.5 }} color="text.secondary">Purchases and stock adjustments that need one more approval from your role because of their amount.</Typography>
      {error && <Alert severity="error" sx={{ mb: 1 }} onClose={() => setError(null)}>{error}</Alert>}
      {items.length === 0 ? <Typography sx={{ fontSize: 13 }} color="text.secondary">Nothing is waiting for you.</Typography> : (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Extra approvals waiting for you">
            <TableHead><TableRow><TableCell>Kind</TableCell><TableCell>Item</TableCell><TableCell align="right">Amount</TableCell><TableCell>Made by</TableCell><TableCell>Your step</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {items.map((w) => (
                <TableRow key={w.id} hover>
                  <TableCell>{w.label}</TableCell><TableCell>{w.description}</TableCell><TableCell align="right">{money(w.amount)}</TableCell><TableCell>{w.requestedBy}</TableCell><TableCell>{w.step}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    <Button size="small" disabled={approve.isPending} onClick={() => approve.mutate(w)}>Approve</Button>
                    <Button size="small" color="error" onClick={() => setRejecting(w)}>Turn down</Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
      {rejecting && <ReasonDialog title={`Turn down ${rejecting.description}`} label="Reason" onRun={(n) => decideWorkflow(rejecting.entityType, rejecting.entityId, 'reject', n).then(refresh)} onClose={() => setRejecting(null)} />}
    </Box>
  )
}

import { Alert, Box, Button, Chip, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { FiCheckCircle } from 'react-icons/fi'
import { getInbox, listChanges, type InboxItem } from './api'
import type { Id } from './clients'
import type { View } from './Dashboard'
import { appBackground, glass } from './glass'
import { useMe } from './session'
import MemberRequestsPanel from './MemberRequests'
import WorkflowInbox from './WorkflowInbox'
import { Loading } from './ui/Loading'
import { ScreenTitle } from './appleUi'

const when = (at: string) => new Date(at.replace(' ', 'T')).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** Items whose decision is made on this very page, below the list. Every other kind is decided on its own screen. */
const DECIDED_HERE = new Set(['CHANGE', 'MEMBER_REQUEST', 'WORKFLOW'])

/**
 * Everything waiting on you, in one list, oldest first, each with a button to the screen that decides it. Below it, the decisions that are made here: client profile changes, member
 * requests and the extra approval steps. Membership Officers see everyone's changes; other people see only their own.
 */
function Everything({ onOpen }: { onOpen: (v: View, tab?: string) => void }) {
  const list = useQuery({ queryKey: ['inbox'], queryFn: getInbox, refetchInterval: 30_000 })
  const items: InboxItem[] = list.data?.items ?? []
  return (
    <Box component="section" aria-label="Everything waiting for you" sx={{ mb: 3 }}>
      {list.error && <Alert severity="error" sx={{ mb: 1 }}>{(list.error as Error).message}</Alert>}
      {list.isLoading && <Loading what="what is waiting" />}
      {list.data && items.length === 0 && (
        <Box sx={(t) => ({ p: 4, textAlign: 'center', ...glass(t) })}>
          <Box sx={{ fontSize: 28, color: 'success.main', display: 'flex', justifyContent: 'center', mb: 1 }}><FiCheckCircle /></Box>
          <Typography sx={{ fontWeight: 600 }}>Nothing is waiting for you</Typography>
          <Typography color="text.secondary">Journals, memos, purchases, stock adjustments and member requests that need you appear here.</Typography>
        </Box>
      )}
      {items.length > 0 && (
        <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Everything waiting for you">
            <TableHead><TableRow><TableCell>What</TableCell><TableCell>Item</TableCell><TableCell>Made by</TableCell><TableCell>Since</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {items.map((i, n) => (
                <TableRow key={`${i.kind}-${n}`} hover>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}><Chip size="small" variant="outlined" label={i.label} /></TableCell>
                  <TableCell>{i.summary}</TableCell>
                  <TableCell>{i.by ?? ''}</TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{i.at ? when(i.at) : ''}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {DECIDED_HERE.has(i.kind)
                      ? <Typography variant="body2" component="span" color="text.secondary">Decide below</Typography>
                      : <Button size="small" onClick={() => onOpen(i.view as View, i.tab ?? undefined)}>Open</Button>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
    </Box>
  )
}

/** Changes waiting for approval. Membership Officers see everyone's; other people see only their own. */
export default function ApprovalsPage({ onOpenClient, onOpen }: { onOpenClient: (id: Id) => void; onOpen: (v: View, tab?: string) => void }) {
  const me = useMe()
  const { data, error, isLoading } = useQuery({ queryKey: ['changes', 'PENDING'], queryFn: () => listChanges('PENDING'), refetchInterval: 30_000 })

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1000, mx: 'auto' }}>
        <ScreenTitle title="Waiting for you" sx={{ mb: 2 }}>
          Everything that needs your approval or action, from every part of the system. Open an item to decide it where it belongs.
        </ScreenTitle>
        <Everything onOpen={onOpen} />
        <Typography variant="h6" component="h2" sx={{ mb: 0.5 }}>Member profile changes</Typography>
        <Typography sx={{ mb: 1.5 }} color="text.secondary">
          {me.canApprove ? 'Changes to member profiles waiting for a Membership Officer.' : 'Your changes that are waiting for a Membership Officer.'}
        </Typography>
        {error && <Alert severity="error">{(error as Error).message}</Alert>}
        {isLoading && <Loading what="the profile changes" />}
        {data && data.length === 0 && (
          <Box sx={(t) => ({ p: 4, textAlign: 'center', ...glass(t) })}>
            <Box sx={{ fontSize: 28, color: 'success.main', display: 'flex', justifyContent: 'center', mb: 1 }}><FiCheckCircle /></Box>
            <Typography sx={{ fontWeight: 600 }}>Nothing is waiting</Typography>
            <Typography color="text.secondary">New requests appear here.</Typography>
          </Box>
        )}
        {data && data.length > 0 && (
          <Box sx={(t) => ({ overflow: 'auto', ...glass(t) })}>
            <Table size="small" aria-label="Changes waiting for approval">
              <TableHead>
                <TableRow><TableCell>Member</TableCell><TableCell>Changes</TableCell><TableCell>Requested by</TableCell><TableCell>When</TableCell></TableRow>
              </TableHead>
              <TableBody>
                {data.map((c) => (
                  <TableRow
                    key={c.id} hover tabIndex={0} sx={{ cursor: 'pointer' }}
                    onClick={() => onOpenClient({ branchCode: c.branchCode, clientId: c.clientId })}
                    onKeyDown={(e) => { if (e.key === 'Enter') onOpenClient({ branchCode: c.branchCode, clientId: c.clientId }) }}
                  >
                    <TableCell sx={{ fontWeight: 600 }}>{c.clientName ?? `Member ${c.clientId}`}</TableCell>
                    <TableCell>{c.items.map((i) => i.label).join(', ')}</TableCell>
                    <TableCell>{c.requestedByName}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{when(c.requestedAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
        <MemberRequestsPanel />
        <WorkflowInbox />
      </Box>
    </Box>
  )
}

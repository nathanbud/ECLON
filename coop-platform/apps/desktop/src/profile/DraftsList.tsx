import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, IconButton, Tooltip, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiTrash2 } from 'react-icons/fi'
import { deleteMemberDraft, getMemberDraft, listMemberDrafts, type MemberDraft, type MemberDraftSummary } from '../api'

const when = (at: string) => new Date(at).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })

/** The new-member registrations this person saved as drafts: open one to carry on, or delete it. Shows nothing when there are none. */
export function DraftsList({ onResume }: { onResume: (draft: MemberDraft) => void }) {
  const qc = useQueryClient()
  const { data } = useQuery({ queryKey: ['member-drafts'], queryFn: listMemberDrafts, retry: false })
  const [error, setError] = useState<string | null>(null)
  const [asking, setAsking] = useState<MemberDraftSummary | null>(null)
  const open = useMutation({ mutationFn: (id: number) => getMemberDraft(id), onSuccess: (d) => onResume(d), onError: (e: Error) => setError(e.message) })
  const remove = useMutation({
    mutationFn: (id: number) => deleteMemberDraft(id),
    onSuccess: () => { setAsking(null); void qc.invalidateQueries({ queryKey: ['member-drafts'] }) },
    onError: (e: Error) => { setAsking(null); setError(e.message) },
  })
  if (!data || data.length === 0) return null
  return (
    <Box component="section" aria-label="Saved drafts" sx={(t) => ({ borderTop: `1px solid ${t.palette.divider}`, px: 2, py: 1.25 })}>
      <Typography component="h2" sx={{ fontSize: 12, fontWeight: 600, letterSpacing: '0.06em', textTransform: 'uppercase', mb: 0.5 }} color="text.secondary">Saved drafts ({data.length})</Typography>
      {error && <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 0.75 }}>{error}</Alert>}
      {data.map((d) => (
        <Box key={d.id} sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.25 }}>
          <Typography sx={{ flex: 1, minWidth: 0, fontSize: 13 }} noWrap>{d.title}</Typography>
          <Typography sx={{ fontSize: 12, flexShrink: 0 }} color="text.secondary">{when(d.updatedAt)}</Typography>
          <Button size="small" onClick={() => open.mutate(d.id)} disabled={open.isPending} aria-label={`Continue the draft for ${d.title}`}>Continue</Button>
          <Tooltip title="Delete this draft">
            <IconButton size="small" aria-label={`Delete the draft for ${d.title}`} onClick={() => setAsking(d)}><FiTrash2 size={15} /></IconButton>
          </Tooltip>
        </Box>
      ))}
      <Dialog open={!!asking} onClose={() => setAsking(null)}>
        <DialogTitle>Delete this draft?</DialogTitle>
        <DialogContent><DialogContentText>The draft for {asking?.title} will be removed. This cannot be undone.</DialogContentText></DialogContent>
        <DialogActions>
          <Button onClick={() => setAsking(null)} autoFocus>Keep it</Button>
          <Button color="error" onClick={() => asking && remove.mutate(asking.id)} disabled={remove.isPending}>Delete</Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}

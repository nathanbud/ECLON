import { Alert, Avatar, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Skeleton, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { FiUser } from 'react-icons/fi'
import { getMember, type Member } from './api'
import { displayName, memberNo, statusOf, type Id } from './clients'
import { glass } from './glass'
import { useMemberImage } from './memberImage'
import { tileFill } from './theme'

/**
 * The applicant of a loan application, in a box of their own: photo, name, member number and status, as on the client profile.
 * The member is passed in when the clerk has just picked one, otherwise it is loaded by id.
 */
export default function LoanApplicantCard({ id, member, onChange }: { id: Id; member?: Member | null; onChange?: () => void }) {
  const { data: m } = useQuery({ queryKey: ['member', id.branchCode, id.clientId], queryFn: () => getMember(id.branchCode, id.clientId), initialData: member ?? undefined, staleTime: 60_000 })
  const photo = useMemberImage('photo', id)
  const signature = useMemberImage('signature', id)
  const [showSignature, setShowSignature] = useState(false)
  if (!m) return <Skeleton variant="rounded" height={92} sx={{ mb: 1.5 }} />
  const st = statusOf(m)
  return (
    <Box sx={{ mb: 1.5 }}>
      <Box component="section" aria-label="Applicant" sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 2, p: 1.5, ...glass(t) })}>
        <Avatar variant="rounded" src={photo ?? undefined} alt={`Photo of ${displayName(m)}`} sx={{
          width: 68, height: 68, fontSize: 22, fontWeight: 600, color: '#fff', borderRadius: '5px', flexShrink: 0,
          background: `linear-gradient(160deg, ${tileFill.orange}F2, ${tileFill.orange})`, boxShadow: '0 1px 2px rgba(0,0,0,0.14)',
        }}>{/* No photo on file: a plain person mark, not initials. */}<FiUser size={34} aria-hidden /></Avatar>
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography sx={{ fontSize: 11, fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase' }} color="text.secondary">Applicant</Typography>
          <Typography noWrap sx={{ fontSize: 17, fontWeight: 700, letterSpacing: '-0.01em', lineHeight: 1.25 }}>{displayName(m)}</Typography>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mt: 0.25, flexWrap: 'wrap' }}>
            <Typography sx={{ fontSize: 13, fontVariantNumeric: 'tabular-nums' }} color="text.secondary">
              ID {memberNo(id)}{m.referenceId ? ` · Ref ${m.referenceId}` : ''}{m.city ? ` · ${m.city}` : ''}
            </Typography>
            <Chip size="small" variant="outlined" color={st.kind === 'default' ? undefined : st.kind} label={st.label} sx={{ height: 20, fontSize: 11 }} />
          </Box>
        </Box>
        {signature && <Button size="small" onClick={() => setShowSignature(true)} sx={{ flexShrink: 0 }}>View signature</Button>}
        {onChange && <Button size="small" onClick={onChange} sx={{ flexShrink: 0 }}>Change member</Button>}
      </Box>
      <Dialog open={showSignature} onClose={() => setShowSignature(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Signature · {displayName(m)}</DialogTitle>
        <DialogContent>
          {/* Signatures are dark ink on a light background, so they sit on a white surface in both themes. */}
          <Box sx={{ bgcolor: '#fff', borderRadius: '5px', border: 1, borderColor: 'divider', p: 2, minHeight: 160, display: 'grid', placeItems: 'center' }}>
            {signature && <Box component="img" src={signature} alt={`Signature of ${displayName(m)}`} sx={{ maxWidth: '100%', maxHeight: 320, objectFit: 'contain' }} />}
          </Box>
        </DialogContent>
        <DialogActions><Button onClick={() => setShowSignature(false)}>Close</Button></DialogActions>
      </Dialog>
      {st.kind === 'default' && <Alert severity="warning" sx={{ mt: 1 }}>This member's account is not active. The application may be refused.</Alert>}
    </Box>
  )
}

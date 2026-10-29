import { getFingerprints, type Lookups, type Member } from '../api'
import { useEdit } from './editing'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { displayName, memberNo, statusOf } from '../clients'
import { useMemberImage } from '../memberImage'
import { Avatar, Box, Chip, IconButton, Tooltip, Typography } from '@mui/material'
import { glass } from '../glass'
import { tileFill } from '../theme'
import { FiAlertTriangle, FiCamera, FiCheckCircle, FiEdit2, FiShield, FiSlash, FiUser } from 'react-icons/fi'
import { IoFingerPrint } from 'react-icons/io5'
import FingerprintDialog from '../FingerprintDialog'
import PrivacyNoticeDialog from '../PrivacyNoticeDialog'
import { getMemberPrivacy } from '../api'
import { useMe } from '../session'
import MemberPhotoDialog from '../MemberPhotoDialog'
import { InlineField, SelectField, quietChip } from './fields'
import { fmtDate, yearsSince } from './format'

/** Side of the photo in pixels: the one number that decides how tall the header is. */
const PHOTO = 88

export function ProfileHeader({ m, lk, canPhoto = false, onNotice }: { m: Member; lk?: Lookups; canPhoto?: boolean; onNotice?: (msg: string) => void }) {
  const { editing } = useEdit()
  const [photoOpen, setPhotoOpen] = useState(false)
  const [fingerOpen, setFingerOpen] = useState(false)
  const [noticeOpen, setNoticeOpen] = useState(false)
  const canEdit = useMe().canEdit
  const privacy = useQuery({ queryKey: ['member-privacy', m.id.branchCode, m.id.clientId], queryFn: () => getMemberPrivacy(m.id.branchCode, m.id.clientId), staleTime: 60_000, retry: false })
  const prints = useQuery({ queryKey: ['fingerprints', m.id], queryFn: () => getFingerprints(m.id.branchCode, m.id.clientId), staleTime: 60_000, retry: false })
  const st = statusOf(m, lk)
  const photo = useMemberImage('photo', m.id)
  const signature = useMemberImage('signature', m.id)
  const alertLabel = m.alertLevel != null ? lk?.alertLevel?.[m.alertLevel] ?? `Level ${m.alertLevel}` : null
  const alertOk = m.alertLevel === 1
  return (
    <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1.75, px: 1.5, py: 1, ...glass(t, { elevated: true }) })}>
      <Box sx={{ position: 'relative', flexShrink: 0, '&:hover .photo-edit, &:focus-within .photo-edit': { opacity: 1 } }}>
        <Avatar variant="rounded" src={photo ?? undefined} alt={`Photo of ${displayName(m)}`} sx={{
          width: PHOTO, height: PHOTO, fontSize: 28, fontWeight: 600, color: '#fff', borderRadius: '8px',
          background: `linear-gradient(160deg, ${tileFill.orange}F2, ${tileFill.orange})`,
          boxShadow: '0 1px 2px rgba(0,0,0,0.14)',
        }}><FiUser size={40} aria-hidden /></Avatar>
        {canPhoto && editing && (
          <Tooltip title="Take or upload a photo">
            <IconButton className="photo-edit" size="small" aria-label="Take or upload a photo" onClick={() => setPhotoOpen(true)}
              sx={{ position: 'absolute', right: -6, bottom: -6, width: 26, height: 26, bgcolor: 'background.paper', border: 1, borderColor: 'divider', opacity: 0.85, transition: 'opacity .15s', '&:hover': { bgcolor: 'background.paper' } }}>
              <FiCamera size={13} />
            </IconButton>
          </Tooltip>
        )}
      </Box>
      {noticeOpen && <PrivacyNoticeDialog branchCode={m.id.branchCode} clientId={m.id.clientId} name={displayName(m)} canRecord={canEdit} onClose={() => setNoticeOpen(false)} onDone={(msg) => { void privacy.refetch(); onNotice?.(msg) }} />}
      {fingerOpen && <FingerprintDialog id={m.id} name={displayName(m)} canManage={canPhoto} onClose={() => setFingerOpen(false)} onDone={(msg) => { void prints.refetch(); onNotice?.(msg) }} />}
      {photoOpen && <MemberPhotoDialog id={m.id} name={displayName(m)} onClose={() => setPhotoOpen(false)} onDone={(msg) => onNotice?.(msg)} />}
      <Box sx={{ minWidth: 0, flex: 1 }}>
        {/* Status and alert: small outlined tags above the name, kept quiet so the name leads. */}
        <Box role="group" aria-label="Client status" sx={{ display: 'flex', gap: 0.75, mb: 0.5, flexWrap: 'wrap' }}>
          {editing && <Chip size="small" color="primary" label="Editing" icon={<FiEdit2 />} sx={quietChip} />}
          <Chip size="small" variant="outlined" color={st.kind === 'default' ? undefined : st.kind} label={st.label}
            icon={st.kind === 'default' ? <FiSlash /> : <FiCheckCircle />} sx={quietChip} />
          {alertLabel && (
            <Chip size="small" variant="outlined" color={alertOk ? 'success' : (m.alertLevel ?? 0) >= 6 ? 'error' : 'warning'}
              label={`Alert: ${alertLabel}`} icon={alertOk ? <FiCheckCircle /> : <FiAlertTriangle />} sx={quietChip} />
          )}
          <Chip size="small" variant="outlined" clickable color={prints.data?.fingers.length ? 'success' : 'default'} icon={<IoFingerPrint />} onClick={() => setFingerOpen(true)}
            label={prints.data?.fingers.length ? `Fingerprint · ${prints.data.fingers.length}/10` : 'Fingerprint'} aria-label="Fingerprints" sx={quietChip} />
          <Chip size="small" variant="outlined" clickable color={privacy.data?.upToDate ? 'success' : 'default'} icon={<FiShield />} onClick={() => setNoticeOpen(true)}
            label={privacy.data?.upToDate ? 'Privacy notice given' : 'Privacy notice'} aria-label="Privacy notice" sx={quietChip} />
        </Box>
        <Typography component="h2" noWrap sx={{ fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em', lineHeight: 1.2 }}>{displayName(m)}</Typography>
        <Typography sx={{ fontSize: 13, fontVariantNumeric: 'tabular-nums', mt: 0 }} color="text.secondary">
          {memberNo(m.id)}{m.referenceId ? ` · Ref ${m.referenceId}` : ''}
        </Typography>
      </Box>
      {signature && (
        <Box sx={{ display: { xs: 'none', lg: 'block' }, textAlign: 'center', flexShrink: 0 }}>
          {/* Signatures are dark ink on a light background, so they always sit on a white surface, in both themes. */}
          <Box sx={{ bgcolor: '#fff', borderRadius: '5px', border: 1, borderColor: 'divider', p: 0.5, width: 150, height: 56, display: 'grid', placeItems: 'center' }}>
            <Box component="img" src={signature} alt={`Signature of ${displayName(m)}`} sx={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
          </Box>
          <Typography sx={{ fontSize: 11, mt: 0.25 }} color="text.secondary">Signature</Typography>
        </Box>
      )}
      {/* Right edge: fingerprint and classification, stacked in a column. */}
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'stretch', gap: 1, flexShrink: 0 }}>
        {/* Classification sits with the status: client type, account type and department. */}
        <Box role="group" aria-label="Client classification" sx={{ display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', gap: '2px 10px', alignItems: 'center', mt: 0, width: 280 }}>
          <SelectField inline label="Member type" lookup="clientType" code={m.clientType} lk={lk} col="ClientType" />
          <SelectField inline label="Department" lookup="department" code={m.department} lk={lk} col="ClientDEPT" />
          <InlineField label="Date opened" value={m.dateOpened ? `${fmtDate(m.dateOpened)}${yearsSince(m.dateOpened) != null ? ` · ${yearsSince(m.dateOpened)} yrs` : ''}` : null} />
        </Box>
      </Box>
    </Box>
  )
}

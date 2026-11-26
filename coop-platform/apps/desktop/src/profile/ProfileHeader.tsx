import { getFingerprints, getKyc, getMemberPrivacy, type Lookups, type Member } from '../api'
import { useEdit } from './editing'
import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { displayName, memberNo, statusOf } from '../clients'
import { useMemberImage } from '../memberImage'
import { Avatar, Box, Chip, IconButton, Tooltip, Typography } from '@mui/material'
import { SIDE } from '../glass'
import { FiCamera, FiEdit2, FiEdit3, FiFlag, FiShield, FiUser, FiUserCheck } from 'react-icons/fi'
import { IoFingerPrint } from 'react-icons/io5'
import FingerprintDialog from '../FingerprintDialog'
import PrivacyNoticeDialog from '../PrivacyNoticeDialog'
import { useMe } from '../session'
import MemberPhotoDialog from '../MemberPhotoDialog'
import SignatureDialog from '../SignatureDialog'

const KYC_LABEL = { NONE: 'not started', INCOMPLETE: 'incomplete', EXPIRED: 'ID expired', REVIEW_DUE: 'review due', COMPLETE: 'complete' } as const

/** Side of the square photo in pixels: one and three-quarter inches (96 CSS pixels to the inch). */
export const PHOTO = 168

type Kind = 'success' | 'warning' | 'error' | 'info' | 'default'

/** A small outlined tag for the dark panel: light text, with the outline tinted by what it says. */
export const tag = (kind: Kind) => {
  const edge = kind === 'success' ? SIDE.ok : kind === 'warning' ? SIDE.warn : kind === 'error' ? SIDE.bad : SIDE.line
  const ink = kind === 'success' ? SIDE.ok : kind === 'warning' ? SIDE.warn : kind === 'error' ? SIDE.bad : SIDE.text
  return {
    height: 22, fontSize: 11, fontWeight: 500, color: ink, borderColor: edge, bgcolor: 'transparent',
    '& .MuiChip-icon': { fontSize: 13, ml: 0.75, mr: -0.25, color: 'inherit' },
    '& .MuiChip-label': { px: 0.9 },
    '&.MuiChip-clickable:hover': { bgcolor: SIDE.hover },
  } as const
}

/** The dark column down the left of the profile. Its children (the section menu) go under the identity block. */
export function SideFrame({ children, label }: { children?: ReactNode; label: string }) {
  return (
    <Box component="aside" aria-label={label} sx={(t) => ({
      display: 'flex', flexDirection: 'column', minHeight: 0, overflowY: 'auto', overflowX: 'hidden', borderRadius: '5px', color: SIDE.text,
      backgroundColor: t.palette.mode === 'dark' ? SIDE.bgDark : SIDE.bg,
      border: `1px solid ${t.palette.mode === 'dark' ? 'rgba(255,255,255,0.1)' : 'transparent'}`,
      boxShadow: t.palette.mode === 'dark' ? 'none' : '0 8px 24px rgba(20,40,90,0.16)',
      scrollbarWidth: 'thin', scrollbarColor: `${SIDE.line} transparent`,
    })}>
      {children}
    </Box>
  )
}

/**
 * The part of the dark column that scrolls on its own: the section menu. The photo, name and tags above it stay where they are. If the window is so short that even the
 * identity block does not fit, the whole column scrolls as before.
 */
export function SideScroll({ children }: { children: ReactNode }) {
  return (
    <Box sx={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto', overflowX: 'hidden', scrollbarWidth: 'thin', scrollbarColor: `${SIDE.line} transparent` }}>
      {children}
    </Box>
  )
}

/** The square picture with a coloured frame. */
export function Ring({ color, children }: { color: string; children: ReactNode }) {
  return (
    <Box sx={{ width: PHOTO + 16, height: PHOTO + 16, borderRadius: '5px', border: `1px solid ${color}`, p: '5px', boxSizing: 'border-box', position: 'relative', mx: 'auto' }}>
      {children}
    </Box>
  )
}

/**
 * The member's identity, in the dark side panel: photo in a status-coloured ring (with the camera to change it in edit mode), the name, member number, and the tags for status,
 * alert, fingerprints and the privacy notice. The section menu is passed in as children.
 */
export function ProfileHeader({ m, lk, canPhoto = false, onNotice, children }: { m: Member; lk?: Lookups; canPhoto?: boolean; onNotice?: (msg: string) => void; children?: ReactNode }) {
  const { editing } = useEdit()
  const [photoOpen, setPhotoOpen] = useState(false)
  const [fingerOpen, setFingerOpen] = useState(false)
  const [noticeOpen, setNoticeOpen] = useState(false)
  const [signatureOpen, setSignatureOpen] = useState(false)
  const canEdit = useMe().canEdit
  const privacy = useQuery({ queryKey: ['member-privacy', m.id.branchCode, m.id.clientId], queryFn: () => getMemberPrivacy(m.id.branchCode, m.id.clientId), staleTime: 60_000, retry: false })
  const prints = useQuery({ queryKey: ['fingerprints', m.id], queryFn: () => getFingerprints(m.id.branchCode, m.id.clientId), staleTime: 60_000, retry: false })
  const kyc = useQuery({ queryKey: ['kyc', m.id.branchCode, m.id.clientId], queryFn: () => getKyc(m.id.branchCode, m.id.clientId), staleTime: 60_000, retry: false })
  const st = statusOf(m, lk)
  const photo = useMemberImage('photo', m.id)
  const signature = useMemberImage('signature', m.id)
  const ring = st.kind === 'success' ? SIDE.ok : st.kind === 'warning' ? SIDE.warn : SIDE.line
  return (
    <SideFrame label="Member">
      {noticeOpen && <PrivacyNoticeDialog branchCode={m.id.branchCode} clientId={m.id.clientId} name={displayName(m)} canRecord={canEdit} onClose={() => setNoticeOpen(false)} onDone={(msg) => { void privacy.refetch(); onNotice?.(msg) }} />}
      {fingerOpen && <FingerprintDialog id={m.id} name={displayName(m)} canManage={canPhoto} onClose={() => setFingerOpen(false)} onDone={(msg) => { void prints.refetch(); onNotice?.(msg) }} />}
      {signatureOpen && <SignatureDialog id={m.id} name={displayName(m)} current={signature} canCapture={canPhoto} onClose={() => setSignatureOpen(false)} onDone={(msg) => onNotice?.(msg)} />}
      {photoOpen && <MemberPhotoDialog id={m.id} name={displayName(m)} onClose={() => setPhotoOpen(false)} onDone={(msg) => onNotice?.(msg)} />}

      <Box sx={{ px: 2, pt: 2.5, pb: 1.5, textAlign: 'center', '&:hover .photo-edit, &:focus-within .photo-edit': { opacity: 1 } }}>
        <Ring color={ring}>
          <Avatar variant="square" src={photo ?? undefined} alt={`Photo of ${displayName(m)}`} sx={{ width: PHOTO, height: PHOTO, borderRadius: '2px', bgcolor: 'rgba(255,255,255,0.14)', color: SIDE.text }}><FiUser size={72} aria-hidden /></Avatar>
          {canPhoto && editing && (
            <Tooltip title="Take or upload a photo">
              <IconButton className="photo-edit" size="small" aria-label="Take or upload a photo" onClick={() => setPhotoOpen(true)}
                sx={{ position: 'absolute', right: -4, bottom: -4, width: 28, height: 28, bgcolor: 'background.paper', color: 'text.primary', border: 1, borderColor: 'divider', opacity: 0.9, transition: 'opacity .15s', '&:hover': { bgcolor: 'background.paper' } }}>
                <FiCamera size={14} />
              </IconButton>
            </Tooltip>
          )}
        </Ring>
        <Typography component="h2" sx={{ mt: 1.5, fontSize: 20, fontWeight: 600, letterSpacing: '-0.01em', lineHeight: 1.2, overflowWrap: 'anywhere' }}>{displayName(m)}</Typography>
        <Typography sx={{ fontSize: 13, mt: 0.25, fontVariantNumeric: 'tabular-nums', color: SIDE.muted }}>
          {memberNo(m.id)}{m.referenceId ? ` · Ref ${m.referenceId}` : ''}
        </Typography>
        {/* The fingerprint, signature and privacy-notice tags. Status and alert are on the right, beside the member type. */}
        <Box role="group" aria-label="Member records" sx={{ display: 'flex', gap: 0.75, mt: 1.25, flexWrap: 'wrap', justifyContent: 'center' }}>
          {editing && <Chip size="small" variant="outlined" label="Editing" icon={<FiEdit2 />} sx={tag('default')} />}
          <Chip size="small" variant="outlined" clickable icon={<IoFingerPrint />} onClick={() => setFingerOpen(true)} aria-label="Fingerprints" sx={tag(prints.data?.fingers.length ? 'success' : 'default')}
            label={prints.data?.fingers.length ? `Fingerprint · ${prints.data.fingers.length}/10` : 'Fingerprint'} />
          <Chip size="small" variant="outlined" clickable icon={<FiEdit3 />} onClick={() => setSignatureOpen(true)} aria-label="Signature" sx={tag(signature ? 'success' : 'default')}
            label={signature ? 'Signature · on file' : 'Signature'} />
          {kyc.data && (
            <Chip size="small" variant="outlined" icon={<FiUserCheck />} aria-label={`KYC: ${KYC_LABEL[kyc.data.status]}`} sx={tag(kyc.data.status === 'COMPLETE' ? 'success' : kyc.data.status === 'NONE' ? 'default' : 'warning')}
              label={kyc.data.status === 'COMPLETE' ? 'KYC complete' : `KYC · ${KYC_LABEL[kyc.data.status]}`} />
          )}
          {m.dosriTag != null && (
            <Chip size="small" variant="outlined" icon={<FiFlag />} sx={tag('warning')} aria-label={`DOSRI: ${lk?.dosriTag?.[String(m.dosriTag)] ?? `code ${m.dosriTag}`}`}
              label={`DOSRI · ${lk?.dosriTag?.[String(m.dosriTag)] ?? `code ${m.dosriTag}`}`} />
          )}
          <Chip size="small" variant="outlined" clickable icon={<FiShield />} onClick={() => setNoticeOpen(true)} aria-label="Privacy notice" sx={tag(privacy.data?.upToDate ? 'success' : 'default')}
            label={privacy.data?.upToDate ? 'Privacy notice given' : 'Privacy notice'} />
        </Box>
      </Box>
      <SideScroll>{children}</SideScroll>
    </SideFrame>
  )
}

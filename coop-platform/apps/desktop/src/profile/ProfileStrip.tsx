import { Box, IconButton, Tooltip, Typography } from '@mui/material'
import { useState } from 'react'
import { FiEdit3 } from 'react-icons/fi'
import { type Lookups, type Member } from '../api'
import { displayName } from '../clients'
import { glass } from '../glass'
import { useMemberImage } from '../memberImage'
import SignatureDialog from '../SignatureDialog'
import { useEdit } from './editing'
import { InlineField, SelectField } from './fields'
import { fmtDate, yearsSince } from './format'

/**
 * The strip across the top of the profile's page: the name of the open section on the left, and on the right the member's classification (member type, department, date opened) and
 * signature. Member type and department are the editable ones, exactly as they were in the old header. The signature box has the pencil to capture a new one in edit mode.
 */
export function ProfileStrip({ m, lk, title, group, canPhoto = false, onNotice }: { m: Member; lk?: Lookups; title: string; group: string; canPhoto?: boolean; onNotice?: (msg: string) => void }) {
  const { editing } = useEdit()
  const [signatureOpen, setSignatureOpen] = useState(false)
  const signature = useMemberImage('signature', m.id)
  return (
    <Box sx={(t) => ({ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 2, px: 2, py: 1.25, ...glass(t) })}>
      {signatureOpen && <SignatureDialog id={m.id} name={displayName(m)} onClose={() => setSignatureOpen(false)} onDone={(msg) => onNotice?.(msg)} />}
      <Box sx={{ flex: '1 1 160px', minWidth: 0 }}>
        <Typography sx={{ fontSize: 11.5, fontWeight: 600, letterSpacing: '0.08em', textTransform: 'uppercase' }} color="text.secondary">{group}</Typography>
        <Typography component="h1" noWrap sx={{ fontSize: 22, fontWeight: 400, letterSpacing: '-0.01em', lineHeight: 1.2 }}>{title}</Typography>
      </Box>
      {/* Classification sits with the status: client type, account type and department. */}
      <Box role="group" aria-label="Client classification" sx={{ display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', gap: '2px 10px', alignItems: 'center', width: 280, maxWidth: '100%' }}>
        <SelectField inline label="Member type" lookup="clientType" code={m.clientType} lk={lk} col="ClientType" />
        <SelectField inline label="Department" lookup="department" code={m.department} lk={lk} col="ClientDEPT" />
        <InlineField label="Date opened" value={m.dateOpened ? `${fmtDate(m.dateOpened)}${yearsSince(m.dateOpened) != null ? ` · ${yearsSince(m.dateOpened)} yrs` : ''}` : null} />
      </Box>
      {(signature || (canPhoto && editing)) && (
        <Box sx={{ textAlign: 'center', flexShrink: 0 }}>
          {/* Signatures are dark ink on a light background, so they always sit on a white surface, in both themes. */}
          <Box sx={{ position: 'relative', bgcolor: '#fff', borderRadius: '5px', border: 1, borderColor: 'divider', borderStyle: signature ? 'solid' : 'dashed', p: 0.5, width: 150, height: 56, display: 'grid', placeItems: 'center' }}>
            {signature
              ? <Box component="img" src={signature} alt={`Signature of ${displayName(m)}`} sx={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
              : <Typography sx={{ fontSize: 11.5, color: '#6b6b6b' }}>No signature on file</Typography>}
            {canPhoto && editing && (
              <Tooltip title={signature ? 'Replace the signature' : 'Capture a signature'}>
                <IconButton size="small" aria-label={signature ? 'Replace the signature' : 'Capture a signature'} onClick={() => setSignatureOpen(true)}
                  sx={{ position: 'absolute', right: -8, bottom: -8, width: 24, height: 24, bgcolor: 'background.paper', border: 1, borderColor: 'divider', opacity: 0.9, '&:hover': { bgcolor: 'background.paper' } }}>
                  <FiEdit3 size={12} />
                </IconButton>
              </Tooltip>
            )}
          </Box>
          <Typography sx={{ fontSize: 11, mt: 0.25 }} color="text.secondary">Signature</Typography>
        </Box>
      )}
    </Box>
  )
}

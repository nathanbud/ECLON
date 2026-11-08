import { Box, Chip, Typography } from '@mui/material'
import { FiAlertTriangle, FiCheckCircle, FiSlash } from 'react-icons/fi'
import { type Lookups, type Member } from '../api'
import { statusOf } from '../clients'
import { glass } from '../glass'
import { InlineField, SelectField, quietChip } from './fields'
import { fmtDate, yearsSince } from './format'

/**
 * The strip across the top of the profile's page: the name of the open section on the left, and on the right the member's classification (member type, department, date opened) with the
 * account status and alert level beside it. Member type and department are the editable ones, exactly as they were in the old header. The signature is a tag in the side panel.
 */
export function ProfileStrip({ m, lk, title, group }: { m: Member; lk?: Lookups; title: string; group: string }) {
  const st = statusOf(m, lk)
  const alertLabel = m.alertLevel != null ? lk?.alertLevel?.[m.alertLevel] ?? `Level ${m.alertLevel}` : null
  const alertOk = m.alertLevel === 1
  return (
    <Box sx={(t) => ({ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 2, px: 2, py: 1, ...glass(t) })}>
      <Box sx={{ flex: '1 1 160px', minWidth: 0 }}>
        <Typography sx={{ fontSize: 11.5, fontWeight: 600, letterSpacing: '0.08em', textTransform: 'uppercase' }} color="text.secondary">{group}</Typography>
        <Typography component="h1" noWrap sx={{ fontSize: 22, fontWeight: 400, letterSpacing: '-0.01em', lineHeight: 1.2 }}>{title}</Typography>
      </Box>
      {/* Classification and, beside it on the same rows, the account status and alert. Their tops line up with Member type. */}
      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 2, flexWrap: 'wrap', maxWidth: '100%' }}>
      <Box role="group" aria-label="Client classification" sx={{ display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', gap: '2px 10px', alignItems: 'center', width: 280, maxWidth: '100%' }}>
        <SelectField inline label="Member type" lookup="clientType" code={m.clientType} lk={lk} col="ClientType" />
        <SelectField inline label="Department" lookup="department" code={m.department} lk={lk} col="ClientDEPT" />
        <InlineField label="Date opened" value={m.dateOpened ? `${fmtDate(m.dateOpened)}${yearsSince(m.dateOpened) != null ? ` · ${yearsSince(m.dateOpened)} yrs` : ''}` : null} />
      </Box>
      <Box role="group" aria-label="Account status" sx={{ display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', gap: '2px 10px', alignItems: 'center', gridAutoRows: 32, minWidth: 170 }}>
        <Typography sx={{ fontSize: 11.5, fontWeight: 500 }} color="text.secondary">Status</Typography>
        <Box><Chip size="small" variant="outlined" color={st.kind === 'default' ? undefined : st.kind} label={st.label} icon={st.kind === 'default' ? <FiSlash /> : <FiCheckCircle />} sx={quietChip} /></Box>
        {alertLabel && (
          <>
            <Typography sx={{ fontSize: 11.5, fontWeight: 500 }} color="text.secondary">Alert</Typography>
            <Box>
              <Chip size="small" variant="outlined" color={alertOk ? 'success' : (m.alertLevel ?? 0) >= 6 ? 'error' : 'warning'} label={alertLabel} icon={alertOk ? <FiCheckCircle /> : <FiAlertTriangle />} sx={quietChip} />
            </Box>
          </>
        )}
      </Box>
      </Box>
    </Box>
  )
}

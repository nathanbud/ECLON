import { Alert, Button, type AlertColor } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { useContext, useState, type ReactNode } from 'react'
import { getSchemaStatus } from './api'
import { can } from './nav/screens'
import { ApplyDialog } from './SchemaStatusNotice'
import { SessionCtx } from './sessionContext'

/**
 * For a screen whose feature the database is not set up for yet (a database change not applied): says so, and lets a super administrator apply the
 * missing changes right there (the same Apply dialog as the notice on Settings, asking for a database administrator's login once). Anyone else is told who
 * can and where. Replaces the old "an administrator has to run migration Vxxx" sentences, which left the screen read-only with nothing to do.
 */
export function DatabaseChangeNotice({ children, severity = 'info' }: { children: ReactNode; severity?: AlertColor }) {
  const me = useContext(SessionCtx)
  const superadmin = !!me && can(me, 'superadmin')
  const { data } = useQuery({ queryKey: ['schema-status'], queryFn: getSchemaStatus, staleTime: 60_000, retry: false, enabled: superadmin })
  const [applying, setApplying] = useState(false)
  const behind = superadmin && data?.state === 'BEHIND'
  return (
    <>
      <Alert severity={severity} sx={{ mb: 2 }}
        action={behind ? <Button color="inherit" size="small" onClick={() => setApplying(true)} sx={{ whiteSpace: 'nowrap' }}>Apply them now</Button> : undefined}>
        {children}{' '}
        {behind
          ? 'Apply the missing database changes now to set it up.'
          : superadmin && data?.state === 'NOT_CHECKED'
            ? 'This is the old MySQL database: its changes are applied with scripts/migrate.ps1 on the server.'
            : superadmin && data
              ? 'Settings > System > Database connection shows what this database lacks.'
              : 'A super administrator can set it up from Settings > System > Database connection (Apply them now).'}
      </Alert>
      {applying && data && <ApplyDialog status={data} onClose={() => setApplying(false)} />}
    </>
  )
}

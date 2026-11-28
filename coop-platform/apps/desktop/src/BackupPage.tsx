import { Alert, Box, Button, Chip, CircularProgress, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { FiDownloadCloud } from 'react-icons/fi'
import { getBackups, runBackup } from './api'
import { appBackground, glass } from './glass'
import { sizeOf, tabular } from './ui/format'
import { useMe } from './session'
import { notifyError } from './ui/notices'
import { AboutNote, ScreenTitle } from './appleUi'

const when = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/**
 * Settings > Backup (administrators): the backups on the server and a button to take a new one. A backup holds every table the application owns (members' requests, the audit
 * log, journals, procurement, inventory and so on) and the fingerprint key. The old system's own tables are not in it: they are backed up as the database. The dump and the key
 * only work together, so a backup missing either one is marked. Restoring is not offered here because it replaces live data; it is done by hand from the dump.
 */
export default function BackupPage() {
  const me = useMe()
  const qc = useQueryClient()
  const admin = me.roles.some((r) => ['ROLE_ADMIN', 'ADMIN'].includes(r))
  const status = useQuery({ queryKey: ['backups'], queryFn: getBackups, enabled: admin })
  const [notice, setNotice] = useState<string | null>(null)

  const run = useMutation({
    mutationFn: runBackup,
    onMutate: () => { setNotice(null) },
    onSuccess: async (r) => { setNotice(r.backup ? `Backup ${r.backup.name} is ready (${sizeOf(r.backup.bytes)}).` : 'The backup finished.'); await qc.invalidateQueries({ queryKey: ['backups'] }) },
    onError: async (e) => { notifyError((e as Error).message); await qc.invalidateQueries({ queryKey: ['backups'] }) },
  })

  const data = status.data
  const busy = run.isPending || !!data?.running
  const latest = data?.backups[0]

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 1000, mx: 'auto' }}>
        <ScreenTitle title="Backup" sx={{ mb: 2 }}>
          Saves everything this application owns, and the fingerprint key, into a dated folder on the server. The old system’s own tables are not included; they are backed up as the database.
        </ScreenTitle>
        {!admin && <Alert severity="info" sx={{ mb: 2 }}>Only an administrator can take or see backups.</Alert>}
        {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
        {status.isError && <Alert severity="error" sx={{ mb: 2 }}>The backups could not be read.</Alert>}
        {data && !data.scriptFound && <Alert severity="warning" sx={{ mb: 2 }}>The backup script was not found on the server, so a backup cannot be taken from here.</Alert>}

        <Box sx={(t) => ({ p: 2.5, mb: 2, ...glass(t) })}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: { sm: 'center' }, justifyContent: 'space-between' }}>
            <Box>
              <Typography variant="h6" component="p">
                {latest ? `Last backup: ${when(latest.takenAt)}` : 'No backup has been taken yet'}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {data ? `From the ${data.engine === 'postgres' ? 'PostgreSQL' : 'MySQL'} database. Kept in ${data.folder}` : 'Reading…'}
              </Typography>
            </Box>
            <Button variant="contained" startIcon={busy ? <CircularProgress size={16} color="inherit" /> : <FiDownloadCloud />} disabled={!admin || !data?.scriptFound || busy} onClick={() => run.mutate()}>
              {busy ? 'Backing up…' : 'Back up now'}
            </Button>
          </Stack>
          <AboutNote sx={{ mt: 1.5 }}>
            Keep the dump and the key together and copy them to another computer: without the key, stored fingerprints cannot be read, and a backup that stays on this machine is lost with it.
          </AboutNote>
        </Box>

        <Box sx={(t) => ({ p: 0, overflowX: 'auto', ...glass(t) })}>
          <Table size="small" aria-label="Backups">
            <TableHead>
              <TableRow>
                <TableCell>Taken</TableCell>
                <TableCell>Folder</TableCell>
                <TableCell>Contains</TableCell>
                <TableCell align="right">Size</TableCell>
                <TableCell>State</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {(data?.backups ?? []).map((b) => (
                <TableRow key={b.name}>
                  <TableCell>{when(b.takenAt)}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{b.name}</TableCell>
                  <TableCell>{b.files.map((f) => f.name).join(', ')}</TableCell>
                  <TableCell align="right" sx={tabular}>{sizeOf(b.bytes)}</TableCell>
                  <TableCell>{b.complete ? <Chip size="small" color="success" label="Complete" /> : <Chip size="small" color="warning" label="Incomplete" />}</TableCell>
                </TableRow>
              ))}
              {data && data.backups.length === 0 && (
                <TableRow><TableCell colSpan={5}><Typography sx={{ py: 1 }} color="text.secondary">Nothing here yet. Press Back up now.</Typography></TableCell></TableRow>
              )}
            </TableBody>
          </Table>
        </Box>
      </Box>
    </Box>
  )
}

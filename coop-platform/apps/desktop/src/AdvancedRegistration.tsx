import { Alert, Box, Button, Checkbox, Chip, FormControlLabel, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { FiDownload, FiUpload } from 'react-icons/fi'
import { downloadMemberImportTemplate, previewMemberImport, uploadMemberImport, type ImportPreview } from './api'
import { glass } from './glass'
import { notifyError, notify } from './ui/notices'
import { useLeaveGuard } from './leaveGuard'

const STEP = 300

/**
 * Advanced mode: register many members from a CSV or Excel list. The list is checked first, row by row, and nothing is saved until it is sent. Sent rows become one batch that a
 * Membership Officer other than you approves (Approvals), which opens the members. Administrators only.
 */
export default function AdvancedRegistration({ onClose, onDone }: { onClose: () => void; onDone: (msg: string) => void }) {
  const qc = useQueryClient()
  const input = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<ImportPreview | null>(null)
  const [onlyProblems, setOnlyProblems] = useState(false)
  const [shown, setShown] = useState(STEP)
  const [skip, setSkip] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  useLeaveGuard(preview && preview.ok > 0 ? 'A checked list of members has not been sent yet.' : null)

  const choose = async (f: File | undefined) => {
    if (!f) return
    setFile(f); setPreview(null); setSkip(false); setShown(STEP); setBusy('Checking the list…')
    try { setPreview(await previewMemberImport(f)) } catch (e) { notifyError((e as Error).message) } finally { setBusy(null) }
  }

  const download = async (kind: 'xlsx' | 'csv') => {
    try {
      await downloadMemberImportTemplate(kind)
      notify(`members-template.${kind} has finished downloading. Look for it in your Downloads folder.`)
    } catch (e) {
      notifyError(`The blank list could not be downloaded: ${(e as Error).message}`)
    }
  }

  const send = async () => {
    if (!file) return
    setBusy('Sending…')
    try {
      const b = await uploadMemberImport(file, skip)
      void qc.invalidateQueries({ queryKey: ['member-imports'] })
      onDone(`${b.okRows} member${b.okRows === 1 ? '' : 's'} from ${b.fileName} sent for approval`)
      onClose()
    } catch (e) { notifyError((e as Error).message) } finally { setBusy(null) }
  }

  const rows = (preview?.rows ?? []).filter((r) => !onlyProblems || !r.ok)
  const canSend = !!preview && preview.ok > 0 && (preview.errors === 0 || skip) && !busy
  return (
    <Box sx={{ maxWidth: 1100, width: '100%', mx: 'auto' }}>
      <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, py: 1.25, mb: 1.5, ...glass(t) })}>
        <Chip size="small" color="warning" label="Advanced mode" />
        <Typography variant="h6" component="p" sx={{ flex: 1 }}>Register members from a list</Typography>
        <Button size="small" onClick={onClose} disabled={!!busy}>Back</Button>
      </Box>

      <Box sx={(t) => ({ p: 2, mb: 1.5, ...glass(t) })}>
        <Typography sx={{ mb: 1 }}>
          Upload a CSV or Excel (.xlsx) file with one member per row and the column names in the first row. Required for each member: <strong>LName, FName, DateOfBirth, Gender, CivilStatus</strong>.
          Gender and CivilStatus may be written as their names (for example Female, Single) or codes; MemberType is Regular or Associate (Regular if left out); dates are written 2026-04-24 or are Excel dates.
          Any other column named like a profile field (CellNum, EmailAddress, ResAddCity, Profession and so on) is filled in too; columns it does not know are ignored.
          Photos and fingerprints are not part of a list: add them on each member afterwards. Up to 5,000 rows and 5 MB.
        </Typography>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}>
          <Button size="small" startIcon={<FiDownload />} onClick={() => void download('xlsx')}>Download a blank Excel list</Button>
          <Button size="small" color="inherit" onClick={() => void download('csv')}>or CSV</Button>
          <Button size="small" variant="contained" startIcon={<FiUpload />} disabled={!!busy} onClick={() => input.current?.click()}>Choose a file</Button>
          <input ref={input} type="file" hidden accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" onChange={(e) => { void choose(e.target.files?.[0]); e.target.value = '' }} />
          {file && <Typography color="text.secondary">{file.name}</Typography>}
        </Stack>
      </Box>

      {busy && <Typography sx={{ mb: 1.5 }} color="text.secondary">{busy}</Typography>}

      {preview && (
        <Box sx={(t) => ({ p: 2, ...glass(t) })}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap', rowGap: 1 }}>
            <Chip size="small" label={`${preview.total} rows`} />
            <Chip size="small" color="success" label={`${preview.ok} ready`} />
            <Chip size="small" color={preview.errors ? 'error' : 'default'} label={`${preview.errors} with problems`} />
            <Box sx={{ flex: 1 }} />
            <FormControlLabel control={<Checkbox size="small" checked={onlyProblems} onChange={(_, v) => setOnlyProblems(v)} />} label="Show only problems" />
          </Stack>
          {preview.ignoredColumns.length > 0 && <Alert severity="info" sx={{ mb: 1, py: 0 }}>Columns not used: {preview.ignoredColumns.join(', ')}.</Alert>}
          <Box sx={{ maxHeight: 360, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1 }}>
            <Table size="small" stickyHeader aria-label="Checked rows">
              <TableHead><TableRow><TableCell sx={{ width: 70 }}>Line</TableCell><TableCell>Member</TableCell><TableCell>Result</TableCell></TableRow></TableHead>
              <TableBody>
                {rows.slice(0, shown).map((r) => (
                  <TableRow key={r.line}>
                    <TableCell>{r.line}</TableCell><TableCell>{r.name}</TableCell>
                    <TableCell sx={{ color: r.ok ? 'success.main' : 'error.main' }}>{r.ok ? 'Ready' : r.problems.join(' ')}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
          {rows.length > shown && (
            <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mt: 0.5 }}>
              <Typography variant="body2" color="text.secondary">Showing {shown} of {rows.length} rows.</Typography>
              <Button size="small" onClick={() => setShown((n) => n + STEP)}>Show {Math.min(STEP, rows.length - shown)} more</Button>
              <Button size="small" color="inherit" onClick={() => setShown(rows.length)}>Show all</Button>
            </Stack>
          )}
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mt: 1.5, flexWrap: 'wrap' }}>
            {preview.errors > 0 && preview.ok > 0 && (
              <FormControlLabel control={<Checkbox size="small" checked={skip} onChange={(_, v) => setSkip(v)} />} label={`Leave out the ${preview.errors} row${preview.errors === 1 ? '' : 's'} with problems`} />
            )}
            <Box sx={{ flex: 1 }} />
            <Button variant="contained" disabled={!canSend} onClick={() => void send()}>
              {preview.ok > 0 ? `Send ${skip || preview.errors === 0 ? preview.ok : 'the'} ${preview.ok === 1 && (skip || preview.errors === 0) ? 'member' : 'members'} for approval` : 'Nothing to send'}
            </Button>
          </Stack>
          <Typography variant="body2" sx={{ mt: 1 }} color="text.secondary">Nothing is saved until you send. A Membership Officer other than you then approves the whole list, which opens the members.</Typography>
        </Box>
      )}
    </Box>
  )
}

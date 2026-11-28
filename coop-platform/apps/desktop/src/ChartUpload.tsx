import { Alert, Box, Button, Checkbox, Chip, FormControlLabel, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useRef, useState } from 'react'
import { FiDownload, FiUpload } from 'react-icons/fi'
import { downloadChartImportTemplate, previewChartImport, uploadChartImport, type ChartImportPreview } from './api'
import { glass } from './glass'
import { notifyError, notify } from './ui/notices'
import { useLeaveGuard } from './leaveGuard'

const STEP = 300
const COLOUR = { NEW: 'success.main', EXISTS: 'text.secondary', PROBLEM: 'error.main' } as const

/**
 * Advanced mode of the chart of accounts: add many accounts from a CSV or Excel list. The list is checked first, row by row, and nothing is saved until it is added with a reason.
 * A list only adds accounts: one whose number is already in the chart is left as it is. Administrators only.
 */
export default function ChartUpload({ onClose, onDone }: { onClose: () => void; onDone: (msg: string) => void }) {
  const input = useRef<HTMLInputElement>(null)
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<ChartImportPreview | null>(null)
  const [onlyProblems, setOnlyProblems] = useState(false)
  const [shown, setShown] = useState(STEP)
  const [skip, setSkip] = useState(false)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  useLeaveGuard(preview && preview.ready > 0 ? 'A checked list of accounts has not been added yet.' : null)

  const choose = async (f: File | undefined) => {
    if (!f) return
    setFile(f); setPreview(null); setSkip(false); setOnlyProblems(false); setShown(STEP); setBusy('Checking the list…')
    try { setPreview(await previewChartImport(f)) } catch (e) { notifyError((e as Error).message) } finally { setBusy(null) }
  }

  const download = async (kind: 'xlsx' | 'csv') => {
    try {
      await downloadChartImportTemplate(kind)
      notify(`chart-of-accounts-template.${kind} has finished downloading. Look for it in your Downloads folder.`)
    } catch (e) {
      notifyError(`The blank list could not be downloaded: ${(e as Error).message}`)
    }
  }

  const add = async () => {
    if (!file) return
    setBusy('Adding the accounts…')
    try {
      const d = await uploadChartImport(file, reason.trim(), skip)
      onDone(`${d.added.toLocaleString()} account${d.added === 1 ? '' : 's'} added from ${d.fileName}`)
    } catch (e) { notifyError((e as Error).message) } finally { setBusy(null) }
  }

  const rows = (preview?.rows ?? []).filter((r) => !onlyProblems || r.state === 'PROBLEM')
  const canAdd = !!preview && preview.ready > 0 && (preview.errors === 0 || skip) && reason.trim() !== '' && !busy
  return (
    <Box sx={{ maxWidth: 1100, width: '100%', mx: 'auto' }}>
      <Box sx={(t) => ({ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, py: 1.25, mb: 1.5, ...glass(t) })}>
        <Chip size="small" color="warning" label="Advanced mode" />
        <Typography variant="h6" component="h1" sx={{ flex: 1 }}>Add accounts from a list</Typography>
        <Button size="small" onClick={onClose} disabled={!!busy}>Back</Button>
      </Box>

      <Box sx={(t) => ({ p: 2, mb: 1.5, ...glass(t) })}>
        <Typography sx={{ mb: 1 }}>
          Upload a CSV or Excel (.xlsx) file with one account per row and the column names in the first row. Required for each account: <strong>Account number</strong> and <strong>Account name</strong>.
          {' '}<strong>Kind</strong> is Heading or Account (Account if left out); <strong>Under</strong> is the number of the heading it goes under (left out, it goes under the longest heading its number starts with);
          {' '}<strong>Journal entries</strong> is Yes or No (No for an account that a loan, savings or share capital ledger controls).
          A list only adds accounts: a number that is already in the chart is left as it is, and nothing is changed or deleted. Up to 5,000 rows and 5 MB.
        </Typography>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}>
          <Button size="small" startIcon={<FiDownload />} onClick={() => void download('xlsx')}>Download a blank Excel list</Button>
          <Button size="small" color="inherit" onClick={() => void download('csv')}>or CSV</Button>
          <Button size="small" variant="contained" startIcon={<FiUpload />} disabled={!!busy} onClick={() => input.current?.click()}>Choose a file</Button>
          <input ref={input} type="file" hidden aria-label="List of accounts" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" onChange={(e) => { void choose(e.target.files?.[0]); e.target.value = '' }} />
          {file && <Typography color="text.secondary">{file.name}</Typography>}
        </Stack>
      </Box>

      {busy && <Typography role="status" sx={{ mb: 1.5 }} color="text.secondary">{busy}</Typography>}

      {preview && (
        <Box sx={(t) => ({ p: 2, ...glass(t) })}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap', rowGap: 1 }}>
            <Chip size="small" label={`${preview.total} rows`} />
            <Chip size="small" color="success" label={`${preview.ready} new`} />
            <Chip size="small" label={`${preview.existing} already in the chart`} />
            <Chip size="small" color={preview.errors ? 'error' : 'default'} label={`${preview.errors} with problems`} />
            <Box sx={{ flex: 1 }} />
            <FormControlLabel control={<Checkbox size="small" checked={onlyProblems} onChange={(_, v) => setOnlyProblems(v)} />} label="Show only problems" />
          </Stack>
          {preview.ignoredColumns.length > 0 && <Alert severity="info" sx={{ mb: 1, py: 0 }}>Columns not used: {preview.ignoredColumns.join(', ')}.</Alert>}
          <Box sx={{ maxHeight: 360, overflow: 'auto', border: 1, borderColor: 'divider', borderRadius: 1 }}>
            <Table size="small" stickyHeader aria-label="Checked rows">
              <TableHead><TableRow><TableCell sx={{ width: 70 }}>Line</TableCell><TableCell sx={{ width: 130 }}>Number</TableCell><TableCell>Name</TableCell><TableCell>Result</TableCell></TableRow></TableHead>
              <TableBody>
                {rows.slice(0, shown).map((r) => (
                  <TableRow key={r.line}>
                    <TableCell>{r.line}</TableCell><TableCell sx={{ fontVariantNumeric: 'tabular-nums' }}>{r.code}</TableCell><TableCell>{r.name}</TableCell>
                    <TableCell sx={{ color: COLOUR[r.state] }}>{r.note}</TableCell>
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
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mt: 1.5, flexWrap: 'wrap', rowGap: 1 }}>
            <TextField required label="Reason for adding these accounts" value={reason} onChange={(e) => setReason(e.target.value)} disabled={preview.ready === 0}
              slotProps={{ htmlInput: { maxLength: 200 } }} sx={{ flex: 1, minWidth: 260 }} />
            {preview.errors > 0 && preview.ready > 0 && (
              <FormControlLabel control={<Checkbox size="small" checked={skip} onChange={(_, v) => setSkip(v)} />} label={`Leave out the ${preview.errors} row${preview.errors === 1 ? '' : 's'} with problems`} />
            )}
            <Button variant="contained" disabled={!canAdd} onClick={() => void add()}>
              {preview.ready > 0 ? `Add ${preview.ready.toLocaleString()} account${preview.ready === 1 ? '' : 's'} to the chart` : 'Nothing to add'}
            </Button>
          </Stack>
          <Typography variant="body2" sx={{ mt: 1 }} color="text.secondary">
            Nothing is saved until you add. The accounts are added together or not at all, and each one is written to the audit log with your reason. An account cannot be deleted afterwards, only switched off.
          </Typography>
        </Box>
      )}
    </Box>
  )
}

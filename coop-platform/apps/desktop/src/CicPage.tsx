import {
  Alert, Box, Button, MenuItem, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material'
import { useState } from 'react'
import { appBackground, glass } from './glass'

interface HeaderRow { recordType: string; providerCode: string; fileReferenceDate: string; version: string; submissionType: string; providerComments: string }
type Kind = 'members' | 'loans'

const SUBMISSION_TYPES = ['Standard', 'Correction', 'Full resubmission']
const today = () => new Date().toISOString().slice(0, 10)
/** The file reference date is written DDMMYYYY, as in the CIC file layout. */
const fileDate = (iso: string) => `${iso.slice(8, 10)}${iso.slice(5, 7)}${iso.slice(0, 4)}`
const stamp = () => new Date().toLocaleTimeString('en-GB')

/**
 * Credit Information Corporation submission screen: the header record, the data generation log for members and loans,
 * and the file steps (text and zip, encrypt, Excel, transmittal letter, submit, outstanding balance computation).
 * Front end only for now: nothing is read from or sent to the server, so the file steps only say so in the log.
 */
export default function CicPage() {
  const [submissionType, setSubmissionType] = useState(SUBMISSION_TYPES[0])
  const [providerCode, setProviderCode] = useState('')
  const [version, setVersion] = useState('1.0')
  const [remarks, setRemarks] = useState('')
  const cutOff = today()
  const [header, setHeader] = useState<HeaderRow | null>(null)
  const [tab, setTab] = useState<Kind>('members')
  const [logs, setLogs] = useState<Record<Kind, string[]>>({ members: [], loans: [] })

  const log = (kind: Kind, line: string) => setLogs((p) => ({ ...p, [kind]: [...p[kind], line] }))
  const generateHeader = () => setHeader({ recordType: 'HD', providerCode: providerCode.trim(), fileReferenceDate: fileDate(cutOff), version, submissionType: String(SUBMISSION_TYPES.indexOf(submissionType)), providerComments: remarks.trim() })
  const step = (what: string) => log(tab, `[${stamp()}] ${what}: not connected to the server yet.`)
  const needHeader = !header || !header.providerCode

  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: { xs: 2, md: 3 }, background: appBackground(t) })}>
      <Box sx={{ maxWidth: 980, mx: 'auto' }}>
        <Typography component="h1" variant="h5" sx={{ fontWeight: 700, mb: 0.5 }}>CIC submission</Typography>
        <Alert severity="info" sx={{ mb: 1.5 }}>Preview of the screen. Generating, encrypting and sending files is not connected to the server yet.</Alert>

        <Box component="section" aria-labelledby="cic-header" sx={(t) => ({ p: 2, mb: 2, ...glass(t) })}>
          <Typography id="cic-header" sx={{ fontWeight: 600, mb: 1.5 }}>Header information</Typography>
          <Box sx={{ display: 'grid', gap: 1.5, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr 1fr' }, alignItems: 'start' }}>
            <TextField select label="Submission type" value={submissionType} onChange={(e) => setSubmissionType(e.target.value)}>
              {SUBMISSION_TYPES.map((s) => <MenuItem key={s} value={s}>{s}</MenuItem>)}
            </TextField>
            <TextField label="Cut-off date" value={cutOff} slotProps={{ input: { readOnly: true }, inputLabel: { shrink: true } }} type="date" />
            <TextField label="Provider code" value={providerCode} onChange={(e) => setProviderCode(e.target.value)} helperText="Assigned to the cooperative by the CIC" />
            <TextField label="Version" value={version} onChange={(e) => setVersion(e.target.value)} />
            <TextField label="Remarks" value={remarks} onChange={(e) => setRemarks(e.target.value)} multiline minRows={2} sx={{ gridColumn: { md: 'span 2' } }} />
          </Box>
          <Stack direction="row" sx={{ justifyContent: 'flex-end', mt: 1.5 }}>
            <Button variant="contained" onClick={generateHeader} disabled={!providerCode.trim() || !version.trim()}>Generate Header Info</Button>
          </Stack>
          <Box sx={{ overflowX: 'auto', mt: 1.5, border: 1, borderColor: 'divider', borderRadius: '5px' }}>
            <Table size="small" aria-label="Header record">
              <TableHead>
                <TableRow>{['Record type', 'Provider code', 'File reference date', 'Version', 'Submission type', 'Provider comments'].map((c) => <TableCell key={c}>{c}</TableCell>)}</TableRow>
              </TableHead>
              <TableBody>
                {header ? (
                  <TableRow selected>
                    <TableCell>{header.recordType}</TableCell><TableCell>{header.providerCode}</TableCell><TableCell>{header.fileReferenceDate}</TableCell>
                    <TableCell>{header.version}</TableCell><TableCell>{header.submissionType}</TableCell><TableCell>{header.providerComments || '—'}</TableCell>
                  </TableRow>
                ) : (
                  <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 1, textAlign: 'center', fontSize: 13 }}>No header yet. Fill in the form and choose Generate Header Info.</Typography></TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </Box>
        </Box>

        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'minmax(0, 1fr) 190px' }, alignItems: 'start' }}>
          <Box component="section" aria-labelledby="cic-data" sx={(t) => ({ p: 2, ...glass(t) })}>
            <Typography id="cic-data" sx={{ fontWeight: 600, mb: 1 }}>Data generation</Typography>
            <Tabs value={tab} onChange={(_, v: Kind) => setTab(v)} sx={{ mb: 1.5 }}>
              <Tab value="members" label="Members" />
              <Tab value="loans" label="Loans" />
            </Tabs>
            <Typography sx={{ fontWeight: 600, fontSize: 13, mb: 0.5 }} id="cic-log-label">Status log</Typography>
            <Box
              role="log" aria-labelledby="cic-log-label" tabIndex={0}
              sx={{ bgcolor: '#000', color: '#fff', fontFamily: 'Consolas, "Courier New", monospace', fontSize: 12.5, p: 1, height: 240, overflow: 'auto', borderRadius: '3px', whiteSpace: 'pre-wrap' }}
            >
              {logs[tab].length ? logs[tab].map((l, i) => <div key={i}>{l}</div>) : <Box component="span" sx={{ opacity: 0.6 }}>Nothing generated yet.</Box>}
            </Box>
            <Stack direction="row" sx={{ justifyContent: 'flex-end', mt: 1.5 }}>
              <Button variant="outlined" disabled={needHeader} onClick={() => step(tab === 'members' ? 'Generate members’ data' : 'Generate loans’ data')}>
                {tab === 'members' ? 'Generate Members’ Data' : 'Generate Loans’ Data'}
              </Button>
            </Stack>
            {needHeader && <Typography color="text.secondary" sx={{ fontSize: 12, mt: 0.5, textAlign: 'right' }}>Generate the header first.</Typography>}
          </Box>

          <Stack spacing={1} aria-label="File steps" role="group">
            <Button variant="outlined" disabled={needHeader} onClick={() => step('Generate .txt and .zip files')}>Generate .txt and .zip files</Button>
            <Button variant="outlined" disabled={needHeader} onClick={() => step('Encrypt .txt and .zip')}>Encrypt .txt and .zip</Button>
            <Button variant="outlined" disabled={needHeader} onClick={() => step('Convert to Excel')}>Convert to Excel</Button>
            <Button variant="outlined" disabled>Generate Transmittal Letter</Button>
            <Button variant="outlined" disabled={needHeader} onClick={() => step('Submit data to CIC')}>Submit Data to CIC</Button>
            <Button variant="outlined" disabled={needHeader} onClick={() => step('Generate outstanding balance computation')}>Generate Outstanding Balance Computation</Button>
          </Stack>
        </Box>
      </Box>
    </Box>
  )
}

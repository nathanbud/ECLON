import { Box } from '@mui/material'
import DocumentsPanel from './DocumentsPanel'
import { appBackground, glass } from './glass'
import { ScreenTitle } from './appleUi'

/** Every document the signed-in person may read, searchable; documents attach to a member, asset or other record from that record's own window or from here. */
export default function DocumentsPage() {
  return (
    <Box component="main" sx={(t) => ({ minHeight: 'calc(100dvh - 44px)', p: 2, background: appBackground(t) })}>
      <Box sx={(t) => ({ maxWidth: 1250, mx: 'auto', p: 2, ...glass(t) })}>
        <ScreenTitle title="Documents" sx={{ mb: 2 }}>Only documents you are allowed to read are listed. Who may read a document depends on how it was classified; every download is recorded.</ScreenTitle>
        <DocumentsPanel />
      </Box>
    </Box>
  )
}

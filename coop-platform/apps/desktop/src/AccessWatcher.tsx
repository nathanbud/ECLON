import { Alert, AlertTitle, Box, Snackbar, Typography } from '@mui/material'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { getMyAccess, restoreSession, type Me, type MyAccess } from './api'
import { accessChanges, anyChange, type AccessChange, type AccessChanges, type AccessItem } from './accessChanges'

/** How often the window asks whether the person's access changed. The answer is a short fingerprint unless something did. */
export const ACCESS_CHECK_MS = 15_000
/** At most this many entries per list in the notice; the rest are counted. */
const SHOWN = 6

interface Notice { changes: AccessChanges; by: string | null; at: string | null }

const time = (at: string | null) => {
  if (!at) return null
  const d = new Date(at)
  return Number.isNaN(d.getTime()) ? null : d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
}

function List({ title, items }: { title: string; items: AccessChange[] }) {
  if (items.length === 0) return null
  return (
    <Box sx={{ mt: 0.75 }}>
      <Typography sx={{ fontSize: 13, fontWeight: 600 }}>{title}</Typography>
      <Box component="ul" sx={{ m: 0, pl: 2.25 }}>
        {items.slice(0, SHOWN).map((c) => (
          <Typography component="li" key={c.text + c.where} sx={{ fontSize: 13 }}>
            {c.text}{c.where && !c.text.startsWith(c.where) && <Typography component="span" sx={{ fontSize: 12, opacity: 0.75 }}> · {c.where}</Typography>}
          </Typography>
        ))}
        {items.length > SHOWN && <Typography component="li" sx={{ fontSize: 13 }}>and {items.length - SHOWN} more</Typography>}
      </Box>
    </Box>
  )
}

/**
 * Watches the signed-in person's access while they work. When an administrator changes it, the window says what changed and who changed it,
 * refreshes the person's menu, tiles and buttons, and reloads what is on screen, so nobody needs to sign out and in again. The server already
 * applies a change on the next request; this keeps the screen in step. A switched-off account or a reset password ends the sign-in instead
 * (the sign-in screen then says why).
 */
export default function AccessWatcher({ onMe }: { onMe: (me: Me) => void }) {
  const qc = useQueryClient()
  const known = useRef<{ fingerprint: string; items: AccessItem[] } | null>(null)
  const busy = useRef(false)
  const warned = useRef(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  useEffect(() => {
    let live = true
    const check = async () => {
      if (busy.current || document.visibilityState === 'hidden') return
      busy.current = true
      try {
        const r: MyAccess = await getMyAccess(known.current?.fingerprint)
        if (!live || !r.items) return
        const before = known.current
        known.current = { fingerprint: r.fingerprint, items: r.items }
        if (!before) return
        const changes = accessChanges(before.items, r.items)
        const me = await restoreSession()
        if (me && live) onMe(me)
        void qc.invalidateQueries()
        if (anyChange(changes) && live) setNotice({ changes, by: r.lastChange?.by ?? null, at: r.lastChange?.at ?? null })
      } catch (e) {
        // Offline or signed out: the next check, or the sign-out itself, deals with it. Said once in the console, so a server that is older than
        // this app (it has no /api/me/access, so it answers "not found") does not fail without a trace.
        if (!warned.current) {
          warned.current = true
          console.warn(`[access] Could not check for changes to your access: ${(e as Error).message} If this says the record was not found, the server is older than this app; restart it (scripts/run-backend.ps1).`)
        }
      } finally {
        busy.current = false
      }
    }
    void check()
    const timer = window.setInterval(() => void check(), ACCESS_CHECK_MS)
    const again = () => void check()
    window.addEventListener('focus', again)
    document.addEventListener('visibilitychange', again)
    return () => { live = false; window.clearInterval(timer); window.removeEventListener('focus', again); document.removeEventListener('visibilitychange', again) }
  }, [qc, onMe])

  if (!notice) return null
  const when = time(notice.at)
  return (
    <Snackbar open anchorOrigin={{ vertical: 'top', horizontal: 'right' }} sx={{ mt: 6, maxWidth: 420 }}>
      <Alert severity="info" variant="filled" onClose={() => setNotice(null)} role="alert" sx={{ width: '100%', alignItems: 'flex-start' }}>
        <AlertTitle sx={{ fontWeight: 700 }}>Your access was changed</AlertTitle>
        <Typography sx={{ fontSize: 13 }}>
          {notice.by ? `${notice.by} (an administrator) changed what you may do` : 'An administrator changed what you may do'}{when ? ` at ${when}` : ''}.
        </Typography>
        <List title="You can now" items={notice.changes.allowed} />
        <List title="You can no longer" items={notice.changes.removed} />
        <List title="Changed" items={notice.changes.changed} />
        <Typography sx={{ fontSize: 12, mt: 1, opacity: 0.85 }}>Your menu and buttons are already updated. There is no need to sign out.</Typography>
      </Alert>
    </Snackbar>
  )
}

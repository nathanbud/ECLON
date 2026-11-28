import { Alert, Box, IconButton, Portal, Snackbar, Typography, alpha, useTheme } from '@mui/material'
import { FiAlertCircle, FiX } from 'react-icons/fi'
import { glass } from '../glass'
import { MutationCache } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

/**
 * Short "done" messages after an action works ("Journal posted to the ledger."), shown for a few seconds at the bottom of the window.
 * A screen does not show them itself: it puts the words on its mutation as {@code meta: { done: '...' }} (or a function of the result and the
 * variables), and {@link noticeMutationCache} shows them.
 * A refusal of an action ("Only a super administrator can …") pops up here too, in red, with {@code notify(message, 'error')}, instead of a box in
 * the page or dialog that pushes the content down when it appears; it shows as a glass card in the middle of the window, stays 12 s and can be closed. Problems with what is typed in a field
 * stay under that field, and a list that failed to load says so in its own place (LoadingRow).
 */
const EVENT = 'coop:notice'

declare module '@tanstack/react-query' {
  interface Register {
    mutationMeta: {
      /** What to tell the person when the action worked; null or an empty string says nothing. */
      done?: string | ((data: unknown, variables: unknown) => string | null)
    }
  }
}

export type NoticeKind = 'success' | 'error' | 'info'
type Notice = { text: string; kind: NoticeKind }

let last = { text: '', kind: '' as string, at: 0 }

export function notify(text: string, kind: NoticeKind = 'success') {
  // The same message said twice at once (a mutation's onError and the dialog that shows its error) shows once.
  const now = Date.now()
  if (text === last.text && kind === last.kind && now - last.at < 1000) return
  last = { text, kind, at: now }
  window.dispatchEvent(new CustomEvent<Notice>(EVENT, { detail: { text, kind } }))
}

/** A refused or failed action, popped up instead of shown inside the screen. */
export const notifyError = (e: unknown) => {
  const text = e instanceof Error ? e.message : e == null ? '' : String(e)
  if (text.trim()) notify(text, 'error') // nothing to say shows nothing (never a card saying null)
}

/** Put on the QueryClient so every mutation with {@code meta.done} says it worked. */
export const noticeMutationCache = () => new MutationCache({
  onSuccess: (data, variables, _context, mutation) => {
    const done = mutation.options.meta?.done
    const text = typeof done === 'function' ? done(data, variables) : done
    if (text) notify(text)
  },
})

/** Mounted once per window: "done" notices at the bottom, refusals as a card in the middle of the window ({@link RefusalCard}). */
export function Notices() {
  const [notice, setNotice] = useState<Notice | null>(null)
  const [seq, setSeq] = useState(0)
  useEffect(() => {
    const on = (e: Event) => { setNotice((e as CustomEvent<Notice>).detail); setSeq((n) => n + 1) }
    window.addEventListener(EVENT, on)
    return () => window.removeEventListener(EVENT, on)
  }, [])
  // Each notice is put in a portal of its own, made when it appears, at the end of the page: an open dialog hides everything that was on the page
  // when it opened from screen readers (aria-hidden), so a notice rendered in place was silent over a dialog. One made after the dialog is not hidden.
  if (!notice) return null
  if (notice.kind === 'error') return <Portal key={seq}><RefusalCard text={notice.text} onClose={() => setNotice(null)} /></Portal>
  return (
    <Portal key={seq}>
      <Snackbar open autoHideDuration={5000} onClose={(_, reason) => { if (reason === 'timeout') setNotice(null) }}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
        <Alert severity={notice.kind} variant="filled" role="status" onClose={() => setNotice(null)}
          sx={{ alignItems: 'center', maxWidth: 640 }}>{notice.text}</Alert>
      </Snackbar>
    </Portal>
  )
}

const REFUSAL_MS = 12000

/**
 * A refusal in the middle of the window, over everything (dialogs too), without covering or dimming the screen: a frosted glass card (the app's
 * {@code glass()} surface, blurred more) that tilts up into place with depth (perspective, layered shadows, a light top edge) and a red mark.
 * It closes by itself after 12 s (not while the pointer is on it), or with its close button; nothing behind it is blocked.
 */
function RefusalCard({ text, onClose }: { text: string; onClose: () => void }) {
  const t = useTheme()
  const dark = t.palette.mode === 'dark'
  const red = t.palette.error.main
  const [hover, setHover] = useState(false)
  useEffect(() => {
    if (hover) return
    const id = window.setTimeout(onClose, REFUSAL_MS)
    return () => window.clearTimeout(id)
  }, [hover, onClose])
  const surface = glass(t, { elevated: true })
  return (
    <Box sx={{ position: 'fixed', inset: 0, zIndex: t.zIndex.tooltip + 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
      pointerEvents: 'none', perspective: '900px', p: 3 }}>
      <Box role="alert" data-kind="error" onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
        sx={{
          ...surface,
          backdropFilter: 'blur(28px) saturate(190%)',
          pointerEvents: 'auto', display: 'flex', alignItems: 'flex-start', gap: 1.5, maxWidth: 520, width: '100%', px: 2.5, py: 2,
          borderRadius: '16px',
          borderTop: `1px solid ${alpha(t.palette.common.white, dark ? 0.28 : 0.9)}`,
          boxShadow: [
            `inset 0 1px 0 ${alpha(t.palette.common.white, dark ? 0.18 : 0.7)}`,
            `0 1px 2px ${alpha(t.palette.common.black, 0.08)}`,
            `0 10px 20px -6px ${alpha(t.palette.common.black, dark ? 0.45 : 0.16)}`,
            `0 28px 56px -12px ${alpha(t.palette.common.black, dark ? 0.55 : 0.24)}`,
            `0 0 0 1px ${alpha(red, 0.18)}`,
          ].join(', '),
          transformOrigin: '50% 100%',
          animation: 'coopRefusalIn 420ms cubic-bezier(0.22, 1, 0.36, 1) both', // the dialogs' ease-out: it settles, no overshoot
          '@keyframes coopRefusalIn': {
            from: { opacity: 0, transform: 'translateY(24px) rotateX(-22deg) scale(0.94)' },
            to: { opacity: 1, transform: 'translateY(0) rotateX(0) scale(1)' },
          },
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        }}>
        <Box aria-hidden sx={{ flex: '0 0 auto', width: 34, height: 34, borderRadius: '50%', display: 'grid', placeItems: 'center', color: red, fontSize: 20,
          background: `radial-gradient(circle at 35% 30%, ${alpha(red, 0.12)}, ${alpha(red, 0.24)})`,
          boxShadow: `inset 0 1px 0 ${alpha(t.palette.common.white, 0.5)}, 0 2px 6px ${alpha(red, 0.3)}` }}>
          <FiAlertCircle />
        </Box>
        <Box sx={{ flex: 1, minWidth: 0, pt: 0.25 }}>
          <Typography sx={{ fontSize: 15, fontWeight: 600, mb: 0.25 }}>This could not be done</Typography>
          <Typography sx={{ fontSize: 13 }} color="text.secondary">{text}</Typography>
        </Box>
        <IconButton size="small" aria-label="Close" onClick={onClose} sx={{ mt: -0.5, mr: -1 }}><FiX /></IconButton>
      </Box>
    </Box>
  )
}

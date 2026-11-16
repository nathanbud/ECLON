import { Alert, Snackbar } from '@mui/material'
import { MutationCache } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

/**
 * Short "done" messages after an action works ("Journal posted to the ledger."), shown for a few seconds at the bottom of the window.
 * A screen does not show them itself: it puts the words on its mutation as {@code meta: { done: '...' }} (or a function of the result and the
 * variables), and {@link noticeMutationCache} shows them. Errors are not shown here; each screen shows its own next to the action.
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

export function notify(text: string) {
  window.dispatchEvent(new CustomEvent<string>(EVENT, { detail: text }))
}

/** Put on the QueryClient so every mutation with {@code meta.done} says it worked. */
export const noticeMutationCache = () => new MutationCache({
  onSuccess: (data, variables, _context, mutation) => {
    const done = mutation.options.meta?.done
    const text = typeof done === 'function' ? done(data, variables) : done
    if (text) notify(text)
  },
})

/** Mounted once per window. */
export function Notices() {
  const [text, setText] = useState<string | null>(null)
  const [seq, setSeq] = useState(0)
  useEffect(() => {
    const on = (e: Event) => { setText((e as CustomEvent<string>).detail); setSeq((n) => n + 1) }
    window.addEventListener(EVENT, on)
    return () => window.removeEventListener(EVENT, on)
  }, [])
  return (
    <Snackbar key={seq} open={!!text} autoHideDuration={5000} onClose={(_, reason) => { if (reason === 'timeout') setText(null) }}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
      <Alert severity="success" variant="filled" onClose={() => setText(null)} sx={{ alignItems: 'center' }}>{text}</Alert>
    </Snackbar>
  )
}

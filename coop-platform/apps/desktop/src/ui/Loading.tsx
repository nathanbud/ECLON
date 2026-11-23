import { Alert, Box, LinearProgress, TableCell, TableRow, Typography, type SxProps, type Theme } from '@mui/material'
import { useIsFetching, useIsMutating } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'

/** The part of a query a loading state needs (any TanStack useQuery result fits). */
type QueryLike = { isLoading: boolean; isError: boolean; error: unknown }

const message = (e: unknown) => (e instanceof Error ? e.message : 'The list could not be loaded.')

/** "Loading the assets…" with a thin bar, so a screen is never blank while it waits. */
export function Loading({ what }: { what: string }) {
  return (
    <Box role="status" aria-live="polite" sx={{ py: 1.5 }}>
      <LinearProgress sx={{ mb: 1, borderRadius: 1 }} />
      <Typography color="text.secondary" sx={{ fontSize: 13 }}>Loading {what}…</Typography>
    </Box>
  )
}

/**
 * The first row of a table body while its list loads, or why it could not be loaded (so a failure is not mistaken for an empty list).
 * Renders nothing once the list is there. Put it as the first child of the TableBody.
 */
export function LoadingRow({ q, what, errors = true }: { q: QueryLike; what: string; errors?: boolean }) {
  if (q.isLoading) {
    return <TableRow><TableCell colSpan={100} sx={{ borderBottom: 0 }}><Loading what={what} /></TableCell></TableRow>
  }
  if (errors && q.isError) {
    return <TableRow><TableCell colSpan={100} sx={{ borderBottom: 0 }}><Alert severity="error">{message(q.error)}</Alert></TableCell></TableRow>
  }
  return null
}

/** The same outside a table. */
export function LoadingBlock({ q, what, errors = true }: { q: QueryLike; what: string; errors?: boolean }) {
  if (q.isLoading) return <Loading what={what} />
  if (errors && q.isError) return <Alert severity="error" sx={{ my: 1 }}>{message(q.error)}</Alert>
  return null
}

/** True once {@code on} has stayed true for {@code ms}; false at once when it turns false. A wait shorter than that shows nothing, so quick loads do not flicker. */
export function useDelayed(on: boolean, ms = 300): boolean {
  const [shown, setShown] = useState(false)
  useEffect(() => {
    if (!on) { setShown(false); return }
    const t = setTimeout(() => setShown(true), ms)
    return () => clearTimeout(t)
  }, [on, ms])
  return on && shown
}

/**
 * A list, table or statement that is being loaded again while its old figures are still on screen (a new filter, date or page, opening many rows): a thin bar
 * runs along its top, and it is dimmed and cannot be clicked until the new figures are in, so nobody acts on what is about to change. The rest of the screen
 * stays usable. Shows only after a short wait, so a quick reload does not flicker. For a first load (nothing on screen yet), use Loading or LoadingRow.
 */
export function BusyArea({ busy, label = 'Loading', children, sx }: { busy: boolean; label?: string; children: ReactNode; sx?: SxProps<Theme> }) {
  const shown = useDelayed(busy)
  return (
    <Box aria-busy={busy} sx={[{ position: 'relative' }, ...(Array.isArray(sx) ? sx : [sx])]}>
      {shown && <LinearProgress aria-label={label} sx={{ position: 'absolute', top: 0, left: 0, right: 0, height: 3, zIndex: 3, borderRadius: 0 }} />}
      <Box sx={{ opacity: shown ? 0.55 : 1, pointerEvents: shown ? 'none' : undefined, transition: 'opacity .15s' }}>{children}</Box>
    </Box>
  )
}

/**
 * The thin bar under the title bar while the person waits on the server anywhere in the app: something on screen is loading with nothing to show yet (a first
 * load, a new search, filter or date) or something is being saved. The lists that refresh themselves in the background every few seconds do not show it.
 * Mounted once, in App.
 */
export function GlobalBusyBar() {
  const loading = useIsFetching({ predicate: (q) => q.state.data === undefined && q.getObserversCount() > 0 })
  const saving = useIsMutating()
  const shown = useDelayed(loading + saving > 0, 400)
  if (!shown) return null
  return (
    <LinearProgress aria-label={saving > 0 ? 'Saving' : 'Loading'}
      sx={{ position: 'fixed', top: 44, left: 0, right: 0, height: 2, zIndex: 11, borderRadius: 0, bgcolor: 'transparent' }} />
  )
}

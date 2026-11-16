import { Alert, Box, LinearProgress, TableCell, TableRow, Typography } from '@mui/material'

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

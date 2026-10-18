import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { getFoPulse, type FoPulse } from './api'

/**
 * Asks a very small question every few seconds ("is anything waiting for me?") instead of reloading the heavy screens. When the answer
 * changes, the screens that depend on it are refreshed, and {@code onArrived} is called when new cash is handed to this person.
 * (A browser cannot send the sign-in header on a server-sent-events stream, so a short poll of a cheap endpoint is used instead.)
 */
export function usePulse(onArrived: (p: FoPulse) => void): FoPulse | undefined {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['fo-pulse'], queryFn: getFoPulse, refetchInterval: 5_000, retry: false })
  const lastId = useRef<number | null>(null)
  const lastSignature = useRef<string | null>(null)
  const notify = useRef(onArrived)
  useEffect(() => { notify.current = onArrived })

  useEffect(() => {
    const p = q.data
    if (!p) return
    const signature = `${p.incoming}/${p.latestId}/${p.disputed}/${p.overrides}`
    if (lastSignature.current !== null && lastSignature.current !== signature) {
      void qc.invalidateQueries({ queryKey: ['fo-handover-mine'] })
      void qc.invalidateQueries({ queryKey: ['fo-handover-drawers'] })
      void qc.invalidateQueries({ queryKey: ['fo-totals'] })
      void qc.invalidateQueries({ queryKey: ['fo-overrides-pending'] })
    }
    if (lastId.current !== null && p.latestId > lastId.current) notify.current(p)
    if (lastId.current === null || p.latestId > lastId.current) lastId.current = p.latestId
    lastSignature.current = signature
  }, [q.data, qc])

  return q.data
}

import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { getMemberImage } from './api'
import type { Id } from './clients'

/** Loads a member's photo or signature; resolves to null when none is on file. */
export function useMemberImage(kind: 'photo' | 'signature', id: Id) {
  const { data } = useQuery({
    queryKey: ['member-image', kind, id],
    queryFn: () => getMemberImage(kind, id.branchCode, id.clientId),
    staleTime: 5 * 60_000,
    retry: false,
  })
  // Object URLs hold the image in memory, so release each one when it is replaced or the page closes.
  useEffect(() => () => { if (data) URL.revokeObjectURL(data) }, [data])
  return data ?? null
}

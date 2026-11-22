import { createContext } from 'react'
import type { Me } from './api'

/**
 * The signed-in person, provided by `SessionGate` and read with `useMe` (both in `session.tsx`).
 * It lives in its own file on purpose: while developing, an edit that makes the dev server reload `session.tsx` would otherwise create a second, empty
 * context while the screens on show still hang off the first, and every `useMe` would throw "useMe must be used inside SessionGate". Nothing edits this file, so
 * the context stays the same object across reloads.
 */
export const SessionCtx = createContext<Me | null>(null)

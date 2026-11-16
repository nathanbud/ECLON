import { useEffect } from 'react'

/**
 * Screens with unsaved work say so here, so leaving for the dashboard (the H key or the Home button) can ask first. A screen calls {@link useLeaveGuard} with a message while it holds
 * unsaved work and null otherwise; the app calls {@link unsavedWork} before it navigates away.
 */
const messages = new Map<symbol, string>()

export function unsavedWork(): string | null {
  for (const m of messages.values()) return m
  return null
}

export function useLeaveGuard(message: string | null) {
  useEffect(() => {
    if (!message) return
    const key = Symbol('guard')
    messages.set(key, message)
    return () => { messages.delete(key) }
  }, [message])
}

import { useState } from 'react'

/** A fresh key for one posting action (letters, digits and dashes, as the server expects). */
export function newRequestKey(): string {
  const c = globalThis.crypto as Crypto | undefined
  if (c?.randomUUID) return c.randomUUID()
  const bytes = new Uint8Array(16)
  if (c?.getRandomValues) c.getRandomValues(bytes)
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * One request key for the posting a dialog makes (documentation/design/posting-engine.md). Every send of that posting, a second press, a retry after a
 * dropped connection, or "posted in the last minute, confirm", carries the same key, so the server writes it once and gives back the same receipt.
 * A new transaction opens a new dialog, which gets a new key.
 */
export function useRequestKey(): string {
  const [key] = useState(newRequestKey)
  return key
}

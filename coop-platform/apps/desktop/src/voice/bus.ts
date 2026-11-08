/**
 * How a voice command reaches a screen that is not part of the title bar: a small message board. A screen that can be told what to do (the client finder, the member profile) listens
 * for these events; nothing else in the app knows about voice.
 */

export const VOICE_FIND = 'coop:voice-find'
export const VOICE_SECTION = 'coop:voice-section'

let pendingFind: string | null = null

/** Open the client finder with this text typed in. Remembered until a finder shows up, because the screen that has it may not be open yet. */
export function requestFind(text: string) {
  pendingFind = text
  window.dispatchEvent(new CustomEvent(VOICE_FIND))
}

export const hasPendingFind = () => pendingFind !== null

/** The text a finder should start with, once: null when voice has not asked for one. */
export function takePendingFind(): string | null {
  const text = pendingFind
  pendingFind = null
  return text
}

export interface SectionRequest { tab?: number; group?: 'Info' | 'Accounts' | 'Other' }

/** Show a section (by its index in the profile's tabs) or the first section of a menu group in the open member's profile. */
export function requestSection(request: SectionRequest) {
  window.dispatchEvent(new CustomEvent<SectionRequest>(VOICE_SECTION, { detail: request }))
}

import type { View } from '../Dashboard'
import { GROUPS, TABS } from '../profile/constants'

/**
 * What a spoken sentence can mean. Voice only moves around and searches: it never saves, approves or posts anything, so a misheard word can at worst open the wrong screen.
 * The words are matched against the screens this person may open (the same registry as the menu and the search), so voice cannot reach a screen the menu would not show.
 */
export type Command =
  | { kind: 'home' }
  | { kind: 'menu' }
  /** Open the client finder with this text typed in (empty: just open it). The person still presses Enter to open a member. */
  | { kind: 'find'; text: string }
  | { kind: 'open'; screen: ScreenRef }
  /** A section of the open member's profile, by its index in {@code TABS}. */
  | { kind: 'section'; tab: number; label: string }
  /** A group of the profile menu (Member info, Accounts, Other), by name. */
  | { kind: 'group'; group: 'Info' | 'Accounts' | 'Other'; label: string }
  | { kind: 'ambiguous'; options: ScreenRef[] }
  | { kind: 'unknown'; heard: string }
  | { kind: 'none' }

export interface ScreenRef { id: string; label: string; view: View; tab?: string }

interface Context {
  /** The screens this person may open. */
  screens: ScreenRef[]
  /** Is a member's profile on the screen? Only then are its sections named. */
  inProfile: boolean
}

/** Lower case, letters and digits only, one space between words. */
export const normalize = (s: string) =>
  s.toLowerCase().replace(/&/g, ' and ').replace(/[^a-z0-9\s]/g, ' ').replace(/\s+/g, ' ').trim()

/** Edit distance between two short strings. */
function distance(a: string, b: string): number {
  const prev = Array.from({ length: b.length + 1 }, (_, j) => j)
  for (let i = 1; i <= a.length; i++) {
    let last = prev[0]
    prev[0] = i
    for (let j = 1; j <= b.length; j++) {
      const keep = prev[j]
      prev[j] = Math.min(prev[j] + 1, prev[j - 1] + 1, last + (a[i - 1] === b[j - 1] ? 0 : 1))
      last = keep
    }
  }
  return prev[b.length]
}

/** How well a spoken phrase matches a name, 0 to 1: whole phrase, whole words inside the name, or close spelling. */
export function similarity(spoken: string, name: string): number {
  const a = normalize(spoken)
  const b = normalize(name)
  if (!a || !b) return 0
  if (a === b) return 1
  const aw = a.split(' ')
  const bw = b.split(' ')
  // every spoken word is a word of the name ("profile" in "client profile"), or the other way round
  if (a.length >= 4 && aw.every((w) => bw.includes(w))) return 0.9
  if (b.length >= 4 && bw.every((w) => aw.includes(w))) return 0.88
  const ratio = 1 - distance(a, b) / Math.max(a.length, b.length)
  return ratio
}

/** Words people say that are not the screen's printed name. */
const ALIASES: Record<string, string> = {
  client: 'Client Profile', clients: 'Client Profile', member: 'Client Profile', members: 'Client Profile', 'member profile': 'Client Profile', profile: 'Client Profile',
  teller: 'Front Office', cash: 'Front Office', 'front desk': 'Front Office',
  approvals: 'Waiting for you', waiting: 'Waiting for you', inbox: 'Waiting for you', 'my work': 'Waiting for you',
  audit: 'Audit Log', 'audit trail': 'Audit Log', logs: 'Audit Log',
  chart: 'Chart of Accounts', 'chart of account': 'Chart of Accounts',
}

const OPEN_VERBS = /^(?:please )?(?:open up|open|go to|goto|go|show me|show|take me to|switch to|navigate to|launch|view|bring up)\s+(?:the )?/
const FIND_VERBS = /^(?:please )?(?:find|search for|search|look up|look for|lookup)\s*(?:a |the )?(?:(?:clients?|members?|customers?)(?:\s+|$))?(?:(?:named|called|name)(?:\s+|$))?(.*)$/

const SECTION_THRESHOLD = 0.85
const SCREEN_THRESHOLD = 0.8

const GROUP_NAMES: { group: 'Info' | 'Accounts' | 'Other'; say: string[]; label: string }[] = [
  { group: 'Info', say: ['member info', 'member information', 'info', 'information'], label: 'Member info' },
  { group: 'Accounts', say: ['accounts', 'account'], label: 'Accounts' },
  { group: 'Other', say: ['other', 'others'], label: 'Other' },
]

/** Reads one spoken sentence. Never throws; a sentence that means nothing comes back as {@code none} or {@code unknown}. */
export function parseCommand(heard: string, ctx: Context): Command {
  let text = normalize(heard)
  if (!text) return { kind: 'none' }
  text = text.replace(/^(?:ok|okay|hey|computer|please)\s+/, '')
  if (/^(?:ok|okay|hey|computer|please|thanks|thank you)$/.test(text)) return { kind: 'none' }

  if (/^(?:go |take me |back |open |show )*(?:to )?(?:the )?(?:home|home page|dashboard|main dashboard|start page)$/.test(text)) return { kind: 'home' }
  if (/^(?:open |show |close |hide |toggle )?(?:the )?(?:main )?menu$/.test(text)) return { kind: 'menu' }

  const find = FIND_VERBS.exec(text)
  if (find && /^(?:please )?(?:find|search|look up|look for|lookup)/.test(text)) return { kind: 'find', text: find[1].trim() }

  const verb = OPEN_VERBS.exec(text)
  const target = (verb ? text.slice(verb[0].length) : text).trim()
  if (!target) return { kind: 'none' }

  // Inside a member's profile the sections and menu groups are named first.
  if (ctx.inProfile) {
    const group = GROUP_NAMES.map((g) => ({ g, score: Math.max(...g.say.map((s) => similarity(target, s))) })).sort((x, y) => y.score - x.score)[0]
    const tab = TABS.map((name, i) => ({ name, i, score: similarity(target, name) })).sort((x, y) => y.score - x.score)[0]
    if (tab.score >= SECTION_THRESHOLD && tab.score >= group.score) return { kind: 'section', tab: tab.i, label: tab.name }
    if (group.score >= SECTION_THRESHOLD) return { kind: 'group', group: group.g.group, label: group.g.label }
  }

  // Only a sentence that asks to open something is matched loosely; a bare word has to name a screen closely, so room noise does not wander the app.
  const aliasTarget = ALIASES[target] ?? target
  const scored = ctx.screens.map((s) => ({ s, score: Math.max(similarity(target, s.label), similarity(aliasTarget, s.label)) })).sort((x, y) => y.score - x.score)
  const need = verb ? SCREEN_THRESHOLD : 0.9
  const best = scored[0]
  if (!best || best.score < need) return { kind: 'unknown', heard: heard.trim() }
  const close = scored.filter((x) => x.score >= need && best.score - x.score < 0.05)
  if (close.length > 1) return { kind: 'ambiguous', options: close.slice(0, 4).map((x) => x.s) }
  return { kind: 'open', screen: best.s }
}

/** Do the profile's menu groups still match the names the parser listens for? A test keeps the two together. */
export const PROFILE_GROUPS = GROUPS.map((g) => g.label)

import { afterEach, describe, expect, it, vi } from 'vitest'
import { adoptCredentials, CANNOT_REACH, friendlyError, getJson, send, signOut, takeSignOutReason } from './http'

describe('messages for failed requests', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keeps the server’s own sentence', () => {
    expect(friendlyError(409, 'The books for 2025 are closed (year-end close), so nothing can be posted dated 2025-06-30.'))
      .toBe('The books for 2025 are closed (year-end close), so nothing can be posted dated 2025-06-30.')
    expect(friendlyError(500, 'The backup failed. The disk is full.')).toBe('The backup failed. The disk is full.')
    expect(friendlyError(400, 'Statement of Operations: the end date is before the start date.')).toContain('Statement of Operations')
  })

  it('replaces technical text and bare status words with plain words', () => {
    expect(friendlyError(500, 'PreparedStatementCallback; bad SQL grammar [SELECT x FROM y]')).toMatch(/^Something went wrong on the server/)
    expect(friendlyError(500, 'java.lang.NullPointerException')).toMatch(/^Something went wrong on the server/)
    expect(friendlyError(403, 'Access Denied')).toMatch(/^You do not have permission/)
    expect(friendlyError(404, null)).toMatch(/^That record was not found/)
    expect(friendlyError(400, 'Bad Request')).toMatch(/^Some details are missing/)
    expect(friendlyError(418)).toContain('code 418')
  })

  it('says the server cannot be reached when no answer comes back', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(getJson('/api/anything')).rejects.toThrow(CANNOT_REACH)
  })

  it('shows the server’s reason from a refused request', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ message: 'Your drawer holds 100.00, less than the 500.00 to pay out.' }), { status: 400 })))
    await expect(send('POST', '/api/x', {})).rejects.toThrow('Your drawer holds 100.00')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>oops</html>', { status: 502 })))
    await expect(send('POST', '/api/x', {})).rejects.toThrow(/^Something went wrong on the server/)
  })

  it('signs out with a reason when the server stops accepting the sign-in, and with none when the person signs out', async () => {
    adoptCredentials('dGVsbGVyMTpwdw==')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
    await expect(getJson('/api/anything')).rejects.toThrow()
    expect(takeSignOutReason()).toMatch(/An administrator may have switched your account off or reset your password/)
    expect(takeSignOutReason()).toBeNull()
    adoptCredentials('dGVsbGVyMTpwdw==')
    signOut()
    expect(takeSignOutReason()).toBeNull()
  })
})

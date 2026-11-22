import { afterEach, describe, expect, it, vi } from 'vitest'
import { ACCOUNT_DISABLED_SIGNED_OUT, adoptCredentials, CANNOT_REACH, friendlyError, getJson, send, signOut, takeSignOutReason } from './http'

describe('messages for failed requests', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('keeps the server’s own sentence', () => {
    expect(friendlyError(409, 'The books for 2025 are closed (year-end close), so nothing can be posted dated 2025-06-30.'))
      .toBe('The books for 2025 are closed (year-end close), so nothing can be posted dated 2025-06-30.')
    expect(friendlyError(500, 'The backup failed. The disk is full.')).toBe('The backup failed. The disk is full.')
    expect(friendlyError(400, 'Statement of Operations: the end date is before the start date.')).toContain('Statement of Operations')
  })

  it('replaces technical text and bare status words with plain words', () => {
    // raw technical text is never shown; with nothing better, it says the server gave no reason and where the details are
    expect(friendlyError(500, 'PreparedStatementCallback; bad SQL grammar [SELECT x FROM y]')).toMatch(/^The server failed while doing this and gave no reason/)
    expect(friendlyError(500, 'java.lang.NullPointerException')).toMatch(/logs\/backend\.log/)
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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>oops</html>', { status: 500 })))
    await expect(send('POST', '/api/x', {})).rejects.toThrow(/^The server failed while doing this and gave no reason/)
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

  it('shows the server’s everyday explanation of an unexpected failure as it is, with its reference', async () => {
    const message = 'This was not saved because one of the entries is longer than the system allows. Shorten what you typed and try again. Reference: E-261121-AB3K.'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 400, reference: 'E-261121-AB3K', message }), { status: 400 })))
    await expect(send('PUT', '/api/x', {})).rejects.toThrow(message)
  })

  it('says the server program is not answering, rather than that something went wrong', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502 })))
    await expect(getJson('/api/x')).rejects.toThrow(/server program is not answering/)
    expect(friendlyError(500)).toMatch(/gave no reason/)
  })

  it('says the account was disabled when the server gives that reason', async () => {
    adoptCredentials('dGVsbGVyMTpwdw==')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401, headers: { 'X-Auth-Reason': 'ACCOUNT_DISABLED' } })))
    await expect(getJson('/api/me/access')).rejects.toThrow()
    expect(takeSignOutReason()).toBe(ACCOUNT_DISABLED_SIGNED_OUT)
  })
})

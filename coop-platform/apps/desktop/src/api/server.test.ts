import { beforeEach, describe, expect, it } from 'vitest'
import { activeServer, addServer, apiUrl, chooseServer, deleteServer, editServer, LOCAL_ID, normalizeServerUrl, servers } from './server'

describe('the server list', () => {
  beforeEach(() => localStorage.clear())

  it('accepts only a plain web address and keeps just its origin', () => {
    expect(normalizeServerUrl('http://192.168.1.20:8090')).toBe('http://192.168.1.20:8090')
    expect(normalizeServerUrl('  office-pc:8090/ ')).toBe('http://office-pc:8090')
    expect(normalizeServerUrl('https://coop.example/api/x?y=1')).toBe('https://coop.example')
    for (const bad of ['', '   ', 'ftp://host', 'javascript:alert(1)', 'http://user:pw@host', 'file:///c:/x', 'http://']) expect(normalizeServerUrl(bad), bad).toBeNull()
  })

  it('always has the built-in entry first, and it cannot be changed or deleted', () => {
    expect(servers()[0].id).toBe(LOCAL_ID)
    expect(() => editServer(LOCAL_ID, 'x', 'http://h:1')).toThrow()
    expect(() => deleteServer(LOCAL_ID)).toThrow()
  })

  it('adds, edits and deletes a server, and remembers them', () => {
    const a = addServer('Office', 'http://192.168.1.20:8090/')
    expect(a.url).toBe('http://192.168.1.20:8090')
    expect(servers().map((s) => s.name)).toEqual(['This computer', 'Office'])
    editServer(a.id, 'Head office', 'http://10.0.0.5:8090')
    expect(servers()[1]).toMatchObject({ name: 'Head office', url: 'http://10.0.0.5:8090' })
    deleteServer(a.id)
    expect(servers()).toHaveLength(1)
  })

  it('refuses a missing name, a bad address and duplicates, with a message', () => {
    addServer('Office', 'http://a:1')
    expect(() => addServer('  ', 'http://b:1')).toThrow('Give the server a name')
    expect(() => addServer('Other', 'not a url ://')).toThrow('http://host:port')
    expect(() => addServer('office', 'http://b:1')).toThrow('already a server named')
    expect(() => addServer('Other', 'http://a:1')).toThrow('already in the list')
  })

  it('sends "/api" calls to the chosen server, and leaves other addresses alone', () => {
    expect(apiUrl('/api/me')).toBe('/api/me') // this computer: the page's own address
    const a = addServer('Office', 'http://192.168.1.20:8090')
    chooseServer(a.id)
    expect(activeServer().id).toBe(a.id)
    expect(apiUrl('/api/me')).toBe('http://192.168.1.20:8090/api/me')
    expect(apiUrl('/logo.svg')).toBe('/logo.svg')
    deleteServer(a.id) // deleting the one in use goes back to this computer
    expect(activeServer().id).toBe(LOCAL_ID)
    expect(apiUrl('/api/me')).toBe('/api/me')
  })

  it('ignores damaged storage', () => {
    localStorage.setItem('coop.servers', '{ not json')
    localStorage.setItem('coop.server', '"gone"')
    expect(servers()).toHaveLength(1)
    expect(activeServer().id).toBe(LOCAL_ID)
    localStorage.setItem('coop.servers', JSON.stringify([{ id: 'x', name: 'Bad', url: 'ftp://nope' }, { id: 'y', name: 'Good', url: 'http://h:1' }]))
    expect(servers().map((s) => s.name)).toEqual(['This computer', 'Good'])
  })
})

/**
 * The fingerprint scanner as the app sees it. A real scanner is driven by its maker's SDK on the workstation, so the app talks to it through a small local agent (or, in the
 * desktop build, a native plugin) that implements this contract and returns a standard template (ISO/IEC 19794-2) with its quality score (NFIQ2-style, 0 to 100):
 *
 *   GET  http://127.0.0.1:9433/status            -> { device: string }
 *   POST http://127.0.0.1:9433/capture {finger}  -> { template: base64, format: 'ISO19794-2', quality: number, device: string }
 *
 * Until a scanner is bought the app uses {@link SimulatedScanner}, which produces "SIM1" templates that the server's stand-in matcher understands. The raw template leaves this
 * file only to be sent to the server; it is never shown or stored in the browser (the simulator keeps its own fake "finger" so a re-scan matches).
 */
export interface ScanResult { template: string; format: string; quality: number; device: string }

export interface Scanner {
  name: string
  simulated: boolean
  capture(member: string, finger: number): Promise<ScanResult>
}

const AGENT = 'http://127.0.0.1:9433'
const SIM_BYTES = 128

const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes))
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0))

/** Stand-in scanner: every (member, finger) has a fixed random "finger" kept locally, and each scan flips a few bits of it, like a real re-scan of the same finger. */
export const SimulatedScanner: Scanner = {
  name: 'Simulated scanner (testing only)',
  simulated: true,
  async capture(member, finger) {
    await new Promise((r) => setTimeout(r, 1100))
    const key = `coop.sim-finger.${member}.${finger}`
    let base: Uint8Array
    try {
      const saved = localStorage.getItem(key)
      base = saved ? unb64(saved) : crypto.getRandomValues(new Uint8Array(SIM_BYTES))
      if (!saved) localStorage.setItem(key, b64(base))
    } catch {
      base = crypto.getRandomValues(new Uint8Array(SIM_BYTES))
    }
    const scan = base.slice()
    const flips = new Uint32Array(40)
    crypto.getRandomValues(flips)
    for (const f of flips) scan[f % SIM_BYTES] ^= 1 << ((f >> 8) % 8)
    // About one scan in eight is poor, so the retry path is seen.
    const quality = Math.random() < 0.125 ? 25 + Math.floor(Math.random() * 20) : 62 + Math.floor(Math.random() * 33)
    return { template: b64(scan), format: 'SIM1', quality, device: 'Simulated scanner' }
  },
}

const agentScanner = (device: string): Scanner => ({
  name: device,
  simulated: false,
  async capture(_member, finger) {
    const res = await fetch(`${AGENT}/capture`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ finger }) })
    if (!res.ok) throw new Error('The scanner did not return a scan. Place the finger on the glass and try again.')
    return res.json() as Promise<ScanResult>
  },
})

/** The connected scanner if its local agent answers, otherwise the simulator. */
export async function findScanner(): Promise<Scanner> {
  try {
    const res = await fetch(`${AGENT}/status`, { signal: AbortSignal.timeout(800) })
    if (res.ok) return agentScanner(((await res.json()) as { device?: string }).device ?? 'Fingerprint scanner')
  } catch { /* no agent: use the simulator */ }
  return SimulatedScanner
}

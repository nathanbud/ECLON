import type { Model } from 'vosk-browser'

/**
 * The speech recognizer behind voice commands: Vosk, running inside the app on this computer (WebAssembly in a worker) with a small English model that ships with the app
 * (`public/voice/model.tar.gz`, put there by `scripts/fetch-voice-model.ps1`). Nothing is sent over the network and no audio is kept: the microphone is read, turned into words, and
 * dropped. Turning the microphone off stops its tracks, so the operating system's "microphone in use" light goes out.
 */

export const MODEL_URL = '/voice/model.tar.gz'

/** Is the speech model there? The button is shown disabled, with how to install it, when it is not. */
export async function modelInstalled(): Promise<boolean> {
  try {
    const res = await fetch(MODEL_URL, { method: 'HEAD', cache: 'no-store' })
    // a dev server answers an unknown path with its index page, so check that it really is the archive
    return res.ok && !(res.headers.get('content-type') ?? '').includes('text/html')
  } catch {
    return false
  }
}

let loading: Promise<Model> | null = null
let idle: ReturnType<typeof setTimeout> | null = null
/** The model takes memory, so it is let go after a while without use and loaded again on the next press. */
const KEEP_MS = 5 * 60_000

function loadModel(): Promise<Model> {
  if (!loading) {
    // The recognizer (a few MB of WebAssembly) is fetched only when someone first uses voice, so it costs nothing to everyone else.
    loading = import('vosk-browser').then(({ createModel }) => createModel(MODEL_URL)).catch((e) => { loading = null; throw e })
  }
  return loading
}

function releaseLater() {
  if (idle) clearTimeout(idle)
  idle = setTimeout(() => {
    const p = loading
    loading = null
    void p?.then((m) => m.terminate()).catch(() => {})
  }, KEEP_MS)
}

export interface Listener {
  /** Words heard so far in the current sentence. */
  onPartial: (text: string) => void
  /** A finished sentence. */
  onSentence: (text: string) => void
  onError: (message: string) => void
}

export interface Session {
  /** Stops listening and turns the microphone off. */
  stop: () => void
}

/** Loads the model if needed, opens the microphone and starts listening. Rejects with a message fit to show when the microphone or the model cannot be used. */
export async function startListening(listener: Listener, onReady?: () => void): Promise<Session> {
  if (idle) { clearTimeout(idle); idle = null }
  if (!navigator.mediaDevices?.getUserMedia) throw new Error('This window cannot use a microphone.')
  let model: Model
  try {
    model = await loadModel()
  } catch {
    throw new Error('The voice model could not be loaded. Run scripts\\fetch-voice-model.ps1 and restart the app.')
  }
  let stream: MediaStream
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true }, video: false })
  } catch {
    throw new Error('The microphone could not be opened. Check that one is connected and that this app may use it.')
  }

  const context = new AudioContext()
  const recognizer = new model.KaldiRecognizer(context.sampleRate)
  recognizer.on('partialresult', (m) => { if ('result' in m && 'partial' in m.result) listener.onPartial(m.result.partial) })
  recognizer.on('result', (m) => { if ('result' in m && 'text' in m.result && m.result.text.trim()) listener.onSentence(m.result.text.trim()) })
  recognizer.on('error', (m) => { if ('error' in m) listener.onError(m.error) })

  const source = context.createMediaStreamSource(stream)
  // A ScriptProcessor is the simplest way to hand the recognizer each block of sound; it is old but works in every webview.
  const node = context.createScriptProcessor(4096, 1, 1)
  node.onaudioprocess = (e) => {
    try { recognizer.acceptWaveform(e.inputBuffer) } catch { /* the session was stopped mid-block */ }
  }
  source.connect(node)
  node.connect(context.destination)
  onReady?.()

  let stopped = false
  return {
    stop: () => {
      if (stopped) return
      stopped = true
      node.onaudioprocess = null
      try { source.disconnect(); node.disconnect() } catch { /* already apart */ }
      stream.getTracks().forEach((t) => t.stop())
      void context.close()
      try { recognizer.remove() } catch { /* already gone */ }
      releaseLater()
    },
  }
}

/**
 * Prints a PDF made by the server: it is loaded into a hidden frame and that frame's print window opens (in the desktop app and in Chrome or Edge, whose PDF viewer
 * prints it as laid out). Where a frame cannot print it, the file is saved instead, so the person can open and print it. Resolves to how it went.
 */
export async function printPdf(file: Blob, name: string): Promise<'printing' | 'saved'> {
  const url = URL.createObjectURL(file)
  const frame = document.createElement('iframe')
  frame.style.position = 'fixed'
  frame.style.width = '0'
  frame.style.height = '0'
  frame.style.border = '0'
  frame.setAttribute('aria-hidden', 'true')
  frame.src = url
  document.body.appendChild(frame)
  try {
    await new Promise<void>((done, fail) => {
      const timer = setTimeout(() => fail(new Error('the PDF did not load')), 15_000)
      frame.onload = () => { clearTimeout(timer); done() }
    })
    const w = frame.contentWindow
    if (!w) throw new Error('no frame')
    w.focus()
    w.print()
    // the frame stays a while: the print window reads from it while it is open
    setTimeout(() => { frame.remove(); URL.revokeObjectURL(url) }, 60_000)
    return 'printing'
  } catch {
    frame.remove()
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
    return 'saved'
  }
}

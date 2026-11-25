/**
 * Clipboard write that also works on non-secure origins (plain HTTP), where
 * navigator.clipboard is undefined and only the legacy execCommand path remains.
 * Returns whether the text reached the clipboard; never throws.
 */
export async function copyText(text: string): Promise<boolean> {
  if (window.isSecureContext && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // writeText can reject even in a secure context (e.g. document not
      // focused); the legacy path below may still succeed.
    }
  }
  return copyViaExecCommand(text)
}

function copyViaExecCommand(text: string): boolean {
  const active = document.activeElement instanceof HTMLElement ? document.activeElement : null
  const selection = document.getSelection()
  const ranges: Range[] = []
  if (selection) {
    for (let i = 0; i < selection.rangeCount; i++) ranges.push(selection.getRangeAt(i))
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '0'
  textarea.style.opacity = '0'
  textarea.style.pointerEvents = 'none'
  document.body.appendChild(textarea)

  let ok = false
  try {
    textarea.focus({ preventScroll: true })
    textarea.select()
    ok = document.execCommand('copy')
  } catch {
    ok = false
  } finally {
    textarea.remove()
    if (selection && ranges.length) {
      selection.removeAllRanges()
      for (const range of ranges) selection.addRange(range)
    }
    active?.focus({ preventScroll: true })
  }
  return ok
}
// A message typed on the phone's floating island (native overlay): the app's page owns the connection to
// Hermes, so the native side hands it the text and the dropped files here. The island's files arrive through
// the same bridge call as a Share-to-Hermes (sharedItem), so they are attached exactly as shared files are.
import { attachFile, errText, sendPrompt, startDraft } from './gateway'
import { sharedItem } from './bridge'
import { setState, toast } from './store'
import { toFile, whenOnline } from './share'

declare global {
  interface Window {
    hermesIsland?: (count: number, text: string) => string
  }
}

export async function islandSend(count: number, text: string): Promise<void> {
  if (!(await whenOnline(60_000))) {
    toast('Hermes is offline: your island message was not sent', 'error')
    return
  }
  setState({ screen: null, sheet: null })
  startDraft()
  // The new chat's composer mounts on the next render.
  await new Promise(r => setTimeout(r, 50))
  for (let i = 0; i < count; i++) {
    const it = sharedItem(i)
    if (!it.b64) {
      toast(`${it.name || 'Dropped file'}: ${it.error || 'unreadable'}`, 'error')
      continue
    }
    try {
      await attachFile(toFile(it.b64, it.name || `dropped-${i + 1}`, it.mime || 'application/octet-stream'))
    } catch (err) {
      toast(errText(err), 'error')
    }
  }
  try {
    await sendPrompt(text)
  } catch (err) {
    toast(errText(err), 'error')
  }
}

// Returns 'y' as soon as the request is accepted; the work continues asynchronously.
window.hermesIsland = (count: number, text: string) => {
  void islandSend(Number(count) || 0, String(text || ''))
  return 'y'
}

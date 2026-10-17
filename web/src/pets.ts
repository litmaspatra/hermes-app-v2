import { rpc } from './gateway'
import { installedPets, isNative, savePetSheet } from './bridge'

// Pets come through Hermes itself (its petdex support downloads them on the Linux side, the same way the desktop app and
// Hermes Relay get theirs). The phone's own download is only the first try: the pet CDN answers it with HTTP 401.

interface Info {
  slug?: string | null
  spritesheetBase64?: string | null
}

/** "" = installed (and made the pet when `select`), otherwise what went wrong. */
export async function installViaHermes(slug: string, select = true): Promise<string> {
  try {
    await rpc('pet.select', { slug }, 180_000) // installs if needed and activates it for Hermes too
    const r = await rpc<Info>('pet.info', {}, 60_000)
    if (!r.spritesheetBase64 || (r.slug && r.slug !== slug)) return 'Hermes did not send the sheet'
    return savePetSheet(slug, r.spritesheetBase64, select)
  } catch (e) {
    return e instanceof Error ? e.message : 'Hermes could not install it'
  }
}

let triedAuto = false

/** No pet installed yet: take the first one Hermes can install, so nobody has to pick or set one up. Once per app start. */
export async function autoPet(): Promise<void> {
  if (!isNative || triedAuto || installedPets().length) return
  triedAuto = true
  try {
    const g = await rpc<{ pets?: { slug: string }[] }>('pet.gallery', {}, 60_000)
    for (const p of (g.pets ?? []).slice(0, 4)) if (!(await installViaHermes(p.slug))) return
  } catch {
    triedAuto = false // Hermes not ready: the next connection tries again
  }
}

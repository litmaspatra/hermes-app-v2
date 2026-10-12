import { haptic } from '../bridge'
import { pauseSpeaking, resumeSpeaking, seekSpeaking, setReadingSpeed, stopSpeaking, ttsConfig, useVoice } from '../voice'

const SPEEDS = [0.75, 1, 1.25, 1.5, 1.75, 2]
const fmt = (r: number) => `${Number(r.toFixed(2))}×`

/** Pop-up player above the composer while a reply is read aloud: pause / resume, speed, scrub through the text. */
export function TtsPlayer() {
  const p = useVoice(v => v.player)
  useVoice(v => v.catalog) // re-render when settings change the rate elsewhere
  if (!p) return null
  const rate = ttsConfig().rate
  const nextSpeed = () => SPEEDS.find(x => x > rate + 0.01) ?? SPEEDS[0]
  const pct = p.total > 0 ? (p.pos / p.total) * 100 : 0
  return (
    <div className="tts-player" role="region" aria-label="Reading aloud">
      <button className="tts-btn" aria-label={p.paused ? 'Resume reading' : 'Pause reading'} onClick={() => { haptic(); p.paused ? resumeSpeaking() : pauseSpeaking() }}>
        {p.paused ? (
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true"><path d="M8 5v14l11-7z" /></svg>
        ) : (
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true"><path d="M7 5h4v14H7zM13 5h4v14h-4z" /></svg>
        )}
      </button>
      <input
        className="slider tts-seek"
        type="range"
        min={0}
        max={Math.max(1, p.total)}
        step={1}
        value={Math.min(p.pos, p.total)}
        style={{ ['--pct' as string]: `${pct}%` }}
        aria-label="Reading position"
        onChange={e => seekSpeaking(Number(e.target.value))}
      />
      <button className="tts-speed" aria-label={`Reading speed ${fmt(rate)}, tap to change`} onClick={() => { haptic(); setReadingSpeed(nextSpeed()) }}>
        {fmt(rate)}
      </button>
      <button className="tts-btn" aria-label="Close player" onClick={() => { haptic(); stopSpeaking() }}>
        ✕
      </button>
    </div>
  )
}

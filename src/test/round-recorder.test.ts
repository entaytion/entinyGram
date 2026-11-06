import { describe, expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'

const components = new URL('../../worktree/TMessagesProj/src/main/java/org/telegram/ui/Components/', import.meta.url)
const camera = readFileSync(new URL('InstantCameraView2.java', components), 'utf8')
const zoom = readFileSync(new URL('RoundZoomSliderView.java', components), 'utf8')
const helper = readFileSync(new URL('../fork/helpers/chat/RoundRecorderHelper.kt', import.meta.url), 'utf8')
const settings = readFileSync(new URL('../fork/ui/settings/CameraSoundSettingsActivity.kt', import.meta.url), 'utf8')

const zoomLayout = camera.match(/addView\(inu_cgZoomSlider, LayoutHelper\.createFrame\(([\s\S]*?)\n\s*\)\);/)![1]
const layoutArgs = zoomLayout.split(',').map(value => value.trim())

function pixels(value: string, density: number): number {
  const dp = value.match(/^dp\((\d+)\)$/)
  const input = dp ? Math.ceil(Number(dp[1]) * density) : Number(value)
  return Math.ceil(input * density)
}

describe('round zoom layout', () => {
  for (const density of [1, 1.5, 2, 2.625, 3.5, 4]) {
    test(`converts dimensions to pixels once at density ${density}`, () => {
      expect(layoutArgs[0]).toBe('LayoutHelper.MATCH_PARENT')
      expect(layoutArgs[2]).toBe('Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM')
      for (const [index, dp] of [[1, 56], [3, 28], [4, 0], [5, 28], [6, 64]]) {
        expect(pixels(layoutArgs[index], density)).toBe(Math.ceil(dp * density))
      }
    })
  }

  test('both icons use the same foreground as unselected zoom labels', () => {
    for (const icon of ['minusButtonIcon', 'plusButtonIcon']) {
      const tint = zoom.match(new RegExp(`${icon}\\.setTint\\((\\w+)\\)`))![1]
      expect(tint).toBe('unselectedToggleColor')
    }
  })
})

describe('round exposure', () => {
  test('entering recording resets EV before syncing and showing the slider', () => {
    const block = camera.match(/if \(inu_recording && previous != RoundVideoSession\.State\.RECORDING\) \{([\s\S]*?)RoundRecorderHelper\.syncAeButton/)![1]
    expect(block).toMatch(/session\.setExposureEv\(0f\);\s*inu_syncEvSlider\(\);\s*inu_showEvSlider\(\);/)
  })

  test('switching cameras resets EV before syncing the slider', () => {
    const block = camera.match(/public void onCameraSwitchCompleted\([^)]*\) \{([\s\S]*?)maybeFinishCameraFlip\(\);/)![1]
    expect(block).toMatch(/session\.setExposureEv\(0f\);\s*inu_syncEvSlider\(\);/)
  })

  test('slider changes apply EV without persisting it', () => {
    const block = camera.match(/inu_evSlider\.setOnValueChange\(value -> \{([\s\S]*?)\n\s*\}\);/)![1]
    expect(block).toContain('session.setExposureEv(ev);')
    expect(/RoundRecorderHelper\.(?:rememberExposure|applyRememberedExposure)\(/.test(camera)).toBe(false)
  })

  test('removes round persistence helpers but retains Camera2Session persistence', () => {
    expect(/fun (?:rememberExposure|applyRememberedExposure)\(session: RoundVideoSession\?/.test(helper)).toBe(false)
    expect(helper).toContain('fun rememberExposure(session: Camera2Session?, ev: Float)')
    expect(helper).toContain('fun syncExposureButtons(buttons: ExposureLevelButtonsView?, session: Camera2Session?)')
  })
})

function blockAfter(source: string, marker: string): string {
  const markerIndex = source.indexOf(marker)
  if (markerIndex < 0) throw new Error(`Missing ${marker}`)
  const start = source.indexOf('{', markerIndex)
  let depth = 0
  for (let end = start; end < source.length; end++) {
    if (source[end] === '{') depth++
    if (source[end] === '}' && --depth === 0) return source.slice(start, end + 1)
  }
  throw new Error(`Unclosed block after ${marker}`)
}

describe('camera settings', () => {
  const fill = blockAfter(settings, 'override fun fillItems')
  const newRecorder = blockAfter(fill, 'if (newRecorder)')
  const legacyRecorder = blockAfter(fill, '} else {')
  const shared = fill.replace(newRecorder, '').replace(legacyRecorder, '')
  const entries = [...settings.matchAll(/SearchRegistry\.Entry\("([^"]+)", R\.string\.(\w+), (\w+)\)/g)]

  for (const key of ['ZOOM_SLIDER', 'LOCK_EXPOSURE', 'EXPOSURE_BUTTON', 'EXPOSURE_LEVELS']) {
    test(`${key} is available once for both recorders`, () => {
      const id = `TOGGLE_ROUND_RECORDER_${key}`
      expect(shared.includes(id)).toBe(true)
      expect(fill.split(id).length - 1).toBe(1)
      expect(newRecorder.includes(id)).toBe(false)
      expect(legacyRecorder.includes(id)).toBe(false)
    })
  }

  test('legacy-only controls stay in the legacy branch', () => {
    for (const key of ['ZOOM_BUTTONS', 'KEEP_ZOOM', 'MIRROR_FRONT', 'EXPONENTIAL_ZOOM', 'DUAL_CAMERA']) {
      const id = `TOGGLE_ROUND_RECORDER_${key}`
      expect(legacyRecorder.includes(id)).toBe(true)
      expect(shared.includes(id) || newRecorder.includes(id)).toBe(false)
    }
  })

  test('new-recorder-only controls stay in the new recorder branch', () => {
    for (const id of ['BUTTON_ROUND_VIDEO_RESOLUTION', 'BUTTON_ROUND_CAMERA_RESOLUTION', 'TOGGLE_ROUND_COMPOSITION', 'TOGGLE_ROUND_EV_SLIDER_RIGHT']) {
      expect(newRecorder.includes(id)).toBe(true)
      expect(shared.includes(id) || legacyRecorder.includes(id)).toBe(false)
    }
  })

  test('every setting has exactly one explicit search entry', () => {
    const ids = [...settings.matchAll(/private val ((?:BUTTON|TOGGLE)_\w+) = InuUtils\.generateId\(\)/g)].map(match => match[1])
    expect(entries.map(match => match[3]).sort()).toEqual(ids.sort())
    const slugs = entries.map(match => match[1])
    expect(new Set(slugs).size).toBe(slugs.length)
  })

  test('camera additions have stable search targets', () => {
    for (const [slug, title, id] of [
      ['attach-camera-resolution', 'InuAttachCameraResolution', 'BUTTON_ATTACH_CAMERA_RESOLUTION'],
      ['attach-camera-exposure-levels', 'InuAttachCameraExposureLevels', 'TOGGLE_ATTACH_CAMERA_EXPOSURE_LEVELS'],
      ['round-recorder-ev-slider-right', 'InuRoundRecorderEvSliderRight', 'TOGGLE_ROUND_EV_SLIDER_RIGHT'],
    ]) {
      expect(entries.find(match => match[1] === slug)?.slice(2)).toEqual([title, id])
    }
  })
})

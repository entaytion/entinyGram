import { describe, expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'

const components = new URL('../../worktree/TMessagesProj/src/main/java/org/telegram/ui/Components/', import.meta.url)
const camera = readFileSync(new URL('InstantCameraView2.java', components), 'utf8')
const zoom = readFileSync(new URL('RoundZoomSliderView.java', components), 'utf8')
const helper = readFileSync(new URL('../fork/helpers/chat/RoundRecorderHelper.kt', import.meta.url), 'utf8')

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

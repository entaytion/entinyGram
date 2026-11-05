import { describe, expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'

const components = new URL('../../worktree/TMessagesProj/src/main/java/org/telegram/ui/Components/', import.meta.url)
const camera = readFileSync(new URL('InstantCameraView2.java', components), 'utf8')
const zoom = readFileSync(new URL('RoundZoomSliderView.java', components), 'utf8')

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

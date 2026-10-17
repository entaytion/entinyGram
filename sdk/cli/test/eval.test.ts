import { describe, expect, it } from 'vitest'
import { compileSnippet } from '../src/commands/eval.js'

// eslint-disable-next-line no-eval
const run = async (source: string) => (0, eval)(await compileSnippet(source))

describe('compileSnippet', () => {
  it('answers with a parenthesized last expression', async () => {
    expect(await run('const x = 1; ({ a: x });')).toEqual({ a: 1 })
  })

  it('answers with a bare last expression', async () => {
    expect(await run('const x = 2\nx * 3')).toBe(6)
  })
})

import { Database } from 'bun:sqlite'
import { expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'

const loader = readFileSync(new URL('../fork/helpers/feed/FeedTimelineLoader.kt', import.meta.url), 'utf8')
type Row = { date: number, uid: number, mid: number }

function bound(row: Row, direction: 'OLDER' | 'NEWER', inclusive = false) {
  const operator = loader.match(new RegExp(`${direction}\\("([<>])"\\)`))![1]
  const values: Record<string, string | number> = {
    operator, midOperator: `${operator}${inclusive ? '=' : ''} `,
    'cursor.date': row.date, 'cursor.uid': row.uid, 'cursor.mid': row.mid,
  }
  const body = loader.split('private fun appendCursorBound(')[1].split('private fun clusterGroupedMessages')[0]
  return [...body.matchAll(/sql\.append\(([^\n]+)\)/g)].map(([, expression]) => {
    if (expression.startsWith('"')) return JSON.parse(expression)
    if (expression === "' '") return ' '
    if (!(expression in values)) throw new Error(`Unknown SQL expression: ${expression}`)
    return values[expression]
  }).join('')
}

test('feed cursors traverse equal timestamps and message ids across channels without skipping rows', () => {
  const db = new Database(':memory:')
  try {
    db.exec('CREATE TABLE messages_v2(date INTEGER, uid INTEGER, mid INTEGER)')
    for (const uid of [-900, -700, -300]) {
      for (let mid = 1; mid <= 57; mid++) {
        db.query('INSERT INTO messages_v2 VALUES (?, ?, ?)').run(100 + Math.floor(mid / 8), uid, mid)
      }
    }
    for (const direction of ['OLDER', 'NEWER'] as const) {
      const order = direction === 'OLDER' ? 'DESC' : 'ASC'
      const expected = db.query(`SELECT * FROM messages_v2 ORDER BY date ${order}, uid ${order}, mid ${order}`).all() as Row[]
      const actual: Row[] = []
      let cursor: Row | undefined
      for (let page = 0; page < 30; page++) {
        const rows = db.query(`SELECT * FROM messages_v2 WHERE mid > 0${cursor ? bound(cursor, direction) : ''} ORDER BY date ${order}, uid ${order}, mid ${order} LIMIT 13`).all() as Row[]
        if (!rows.length) break
        actual.push(...rows)
        cursor = rows.at(-1)
      }
      expect(actual).toEqual(expected)
      const anchor = expected[41]
      expect(db.query(`SELECT * FROM messages_v2 WHERE 1${bound(anchor, direction, true)} ORDER BY date ${order}, uid ${order}, mid ${order} LIMIT 1`).get()).toEqual(anchor)
    }
  } finally {
    db.close()
  }
})

test('history-complete sentinel is not a backfill hole, but real gaps remain visible', () => {
  const db = new Database(':memory:')
  try {
    db.exec('CREATE TABLE messages_holes(uid INTEGER, start INTEGER, end INTEGER)')
    db.exec('INSERT INTO messages_holes VALUES (-10, 1, 1), (-20, 1, 1), (-20, 4, 9), (-20, 12, 18), (-30, 0, 8), (-40, 0, 50)')
    const sql = loader.match(/"(SELECT uid, max\(end\) FROM messages_holes[^"\n]+)"/)![1]
      .replace('$dialogIdsSql', '-10,-20,-30')
    expect(db.query(sql).all()).toEqual([
      { uid: -30, 'max(end)': 8 },
      { uid: -20, 'max(end)': 18 },
    ])
  } finally {
    db.close()
  }
})

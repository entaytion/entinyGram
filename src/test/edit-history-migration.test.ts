import { Database } from 'bun:sqlite'
import { expect, test } from 'bun:test'
import { readFileSync } from 'node:fs'

const migrations = readFileSync(new URL('../fork/helpers/InuDatabaseHelper.kt', import.meta.url), 'utf8')
const archive = readFileSync(new URL('../fork/helpers/archive/ArchiveStore.kt', import.meta.url), 'utf8')

test('edit history cleanup query accepts the old schema and author migration preserves revisions', () => {
  const db = new Database(':memory:')
  try {
    db.exec(migrations.match(/"(CREATE TABLE inu_edit_history_v20[^"]+)"/)![1])
    db.exec(migrations.match(/"(ALTER TABLE inu_edit_history_v20 RENAME TO inu_edit_history)"/)![1])
    db.query('INSERT INTO inu_edit_history(dialog_id, msg_id, text, date, media_path, archived_at) VALUES(?, ?, ?, ?, ?, ?)')
      .run(10, 42, 'before upgrade', 100, '/archive/42.bin', 101)

    const cleanup = archive.match(/fun cleanEditHistory\([\s\S]*?queryFinalized\("([^"]+)"/)![1]
    expect(db.query(cleanup).all()).toMatchObject([
      { id: 1, dialog_id: 10, msg_id: 42, text: 'before upgrade', date: 100, media_path: '/archive/42.bin' },
    ])
    db.exec(migrations.match(/"(CREATE UNIQUE INDEX IF NOT EXISTS idx_inu_edit_history_revision[^"]+)"/)![1])
    db.exec(migrations.match(/"(ALTER TABLE inu_edit_history ADD COLUMN from_id[^"]+)"/)![1])

    const read = archive.match(/fun loadEditHistory\([\s\S]*?readEditHistory\(db, "([^"]+)"/)![1]
    const oldRevision = {
      date: 100, text: 'before upgrade', media_path: '/archive/42.bin', entities: null, media: null, id: 1, from_id: 0,
    }
    expect(db.query(read).all(10, 42)).toEqual([oldRevision])
    expect(db.query('SELECT archived_at FROM inu_edit_history WHERE id = 1').get()).toEqual({ archived_at: 101 })

    const insert = archive.match(/"(INSERT INTO inu_edit_history\(dialog_id[^"]+)"/)![1]
    db.query(insert).run(10, 42, 'after upgrade', 200, null, null, null, 201, 77)
    expect(db.query(read).all(10, 42)).toEqual([
      oldRevision,
      { date: 200, text: 'after upgrade', media_path: null, entities: null, media: null, id: 2, from_id: 77 },
    ])
  } finally {
    db.close()
  }
})

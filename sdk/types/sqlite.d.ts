declare namespace inu {
  /**
   * The plugin's private sync SQLite database, see {@link sqlite.open}.
   * API shape is based on `better-sqlite3`.
   *
   * Values bind and read back as:
   * - `null` (and `undefined` when binding)
   * - `number`: whole numbers as `INTEGER`, others as `REAL`; booleans bind as `0`/`1`
   * - `bigint`: `INTEGER`. An `INTEGER` reads back as `bigint` only past `Number.MAX_SAFE_INTEGER`
   * - `string`: `TEXT`
   * - `Uint8Array`: `BLOB`
   *
   * SQL errors throw {@link PluginError} with code `sqlite` and {@link PluginError.errcode}.
   *
   * **Limits: 50 MB per install (unless widened via a grant), 16 MB per value, 1 MB of SQL per statement**
   *
   * @example
   * ```ts
   * const db = inu.sqlite.open()
   * db.exec('CREATE TABLE IF NOT EXISTS seen (id INTEGER PRIMARY KEY, at INTEGER)')
   * db.prepare('INSERT OR REPLACE INTO seen VALUES (?, ?)').run(42, Date.now())
   * ```
   *
   * @needs-grant sqlite
   * @experimental Subject to breaking changes between releases.
   */
  namespace sqlite {
    type Value = null | number | bigint | string | Uint8Array

    /**
     * Positional values in order, spread from arrays, plus at most one plain object naming `:x`, `@x` and `$x` as `x`
     *
     * @example
     * ```ts
     * db.prepare('SELECT * FROM t WHERE a = ? AND b = :b').all(1, { b: 2 })
     * ```
     */
    type Param = Value | boolean | undefined | readonly (Value | boolean | undefined)[] | Record<string, Value | boolean | undefined>

    interface RunResult {
      /** Rows changed by the most recent write on the database */
      changes: number
      /** Rowid of the most recent successful `INSERT` on the database */
      lastInsertRowid: number | bigint
    }

    interface Statement<Row = Record<string, Value>> {
      /** The SQL this statement was prepared from */
      readonly source: string
      /** Whether the statement returns rows. {@link get} and {@link all} throw on one that does not */
      readonly reader: boolean

      /** Run to completion, discarding any rows */
      run(...params: Param[]): RunResult
      /** The first row, or `undefined` when there is none */
      get(...params: Param[]): Row | undefined
      all(...params: Param[]): Row[]

      /** Return rows as arrays of column values instead of objects */
      raw(enable?: boolean): this
      /** Return only the first column of each row */
      pluck(enable?: boolean): this
    }

    interface OpenOptions {
      /** Use write-ahead logging: faster writes, at the cost of two more files beside the database. Defaults to `true` */
      wal?: boolean
    }

    /**
     * Open the plugin's database.
     *
     * Every handle shares one connection, and with it transactions and the journal mode,
     * which is the one the latest call asked for.
     */
    function open(options?: OpenOptions): Database

    interface Database {
      /** Prepare one statement. Prepared statements are cached, so preparing the same SQL again is cheap */
      prepare<Row = Record<string, Value>>(sql: string): Statement<Row>

      /** Run any number of `;`-separated statements, discarding their rows */
      exec(sql: string): void

      /**
       * Wrap `fn` so that each call runs in a transaction: committed when `fn` returns, rolled back when it throws.
       * Nested calls use savepoints.
       *
       * `fn` must be synchronous: returning a promise rolls back and throws `TypeError`.
       *
       * @example
       * ```ts
       * const insert = db.prepare('INSERT INTO cats (name) VALUES (?)')
       * const insertMany = db.transaction((names: string[]) => {
       *   for (const name of names) insert.run(name)
       * })
       * insertMany(['Joey', 'Sally'])
       * ```
       */
      transaction<A extends unknown[], R>(fn: (...args: A) => R): (...args: A) => R
    }
  }
}

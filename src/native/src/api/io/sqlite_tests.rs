use super::*;
use crate::api::io::fs::DEFAULT_QUOTA_BYTES;
use crate::sandbox::grants::CachedGrantHost;
use crate::sandbox::limits::arm;
use crate::testing::harness::{get_api_globals, TestDir};
use rquickjs::{Context, Runtime};

/// the context drops before its runtime, and the connection with it, before the directory goes
struct Fixture {
  ctx: Context,
  _rt: Runtime,
  dir: TestDir,
}

fn open_in(dir: TestDir, grants: &[&str], quota: u64) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  ctx.with(|ctx| {
    let globals = get_api_globals(&ctx);
    install_sqlite(&ctx, CachedGrantHost::new(grants), dir.path().join("db"), quota, &globals).unwrap();
  });
  Fixture { ctx, _rt: rt, dir }
}

fn open(name: &str) -> Fixture {
  open_in(TestDir::new(name), &["sqlite"], DEFAULT_QUOTA_BYTES)
}

impl Fixture {
  /// json, or `throws <code or name>: <message>`; an expression or statements that `return`, with `db` open
  fn eval(&self, code: &str) -> String {
    let body = if code.contains("return ") { code.to_string() } else { format!("return ({code})") };
    crate::testing::harness::eval_string(
      &self.ctx,
      &format!(
        r#"
          (() => {{
            const json = v => JSON.stringify(v, (_, x) => typeof x === 'bigint' ? `${{x}}n` : x instanceof Uint8Array ? [...x] : x)
            try {{ const db = inu.sqlite.open(); return json((() => {{ {body} }})()) ?? 'undefined' }}
            catch (e) {{ return `throws ${{e.code ?? e.name}}: ${{e.message}}` }}
          }})()
        "#,
      ),
    )
  }

  fn reopen(self) -> Fixture {
    let Fixture { ctx, _rt, dir } = self;
    drop(ctx);
    drop(_rt);
    open_in(dir, &["sqlite"], DEFAULT_QUOTA_BYTES)
  }
}

#[test]
fn values_come_back_as_the_types_they_went_in_as() {
  let db = open("types");
  db.eval("db.exec('CREATE TABLE t (v)')");
  assert_eq!(
    db.eval(
      r#"
        const insert = db.prepare('INSERT INTO t VALUES (?)')
        for (const v of [null, undefined, 42, 1.5, -0, 'text', new Uint8Array([1, 2]), 2n ** 60n, true, 2 ** 53 - 1]) insert.run(v)
        return db.prepare('SELECT v, typeof(v) AS type FROM t').all()
      "#
    ),
    concat!(
      r#"[{"v":null,"type":"null"},{"v":null,"type":"null"},{"v":42,"type":"integer"},{"v":1.5,"type":"real"},"#,
      r#"{"v":0,"type":"integer"},{"v":"text","type":"text"},{"v":[1,2],"type":"blob"},"#,
      r#"{"v":"1152921504606846976n","type":"integer"},{"v":1,"type":"integer"},{"v":9007199254740991,"type":"integer"}]"#,
    ),
  );
}

#[test]
fn a_value_that_has_no_sql_type_is_refused() {
  let db = open("bad-values");
  assert_eq!(
    db.eval("db.prepare('SELECT ?').get(new Date(0))"),
    "throws TypeError: sqlite: cannot bind a object, expected null, a number, a bigint, a string or a Uint8Array",
  );
  assert_eq!(
    db.eval("db.prepare('SELECT ?').get(2n ** 64n)"),
    "throws RangeError: sqlite: 18446744073709551616 does not fit in 64 bits",
  );
}

#[test]
fn parameters_bind_by_position_array_or_name() {
  let db = open("params");
  assert_eq!(db.eval("db.prepare('SELECT ? AS a, ? AS b').get(1, 2)"), r#"{"a":1,"b":2}"#);
  assert_eq!(db.eval("db.prepare('SELECT ? AS a, ? AS b').get([1, 2])"), r#"{"a":1,"b":2}"#);
  assert_eq!(
    db.eval("db.prepare('SELECT :a AS a, @b AS b, $c AS c, ? AS d').get({ a: 1, b: 2, c: 3 }, 4)"),
    r#"{"a":1,"b":2,"c":3,"d":4}"#,
  );
  assert_eq!(
    db.eval("db.prepare('SELECT :a AS a').get(Object.assign(Object.create(null), { a: 1 }))"),
    r#"{"a":1}"#
  );
  assert_eq!(db.eval("db.prepare('SELECT :a').get({})"), "throws RangeError: sqlite: missing named parameter 'a'");
  assert_eq!(db.eval("db.prepare('SELECT ?, ?').get(1)"), "throws RangeError: sqlite: too few parameter values");
  assert_eq!(db.eval("db.prepare('SELECT ?').get(1, 2)"), "throws RangeError: sqlite: too many parameter values");
}

#[test]
fn a_getter_running_the_same_statement_while_its_parameters_are_read_is_fine() {
  let db = open("reentry");
  assert_eq!(
    db.eval(
      r#"
        const stmt = db.prepare('SELECT :a AS a')
        return stmt.get({ get a() { return stmt.get({ a: 1 }).a + 1 } })
      "#
    ),
    r#"{"a":2}"#,
  );
}

#[test]
fn run_get_and_all_report_what_sqlite_did() {
  let db = open("run-get-all");
  db.eval("db.exec('CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT); CREATE TABLE u (x)')");
  assert_eq!(
    db.eval("db.prepare('INSERT INTO t (name) VALUES (?), (?)').run('a', 'b')"),
    r#"{"changes":2,"lastInsertRowid":2}"#,
  );
  assert_eq!(db.eval("db.prepare('SELECT * FROM t ORDER BY id').get()"), r#"{"id":1,"name":"a"}"#);
  assert_eq!(db.eval("db.prepare('SELECT * FROM t WHERE id = 9').get()"), "undefined");
  assert_eq!(db.eval("db.prepare('SELECT name FROM t ORDER BY id').all()"), r#"[{"name":"a"},{"name":"b"}]"#);
  assert_eq!(
    db.eval("db.prepare('SELECT 1').run()"),
    r#"{"changes":2,"lastInsertRowid":2}"#,
    "a read leaves the counters where the last write put them",
  );
  assert_eq!(
    db.eval("db.prepare('DELETE FROM t').all()"),
    "throws TypeError: sqlite: all() needs a statement that returns rows, use run()",
  );
  let stmt = "const s = db.prepare('SELECT id, name FROM t ORDER BY id')";
  assert_eq!(
    db.eval(&format!("{stmt}; return [s.source, s.reader]")),
    r#"["SELECT id, name FROM t ORDER BY id",true]"#
  );
}

#[test]
fn raw_and_pluck_reshape_rows_until_turned_off() {
  let db = open("shapes");
  db.eval("db.exec(\"CREATE TABLE t (a, b); INSERT INTO t VALUES (1, 'x'), (2, 'y')\")");
  assert_eq!(
    db.eval(
      r#"
        const s = db.prepare('SELECT a, b FROM t ORDER BY a')
        return [s.raw().all(), s.pluck().all(), s.raw(false).all(), s.pluck(false).all()]
      "#
    ),
    r#"[[[1,"x"],[2,"y"]],[1,2],[1,2],[{"a":1,"b":"x"},{"a":2,"b":"y"}]]"#,
  );
}

#[test]
fn a_column_named_like_an_inherited_setter_is_an_own_property() {
  let db = open("define");
  assert_eq!(
    db.eval(
      r#"
        let hit = false
        Object.defineProperty(Object.prototype, 'trap', { set() { hit = true }, configurable: true })
        const row = db.prepare("SELECT 1 AS trap, 2 AS __proto__").get()
        delete Object.prototype.trap
        return [hit, Object.keys(row), row.trap]
      "#
    ),
    r#"[false,["trap","__proto__"],1]"#,
  );
}

#[test]
fn prepare_takes_exactly_one_statement() {
  let db = open("one-statement");
  assert_eq!(
    db.eval("db.prepare('SELECT 1; SELECT 2')"),
    "throws TypeError: sqlite: prepare() takes one statement, exec() takes several",
  );
  assert_eq!(db.eval("db.prepare(' -- nothing')"), "throws TypeError: sqlite: the SQL holds no statement");
  assert_eq!(db.eval("db.exec('SELECT 1; SELECT 2;')"), "undefined");
}

#[test]
fn an_sql_error_carries_sqlite_s_extended_code() {
  let db = open("errors");
  db.eval("db.exec('CREATE TABLE t (x UNIQUE); INSERT INTO t VALUES (1)')");
  assert_eq!(
    db.eval(
      r#"
        try { db.exec('INSERT INTO t VALUES (1)') }
        catch (e) { return [e instanceof inu.PluginError, e.code, e.errcode, e.errstr, e.message] }
      "#
    ),
    r#"[true,"sqlite",2067,"constraint failed","sqlite: UNIQUE constraint failed: t.x"]"#,
  );
  assert_eq!(db.eval("db.prepare('SELEKT 1')"), r#"throws sqlite: sqlite: near "SELEKT": syntax error"#);
}

#[test]
fn a_transaction_commits_or_rolls_back_as_a_whole() {
  let db = open("transaction");
  db.eval("db.exec('CREATE TABLE t (x)')");
  assert_eq!(
    db.eval(
      r#"
        const insert = db.prepare('INSERT INTO t VALUES (?)')
        const count = () => db.prepare('SELECT count(*) FROM t').pluck().get()
        const many = db.transaction(function (...xs) { for (const x of xs) insert.run(x); return [this, xs.length] })
        const result = many.call('self', 1, 2)
        let failed
        try { db.transaction(() => { insert.run(3); throw new Error('boom') })() } catch (e) { failed = e.message }
        return [result, failed, count()]
      "#
    ),
    r#"[["self",2],"boom",2]"#,
  );
}

#[test]
fn a_nested_transaction_rolls_back_alone() {
  let db = open("nested");
  db.eval("db.exec('CREATE TABLE t (x)')");
  assert_eq!(
    db.eval(
      r#"
        const insert = db.prepare('INSERT INTO t VALUES (?)')
        db.transaction(() => {
          insert.run(1)
          try { db.transaction(() => { insert.run(2); throw 0 })() } catch {}
          db.transaction(() => insert.run(3))()
        })()
        return db.prepare('SELECT x FROM t ORDER BY x').pluck().all()
      "#
    ),
    "[1,3]",
  );
}

#[test]
fn an_async_transaction_function_is_rolled_back_and_refused() {
  let db = open("async");
  db.eval("db.exec('CREATE TABLE t (x)')");
  assert_eq!(
    db.eval("db.transaction(async () => db.exec('INSERT INTO t VALUES (1)'))()"),
    "throws TypeError: Database.transaction: the function must not return a promise",
  );
  assert_eq!(db.eval("db.prepare('SELECT count(*) FROM t').pluck().get()"), "0");
  assert_eq!(db.eval("db.transaction(1)"), "throws TypeError: Database.transaction: expected a function");
}

#[test]
fn a_transaction_the_function_ended_itself_is_left_alone() {
  let db = open("self-commit");
  db.eval("db.exec('CREATE TABLE t (x)')");
  assert_eq!(
    db.eval(
      r#"
        db.transaction(() => db.exec('INSERT INTO t VALUES (1); COMMIT'))()
        return db.prepare('SELECT count(*) FROM t').pluck().get()
      "#
    ),
    "1",
  );
}

#[test]
fn nothing_outside_the_database_file_can_be_attached() {
  let db = open("attach");
  let outside = db.dir.path().join("outside.db");
  for sql in [
    format!("ATTACH '{}' AS other", outside.display()),
    format!("ATTACH ('{}' || '') AS other", outside.display()),
    format!("VACUUM INTO '{}'", outside.display()),
  ] {
    assert!(db.eval(&format!("db.exec({sql:?})")).starts_with("throws forbidden"), "{sql}");
  }
  assert!(!outside.exists());
  assert_eq!(db.eval("db.exec('CREATE TABLE t (x); VACUUM')"), "undefined");
}

#[test]
fn only_pragmas_that_stay_inside_the_connection_can_be_set() {
  let db = open("pragmas");
  assert_eq!(
    db.eval("db.exec('PRAGMA user_version = 7'); return db.prepare('PRAGMA user_version').pluck().get()"),
    "7"
  );
  assert_eq!(db.eval("db.prepare('PRAGMA table_info(sqlite_schema)').all().length"), "5");
  assert_eq!(db.eval("typeof db.prepare('PRAGMA page_size').pluck().get()"), r#""number""#);
  for sql in [
    "PRAGMA max_page_count = 999999999",
    "PRAGMA MAX_PAGE_COUNT(999999999)",
    "PRAGMA main.page_size = 65536",
    "PRAGMA journal_mode = OFF",
    "PRAGMA journal_mode = MEMORY",
    "PRAGMA cache_size = -1000000",
    "PRAGMA temp_store_directory = '/tmp'",
    "PRAGMA hard_heap_limit = 1",
  ] {
    assert!(db.eval(&format!("db.exec({sql:?})")).starts_with("throws forbidden"), "{sql}");
  }
  db.eval("db.exec('SELECT * FROM pragma_max_page_count(999999999)')");
  assert_eq!(
    db.eval("db.prepare('PRAGMA max_page_count').pluck().get()"),
    (DEFAULT_QUOTA_BYTES / 4096).to_string(),
  );
}

#[test]
fn the_database_cannot_grow_past_its_quota() {
  let db = open_in(TestDir::new("quota"), &["sqlite"], 64 * 1024);
  db.eval("db.exec('CREATE TABLE t (x)')");
  let thrown = db.eval(
    r#"
      try { db.prepare('INSERT INTO t VALUES (?)').run(new Uint8Array(128 * 1024)) }
      catch (e) { return [e.code, e.quota, e.usage <= e.quota] }
    "#,
  );
  assert_eq!(thrown, r#"["quota-exceeded",65536,true]"#);
  assert_eq!(db.eval("db.prepare('INSERT INTO t VALUES (?)').run(new Uint8Array(1024)).changes"), "1");
}

#[test]
fn a_query_stops_at_the_callers_deadline() {
  let db = open("deadline");
  let _deadline = arm(50);
  let started = std::time::Instant::now();
  let thrown =
    db.eval("db.prepare('WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT max(x) FROM c').get()");
  assert_eq!(thrown, "throws sqlite: sqlite: interrupted");
  assert!(started.elapsed() < std::time::Duration::from_secs(2));
}

#[test]
fn data_outlives_the_engine() {
  let db = open("persist");
  db.eval("db.exec(\"CREATE TABLE t (x); INSERT INTO t VALUES ('kept')\")");
  let db = db.reopen();
  assert_eq!(db.eval("db.prepare('SELECT x FROM t').pluck().get()"), r#""kept""#);
}

#[test]
fn every_call_needs_the_grant() {
  let db = open_in(TestDir::new("ungranted"), &[], DEFAULT_QUOTA_BYTES);
  assert!(db.eval("db.exec('SELECT 1')").starts_with("throws not-granted"));
  assert!(db.dir.entries().is_empty(), "nothing is opened without the grant");
}

#[test]
fn a_database_opens_in_wal_mode_unless_asked_not_to() {
  let db = open("journal");
  assert_eq!(db.eval("db.prepare('PRAGMA journal_mode').pluck().get()"), r#""wal""#);
  db.eval("db.exec('CREATE TABLE t (x); INSERT INTO t VALUES (1)')");
  assert!(db.dir.path().join("db-wal").exists());
  assert_eq!(
    db.eval("inu.sqlite.open({ wal: false }).prepare('PRAGMA journal_mode').pluck().get()"),
    r#""delete""#
  );
  assert!(!db.dir.path().join("db-wal").exists(), "leaving wal checkpoints and removes the log");
  assert_eq!(db.eval("db.exec('PRAGMA journal_mode = delete; PRAGMA journal_mode = WAL')"), "undefined");
}

#[test]
fn every_handle_shares_one_connection() {
  let db = open("handles");
  assert_eq!(
    db.eval(
      r#"
        const other = inu.sqlite.open()
        db.exec('CREATE TABLE t (x)')
        return db.transaction(() => {
          other.exec('INSERT INTO t VALUES (1)')
          return other.prepare('SELECT count(*) FROM t').pluck().get()
        })()
      "#
    ),
    "1",
  );
}

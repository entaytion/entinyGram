use std::cell::{Cell, OnceCell};
use std::path::PathBuf;
use std::rc::Rc;

use rquickjs::class::Trace;
use rquickjs::function::{Opt, Rest, This};
use rquickjs::object::Property;
use rquickjs::{
  Array, BigInt, Class, Coerced, Ctx, Exception, Function, JsLifetime, Object, Result as JsResult, TypedArray, Value,
};
use rusqlite::config::DbConfig;
use rusqlite::hooks::{AuthAction, AuthContext, Authorization};
use rusqlite::limits::Limit;
use rusqlite::types::{Value as SqlValue, ValueRef};
use rusqlite::{ffi, Connection, ErrorCode, OpenFlags, Row};

use crate::api::error::{make_plugin_error, PluginErrorCode};
use crate::api::Globals;
use crate::sandbox::grants::{GrantHost, MATCH_EXACT};
use crate::sandbox::limits::is_past_deadline;
use crate::utils::qjs::{qjs_load_prelude, qjs_read_typed_bytes};
use crate::utils::shape::{define_method, get_class_prototype};

const PRELUDE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/sqlite.qbc"));

const MAX_VALUE_BYTES: i32 = 16 * 1024 * 1024;

const MAX_SQL_BYTES: i32 = 1024 * 1024;

const PROGRESS_STEP_OPS: i32 = 1000;

const MAX_SAFE_INTEGER: u64 = (1 << 53) - 1;

/// what a WAL is cut back to after a checkpoint: past the quota, it is the one file that can outgrow it
const MAX_WAL_BYTES: i64 = 4 * 1024 * 1024;

/// with any argument: none reaches past this connection or the quota
const FREE_PRAGMAS: &[&str] = &[
  "application_id",
  "auto_vacuum",
  "automatic_index",
  "case_sensitive_like",
  "cell_size_check",
  "defer_foreign_keys",
  "foreign_key_check",
  "foreign_key_list",
  "foreign_keys",
  "ignore_check_constraints",
  "incremental_vacuum",
  "index_info",
  "index_list",
  "index_xinfo",
  "integrity_check",
  "optimize",
  "quick_check",
  "recursive_triggers",
  "reverse_unordered_selects",
  "secure_delete",
  "synchronous",
  "table_info",
  "table_list",
  "table_xinfo",
  "user_version",
];

/// read-only: the quota rests on `page_size` and `max_page_count`, and the rest are memory or process-wide
const QUERY_PRAGMAS: &[&str] = &[
  "cache_size",
  "collation_list",
  "compile_options",
  "data_version",
  "encoding",
  "freelist_count",
  "function_list",
  "journal_mode",
  "max_page_count",
  "page_count",
  "page_size",
  "pragma_list",
  "schema_version",
  "temp_store",
];

fn authorize(context: AuthContext<'_>) -> Authorization {
  let allowed = match context.action {
    // VACUUM attaches its scratch copy as ''; any other name is a file outside the sandbox
    AuthAction::Attach { filename } => filename.is_empty(),
    AuthAction::Unknown { code, .. } => code != ffi::SQLITE_ATTACH,
    AuthAction::Pragma { pragma_name, pragma_value } => {
      let name = pragma_name.to_ascii_lowercase();
      FREE_PRAGMAS.contains(&name.as_str())
        || match pragma_value {
          None => QUERY_PRAGMAS.contains(&name.as_str()),
          // the two modes whose side files the host wipes
          Some(mode) => name == "journal_mode" && ["wal", "delete"].contains(&mode.to_ascii_lowercase().as_str()),
        }
    }
    _ => true,
  };
  if allowed {
    Authorization::Allow
  } else {
    Authorization::Deny
  }
}

struct SqliteState {
  grants: Rc<dyn GrantHost>,
  path: PathBuf,
  quota: u64,
  connection: OnceCell<Connection>,
}

impl SqliteState {
  fn open(&self) -> rusqlite::Result<Connection> {
    let flags = OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_CREATE | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let connection = Connection::open_with_flags(&self.path, flags)?;
    connection.set_db_config(DbConfig::SQLITE_DBCONFIG_DEFENSIVE, true)?;
    connection.set_db_config(DbConfig::SQLITE_DBCONFIG_TRUSTED_SCHEMA, false)?;
    connection.set_limit(Limit::SQLITE_LIMIT_LENGTH, MAX_VALUE_BYTES)?;
    connection.set_limit(Limit::SQLITE_LIMIT_SQL_LENGTH, MAX_SQL_BYTES)?;
    let page_size: i64 = connection.query_row("PRAGMA page_size", [], |row| row.get(0))?;
    let max_pages = (self.quota / page_size.max(1) as u64).max(1);
    // the app's sqlite keeps temp files on disk by default, and android gives it no temp directory
    connection.execute_batch(&format!(
      "PRAGMA temp_store = MEMORY; PRAGMA max_page_count = {max_pages}; PRAGMA journal_size_limit = {MAX_WAL_BYTES}"
    ))?;
    connection.authorizer(Some(authorize))?;
    connection.progress_handler(PROGRESS_STEP_OPS, Some(is_past_deadline))?;
    Ok(connection)
  }

  /// Opens on first use to avoid startup I/O for plugins that never touch it.
  fn get_connection(&self, ctx: &Ctx<'_>) -> JsResult<&Connection> {
    self.grants.check_grant(ctx, "sqlite", None, MATCH_EXACT)?;
    if let Some(connection) = self.connection.get() {
      return Ok(connection);
    }
    let connection = self.open().or_else(|e| self.throw(ctx, e))?;
    Ok(self.connection.get_or_init(|| connection))
  }

  fn get_usage(&self) -> Option<u64> {
    let connection = self.connection.get()?;
    let read = |pragma| connection.query_row(pragma, [], |row| row.get::<_, i64>(0)).ok();
    Some((read("PRAGMA page_count")? * read("PRAGMA page_size")?) as u64)
  }

  fn throw<'js, T>(&self, ctx: &Ctx<'js>, error: rusqlite::Error) -> JsResult<T> {
    let (failure, message) = match error {
      rusqlite::Error::SqliteFailure(failure, message) => (failure, message),
      rusqlite::Error::MultipleStatement => {
        return Err(Exception::throw_type(ctx, "sqlite: prepare() takes one statement, exec() takes several"))
      }
      other => return PluginErrorCode::Internal.throw(ctx, &format!("sqlite: {other}")),
    };
    let errstr = ffi::code_to_str(failure.extended_code);
    let message = format!("sqlite: {}", message.as_deref().unwrap_or(errstr));
    match failure.code {
      ErrorCode::AuthorizationForStatementDenied => {
        PluginErrorCode::Forbidden.throw(ctx, &format!("{message}: ATTACH and most pragmas are refused"))
      }
      ErrorCode::DiskFull => {
        let usage = self.get_usage().unwrap_or(self.quota);
        PluginErrorCode::QuotaExceeded(
          i64::try_from(usage).unwrap_or(i64::MAX),
          i64::try_from(self.quota).unwrap_or(i64::MAX),
        )
        .throw(ctx, &message)
      }
      _ => {
        let error = make_plugin_error(ctx, "sqlite", &message, None, None, None)?;
        if let Some(object) = error.as_object() {
          object.set("errcode", failure.extended_code)?;
          object.set("errstr", errstr)?;
        }
        Err(ctx.throw(error))
      }
    }
  }

  fn run_transaction<'js>(
    &self,
    ctx: &Ctx<'js>,
    f: Function<'js>,
    this: Value<'js>,
    args: Vec<Value<'js>>,
  ) -> JsResult<Value<'js>> {
    let connection = self.get_connection(ctx)?;
    let (begin, commit, rollback) = if connection.is_autocommit() {
      ("BEGIN", "COMMIT", "ROLLBACK")
    } else {
      (
        "SAVEPOINT inu_transaction",
        "RELEASE inu_transaction",
        "ROLLBACK TO inu_transaction; RELEASE inu_transaction",
      )
    };
    connection.execute_batch(begin).or_else(|e| self.throw(ctx, e))?;
    // the function may have ended the transaction itself
    let roll_back = || {
      if !connection.is_autocommit() {
        let _ = connection.execute_batch(rollback);
      }
    };
    let result = match f.call::<_, Value>((This(this), Rest(args))) {
      Ok(value) if value.is_promise() => {
        Err(Exception::throw_type(ctx, "Database.transaction: the function must not return a promise"))
      }
      result => result,
    };
    if result.is_err() {
      roll_back();
      return result;
    }
    if !connection.is_autocommit() {
      if let Err(e) = connection.execute_batch(commit) {
        roll_back();
        return self.throw(ctx, e);
      }
    }
    result
  }
}

#[derive(Clone, Copy, PartialEq)]
enum RowShape {
  Object,
  Array,
  FirstColumn,
}

#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "Statement", frozen)]
pub struct Statement {
  #[qjs(skip_trace)]
  state: Rc<SqliteState>,
  #[qjs(skip_trace)]
  sql: String,
  /// each parameter's name, `None` for a bare `?`
  #[qjs(skip_trace)]
  params: Vec<Option<String>>,
  #[qjs(skip_trace)]
  reader: bool,
  #[qjs(skip_trace)]
  shape: Cell<RowShape>,
}

impl Statement {
  /// Reads every parameter before the statement is touched: a getter or a proxy is plugin code,
  /// which can run this statement again.
  fn step<'js>(
    &self,
    ctx: &Ctx<'js>,
    params: Vec<Value<'js>>,
    mut on_row: impl FnMut(&Row<'_>) -> JsResult<bool>,
  ) -> JsResult<()> {
    let values = read_params(ctx, &self.params, params)?;
    let connection = self.state.get_connection(ctx)?;
    let mut statement = connection.prepare_cached(&self.sql).or_else(|e| self.state.throw(ctx, e))?;
    for (index, value) in values.iter().enumerate() {
      statement.raw_bind_parameter(index + 1, value).or_else(|e| self.state.throw(ctx, e))?;
    }
    let mut rows = statement.raw_query();
    while let Some(row) = rows.next().or_else(|e| self.state.throw(ctx, e))? {
      if !on_row(row)? {
        break;
      }
    }
    Ok(())
  }

  fn require_reader(&self, ctx: &Ctx<'_>, method: &str) -> JsResult<()> {
    if self.reader {
      return Ok(());
    }
    Err(Exception::throw_type(
      ctx,
      &format!("sqlite: {method}() needs a statement that returns rows, use run()"),
    ))
  }

  fn read_column<'js>(&self, ctx: &Ctx<'js>, row: &Row<'_>, index: usize) -> JsResult<Value<'js>> {
    Ok(match row.get_ref(index).or_else(|e| self.state.throw(ctx, e))? {
      ValueRef::Null => Value::new_null(ctx.clone()),
      ValueRef::Integer(int) => read_integer(ctx, int)?,
      ValueRef::Real(real) => Value::new_float(ctx.clone(), real),
      ValueRef::Text(text) => rquickjs::String::from_str(ctx.clone(), &String::from_utf8_lossy(text))?.into_value(),
      ValueRef::Blob(blob) => TypedArray::<u8>::new_copy(ctx.clone(), blob)?.into_value(),
    })
  }

  /// Defines rather than assigns each column, so a setter up the prototype chain never runs.
  fn read_row<'js>(&self, ctx: &Ctx<'js>, row: &Row<'_>) -> JsResult<Value<'js>> {
    let statement = row.as_ref();
    let count = statement.column_count();
    match self.shape.get() {
      RowShape::FirstColumn => self.read_column(ctx, row, 0),
      RowShape::Array => {
        let array = Array::new(ctx.clone())?;
        for index in 0..count {
          array.set(index, self.read_column(ctx, row, index)?)?;
        }
        Ok(array.into_value())
      }
      RowShape::Object => {
        let object = Object::new(ctx.clone())?;
        for index in 0..count {
          let name = statement.column_name(index).or_else(|e| self.state.throw(ctx, e))?;
          let value = self.read_column(ctx, row, index)?;
          object.prop(name, Property::from(value).writable().enumerable().configurable())?;
        }
        Ok(object.into_value())
      }
    }
  }

  fn set_shape(&self, shape: RowShape, enable: Opt<bool>) {
    if enable.0.unwrap_or(true) {
      self.shape.set(shape);
    } else if self.shape.get() == shape {
      self.shape.set(RowShape::Object);
    }
  }
}

#[rquickjs::methods]
impl<'js> Statement {
  #[qjs(get, enumerable, configurable)]
  fn source(&self) -> String {
    self.sql.clone()
  }

  #[qjs(get, enumerable, configurable)]
  fn reader(&self) -> bool {
    self.reader
  }

  fn run(&self, ctx: Ctx<'js>, params: Rest<Value<'js>>) -> JsResult<Object<'js>> {
    self.step(&ctx, params.0, |_| Ok(true))?;
    let connection = self.state.get_connection(&ctx)?;
    let result = Object::new(ctx.clone())?;
    result.set("changes", connection.changes() as f64)?;
    result.set("lastInsertRowid", read_integer(&ctx, connection.last_insert_rowid())?)?;
    Ok(result)
  }

  fn get(&self, ctx: Ctx<'js>, params: Rest<Value<'js>>) -> JsResult<Value<'js>> {
    self.require_reader(&ctx, "get")?;
    let mut found = Value::new_undefined(ctx.clone());
    self.step(&ctx, params.0, |row| {
      found = self.read_row(&ctx, row)?;
      Ok(false)
    })?;
    Ok(found)
  }

  fn all(&self, ctx: Ctx<'js>, params: Rest<Value<'js>>) -> JsResult<Array<'js>> {
    self.require_reader(&ctx, "all")?;
    let rows = Array::new(ctx.clone())?;
    let mut len = 0;
    self.step(&ctx, params.0, |row| {
      rows.set(len, self.read_row(&ctx, row)?)?;
      len += 1;
      Ok(true)
    })?;
    Ok(rows)
  }

  fn raw(&self, this: This<Class<'js, Statement>>, enable: Opt<bool>) -> Class<'js, Statement> {
    self.set_shape(RowShape::Array, enable);
    this.0
  }

  fn pluck(&self, this: This<Class<'js, Statement>>, enable: Opt<bool>) -> Class<'js, Statement> {
    self.set_shape(RowShape::FirstColumn, enable);
    this.0
  }
}

/// `number` while exact, `bigint` past `Number.MAX_SAFE_INTEGER`
fn read_integer<'js>(ctx: &Ctx<'js>, int: i64) -> JsResult<Value<'js>> {
  if int.unsigned_abs() <= MAX_SAFE_INTEGER {
    return Ok(Value::new_number(ctx.clone(), int as f64));
  }
  Ok(BigInt::from_i64(ctx.clone(), int)?.into_value())
}

/// an object literal or `Object.create(null)`: anything else is a value or a mistake
fn is_plain_object(value: &Value<'_>) -> bool {
  let Some(object) = value.as_object() else {
    return false;
  };
  if value.is_array() || value.is_function() {
    return false;
  }
  object.get_prototype().is_none_or(|proto| proto.get_prototype().is_none())
}

/// better-sqlite3's binding: arrays spread into positional values, and one plain object names
/// `:x`, `@x` and `$x` by `x`
fn read_params<'js>(ctx: &Ctx<'js>, slots: &[Option<String>], args: Vec<Value<'js>>) -> JsResult<Vec<SqlValue>> {
  let mut positional = Vec::new();
  let mut named: Option<Object<'js>> = None;
  for arg in args {
    if let Some(array) = arg.as_array() {
      for item in array.iter::<Value>() {
        positional.push(item?);
      }
    } else if is_plain_object(&arg) {
      if named.replace(arg.into_object().expect("checked above")).is_some() {
        return Err(Exception::throw_type(ctx, "sqlite: named parameters go in a single object"));
      }
    } else {
      positional.push(arg);
    }
  }
  let mut positional = positional.into_iter();
  let mut values = Vec::with_capacity(slots.len());
  for slot in slots {
    let value = match slot.as_deref() {
      Some(name) if !name.starts_with('?') => {
        let key = &name[1..];
        match &named {
          Some(object) if object.contains_key(key)? => object.get(key)?,
          _ => return Err(Exception::throw_range(ctx, &format!("sqlite: missing named parameter '{key}'"))),
        }
      }
      _ => positional.next().ok_or_else(|| Exception::throw_range(ctx, "sqlite: too few parameter values"))?,
    };
    values.push(to_sql(ctx, &value)?);
  }
  if positional.next().is_some() {
    return Err(Exception::throw_range(ctx, "sqlite: too many parameter values"));
  }
  Ok(values)
}

fn to_sql<'js>(ctx: &Ctx<'js>, value: &Value<'js>) -> JsResult<SqlValue> {
  if value.is_null() || value.is_undefined() {
    return Ok(SqlValue::Null);
  }
  if let Some(flag) = value.as_bool() {
    return Ok(SqlValue::Integer(flag.into()));
  }
  if let Some(int) = value.as_int() {
    return Ok(SqlValue::Integer(int.into()));
  }
  if let Some(real) = value.as_float() {
    let integral = real.fract() == 0.0 && real.abs() <= MAX_SAFE_INTEGER as f64;
    return Ok(if integral { SqlValue::Integer(real as i64) } else { SqlValue::Real(real) });
  }
  if let Some(text) = value.as_string() {
    return Ok(SqlValue::Text(text.to_string()?));
  }
  if value.is_big_int() {
    let digits = value.get::<Coerced<String>>()?.0;
    return digits
      .parse()
      .map(SqlValue::Integer)
      .map_err(|_| Exception::throw_range(ctx, &format!("sqlite: {digits} does not fit in 64 bits")));
  }
  if let Ok(array) = TypedArray::<u8>::from_value(value.clone()) {
    return qjs_read_typed_bytes(&array, |bytes| SqlValue::Blob(bytes.to_vec()))
      .ok_or_else(|| Exception::throw_type(ctx, "sqlite: the array is detached"));
  }
  Err(Exception::throw_type(
    ctx,
    &format!(
      "sqlite: cannot bind a {}, expected null, a number, a bigint, a string or a Uint8Array",
      value.type_name()
    ),
  ))
}

#[derive(Trace, JsLifetime)]
#[rquickjs::class(rename = "Database", frozen)]
pub struct Database {
  #[qjs(skip_trace)]
  state: Rc<SqliteState>,
}

#[rquickjs::methods]
impl<'js> Database {
  fn prepare(&self, ctx: Ctx<'js>, sql: String) -> JsResult<Class<'js, Statement>> {
    let connection = self.state.get_connection(&ctx)?;
    let statement = connection.prepare_cached(&sql).or_else(|e| self.state.throw(&ctx, e))?;
    // sqlite has no statement for SQL that is only whitespace and comments
    if statement.expanded_sql().is_none() {
      return Err(Exception::throw_type(&ctx, "sqlite: the SQL holds no statement"));
    }
    let params = (1..=statement.parameter_count())
      .map(|index| statement.parameter_name(index).map(str::to_string))
      .collect();
    let reader = statement.column_count() > 0;
    drop(statement);
    Class::instance(
      ctx.clone(),
      Statement {
        state: self.state.clone(),
        sql,
        params,
        reader,
        shape: Cell::new(RowShape::Object),
      },
    )
  }

  fn exec(&self, ctx: Ctx<'js>, sql: String) -> JsResult<()> {
    let connection = self.state.get_connection(&ctx)?;
    connection.execute_batch(&sql).or_else(|e| self.state.throw(&ctx, e))
  }
}

pub fn install_sqlite<'js>(
  ctx: &Ctx<'js>,
  grants: Rc<dyn GrantHost>,
  path: PathBuf,
  quota: u64,
  globals: &Globals<'js>,
) -> JsResult<()> {
  let state = Rc::new(SqliteState {
    grants,
    path,
    quota,
    connection: OnceCell::new(),
  });

  let run = Function::new(
    ctx.clone(),
    |ctx: Ctx<'js>,
     database: Class<'js, Database>,
     f: Function<'js>,
     this: Value<'js>,
     args: Vec<Value<'js>>|
     -> JsResult<Value<'js>> {
      let state = database.borrow().state.clone();
      state.run_transaction(&ctx, f, this, args)
    },
  )?;
  let transaction: Function = qjs_load_prelude(ctx, PRELUDE)?.call((run,))?;
  define_method(&get_class_prototype::<Database>(ctx)?, "transaction", transaction)?;

  let namespace = Object::new(ctx.clone())?;
  set_fn!(namespace, "open", ctx, state, move |ctx: Ctx<'js>,
                                               options: Opt<Value<'js>>|
        -> JsResult<Class<'js, Database>> {
    let wal = match options.0.as_ref().and_then(Value::as_object) {
      Some(options) => options.get::<_, Option<bool>>("wal")?.unwrap_or(true),
      None => true,
    };
    let connection = state.get_connection(&ctx)?;
    let mode = if wal { "WAL" } else { "DELETE" };
    connection
      .execute_batch(&format!("PRAGMA journal_mode = {mode}"))
      .or_else(|e| state.throw(&ctx, e))?;
    Class::instance(ctx.clone(), Database { state: state.clone() })
  });
  globals.inu.set("sqlite", namespace)?;
  Ok(())
}

#[cfg(test)]
#[path = "sqlite_tests.rs"]
mod tests;

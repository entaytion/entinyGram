use crate::runtime::Dispose;
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use rquickjs::{Array, Ctx, Exception, FromJs, Function, Object, Persistent, Result as JsResult, Value};

use crate::api::error::{call_callback, describe_js_error, format_exception, format_thrown};
use crate::api::platform::jvm::JvmState;
use crate::api::telegram::account::AccountState;
use crate::api::tl::proxy::json_parse_tl;
use crate::api::ui::icons::{icon_from_value, Icon};
use crate::runtime::enter_js;
use crate::runtime::pump_jobs;
use crate::sandbox::registry::{make_disposer, noop_disposer, Lifecycle, Registry};
use crate::utils::arguments::{field, opt_fn, req_fn, req_str, stringify_json};
use crate::utils::qjs::resolve_and_then;

pub const KIND_GLOBAL: i32 = 0;
pub const KIND_CHAT: i32 = 1;
pub const KIND_MESSAGE: i32 = 2;
pub const KIND_PROFILE: i32 = 3;

pub const MESSAGE_PLACEMENT_BUBBLE: i32 = 1;
pub const MESSAGE_PLACEMENT_SELECTION: i32 = 2;
pub const MESSAGE_PLACEMENT_SHARED_MEDIA: i32 = 4;
const ALL_PLACEMENTS: i32 = -1;

pub const DYNAMIC_TEXT: i32 = 1;
pub const DYNAMIC_ICON: i32 = 2;
pub const DYNAMIC_VISIBLE: i32 = 4;
pub const HAS_SECONDARY_CALLBACK: i32 = 8;

const KIND_COUNT: usize = 4;

fn kind_name(kind: i32) -> &'static str {
  match kind {
    KIND_CHAT => "registerChatAction",
    KIND_MESSAGE => "registerMessageAction",
    KIND_PROFILE => "registerProfileAction",
    _ => "registerGlobalAction",
  }
}

pub trait ActionHost {
  fn action_register(
    &self,
    kind: i32,
    token: u32,
    id: &str,
    placements: i32,
    text: Option<&str>,
    icon: Option<&str>,
    dynamic_fields: i32,
  ) -> Option<String>;
  fn action_unregister(&self, kind: i32, token: u32);
  /// a json array of `{ token, text, icon? }`, or `None` when the render failed as a whole
  fn actions_rendered(&self, render_id: i64, json: Option<&str>);
}

enum Label {
  Static(String),
  Dynamic(Persistent<Function<'static>>),
}

enum ActionIcon {
  Static { spec: String, _retained: Option<Persistent<Value<'static>>> },
  Dynamic(Persistent<Function<'static>>),
}

struct ActionDef {
  token: u32,
  placements: i32,
  label: Label,
  icon: Option<ActionIcon>,
  retained_icons: RefCell<Vec<Persistent<Value<'static>>>>,
  visible: Option<Persistent<Function<'static>>>,
  callback: Persistent<Function<'static>>,
  secondary_callback: Option<Persistent<Function<'static>>>,
}

#[derive(Clone, Copy)]
enum Getter {
  Visible,
  Text,
  Icon,
}

struct RenderRow {
  token: u32,
  text: Option<String>,
  icon: Option<String>,
  dropped: bool,
  pending: usize,
}

struct Render {
  id: i64,
  kind: i32,
  context: Persistent<Value<'static>>,
  rows: RefCell<Vec<RenderRow>>,
  started: Cell<bool>,
}

pub struct ActionState {
  host: Rc<dyn ActionHost>,
  lifecycle: Rc<Lifecycle>,
  log: crate::Log,
  accounts: Option<Rc<AccountState>>,
  jvm: Option<Rc<JvmState>>,
  kinds: Vec<Registry<Rc<ActionDef>>>,
  renders: RefCell<HashMap<i64, Rc<Render>>>,
}

impl ActionState {
  fn registry(&self, kind: i32) -> Option<&Registry<Rc<ActionDef>>> {
    self.kinds.get(kind as usize)
  }
}

pub fn install_actions<'js>(
  ctx: &Ctx<'js>,
  host: Rc<dyn ActionHost>,
  lifecycle: Rc<Lifecycle>,
  accounts: Option<Rc<AccountState>>,
  jvm: Option<Rc<JvmState>>,
  log: crate::Log,
  globals: &crate::api::Globals<'js>,
) -> JsResult<Rc<ActionState>> {
  let state = Rc::new(ActionState {
    host,
    lifecycle,
    log,
    accounts,
    jvm,
    kinds: (0..KIND_COUNT).map(|_| Registry::default()).collect(),
    renders: RefCell::new(HashMap::new()),
  });

  for (name, kind) in [
    ("registerGlobalAction", KIND_GLOBAL),
    ("registerChatAction", KIND_CHAT),
    ("registerMessageAction", KIND_MESSAGE),
    ("registerProfileAction", KIND_PROFILE),
  ] {
    let state = state.clone();
    globals.inu.set(
      name,
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, opts: Object<'js>| state.js_register(&ctx, kind, opts))?,
    )?;
  }
  Ok(state)
}

impl ActionState {
  fn js_register<'js>(self: &Rc<Self>, ctx: &Ctx<'js>, kind: i32, opts: Object<'js>) -> JsResult<Function<'js>> {
    let state = self;
    let what = kind_name(kind);
    let id = req_str(ctx, &opts, what, "id")?;
    let placements = if kind == KIND_MESSAGE { parse_message_placements(ctx, &opts, what)? } else { ALL_PLACEMENTS };
    let label = {
      let value = field(ctx, &opts, what, "text")?;
      match value.as_string() {
        Some(s) => Label::Static(s.to_string()?),
        None => match value.into_function() {
          Some(f) => Label::Dynamic(Persistent::save(ctx, f)),
          None => return Err(Exception::throw_type(ctx, &format!("{what}: 'text' must be a string or a function"))),
        },
      }
    };
    let icon = field(ctx, &opts, what, "icon")?;
    let icon = if icon.is_undefined() || icon.is_null() {
      None
    } else if let Some(f) = icon.as_function() {
      Some(ActionIcon::Dynamic(Persistent::save(ctx, f.clone())))
    } else {
      icon_from_value(ctx, icon, what, state.jvm.as_ref())?.map(|Icon { spec, retained_value }| ActionIcon::Static {
        spec,
        _retained: retained_value.map(|value| Persistent::save(ctx, value)),
      })
    };
    let visible = opt_fn(ctx, &opts, what, "visible")?;
    let callback = req_fn(ctx, &opts, what, "callback")?;
    let secondary_callback = opt_fn(ctx, &opts, what, "secondaryCallback")?;

    if state.lifecycle.is_unloading() {
      return noop_disposer(ctx);
    }

    let Some(registry) = state.registry(kind) else {
      return Err(Exception::throw_type(ctx, &format!("{what}: unknown action kind")));
    };
    let token = registry.alloc();
    let static_text = match &label {
      Label::Static(text) => Some(text.as_str()),
      Label::Dynamic(_) => None,
    };
    let static_icon = match &icon {
      Some(ActionIcon::Static { spec, .. }) => Some(spec.as_str()),
      _ => None,
    };
    let dynamic_fields = (if matches!(&label, Label::Dynamic(_)) { DYNAMIC_TEXT } else { 0 })
      | (if matches!(&icon, Some(ActionIcon::Dynamic(_))) { DYNAMIC_ICON } else { 0 })
      | (if visible.is_some() { DYNAMIC_VISIBLE } else { 0 })
      | (if secondary_callback.is_some() { HAS_SECONDARY_CALLBACK } else { 0 });
    if let Some(err) =
      state.host.action_register(kind, token, &id, placements, static_text, static_icon, dynamic_fields)
    {
      return Err(ctx.throw(crate::api::error::host_error_to_js(ctx, &err)?));
    }
    let def = Rc::new(ActionDef {
      token,
      placements,
      label,
      icon,
      retained_icons: RefCell::new(Vec::new()),
      visible: visible.map(|f| Persistent::save(ctx, f)),
      callback: Persistent::save(ctx, callback),
      secondary_callback: secondary_callback.map(|f| Persistent::save(ctx, f)),
    });
    if let Some(previous) = registry.insert(token, Some(id), def) {
      state.host.action_unregister(kind, previous.token);
    }

    let state = state.clone();
    make_disposer(ctx, move |_ctx| {
      let Some(registry) = state.registry(kind) else {
        return;
      };
      if registry.remove(token).is_some() {
        state.host.action_unregister(kind, token);
      }
    })
  }
}

fn parse_message_placements<'js>(ctx: &Ctx<'js>, opts: &Object<'js>, what: &str) -> JsResult<i32> {
  let value: Value = opts.get("placements")?;
  if value.is_undefined() {
    return Ok(MESSAGE_PLACEMENT_BUBBLE);
  }
  let array = value
    .as_array()
    .ok_or_else(|| Exception::throw_type(ctx, &format!("{what}: 'placements' must be a non-empty array")))?;
  if array.is_empty() {
    return Err(Exception::throw_type(ctx, &format!("{what}: 'placements' must not be empty")));
  }
  let mut placements = 0;
  for value in array.iter::<Value>() {
    let value = value?;
    let placement = value
      .as_string()
      .ok_or_else(|| {
        Exception::throw_type(ctx, &format!("{what}: placements must be 'bubble', 'selection' or 'sharedMedia'"))
      })?
      .to_string()?;
    placements |= match placement.as_str() {
      "bubble" => MESSAGE_PLACEMENT_BUBBLE,
      "selection" => MESSAGE_PLACEMENT_SELECTION,
      "sharedMedia" => MESSAGE_PLACEMENT_SHARED_MEDIA,
      _ => return Err(Exception::throw_type(ctx, &format!("{what}: unknown placement '{placement}'"))),
    };
  }
  Ok(placements)
}

impl ActionState {
  fn build_context<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    kind: i32,
    surface_json: &str,
  ) -> JsResult<(Object<'js>, i32)> {
    let parsed = json_parse_tl(ctx, surface_json)?;
    let parsed = parsed.as_object().ok_or_else(|| Exception::throw_type(ctx, "action: malformed surface"))?;

    let out = Object::new(ctx.clone())?;
    let account_id: i32 = parsed.get::<_, Option<i32>>("accountId")?.unwrap_or(0);
    out.set("account", crate::api::telegram::account::dispatch_account(ctx, &self.accounts, account_id)?)?;

    if kind != KIND_GLOBAL {
      let dialog_id: f64 = parsed.get::<_, Option<f64>>("dialogId")?.unwrap_or(0.0);
      out.set("dialogId", dialog_id)?;
      if let Some(topic_id) = parsed.get::<_, Option<f64>>("topicId")? {
        out.set("topicId", topic_id)?;
      }
    }
    if kind == KIND_MESSAGE {
      let source: String = parsed.get("source")?;
      let placement = match source.as_str() {
        "bubble" => MESSAGE_PLACEMENT_BUBBLE,
        "selection" => MESSAGE_PLACEMENT_SELECTION,
        "sharedMedia" => MESSAGE_PLACEMENT_SHARED_MEDIA,
        _ => return Err(Exception::throw_type(ctx, "action: malformed message source")),
      };
      let messages: Value = parsed.get("messages")?;
      let messages = messages.as_array().ok_or_else(|| Exception::throw_type(ctx, "action: malformed messages"))?;
      let globals = crate::api::Globals::get(ctx)?;
      let message = globals.get_message(ctx)?;
      let wrapped = Array::new(ctx.clone())?;
      for (index, raw) in messages.iter::<Value>().enumerate() {
        let raw = raw?;
        if raw.as_object().is_none() {
          return Err(Exception::throw_type(ctx, "action: malformed message"));
        }
        let wrapper: Object = message.construct((raw,))?;
        wrapped.set(index, wrapper)?;
      }
      out.set("source", source)?;
      out.set("messages", wrapped)?;
      return Ok((out, placement));
    }
    Ok((out, ALL_PLACEMENTS))
  }

  fn surface_context<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    kind: i32,
    surface_json: &str,
  ) -> Option<(Object<'js>, i32)> {
    match self.build_context(ctx, kind, surface_json) {
      Ok(obj) => Some(obj),
      Err(e) => {
        (self.log)(&format!("{}: bad surface: {}", kind_name(kind), describe_js_error(ctx, e)));
        None
      }
    }
  }

  fn start_render<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    render_id: i64,
    kind: i32,
    registry: &Registry<Rc<ActionDef>>,
    context: Value<'js>,
    placement: i32,
    settings: bool,
  ) -> JsResult<()> {
    let render = Rc::new(Render {
      id: render_id,
      kind,
      context: Persistent::save(ctx, context.clone()),
      rows: RefCell::new(Vec::new()),
      started: Cell::new(false),
    });
    self.renders.borrow_mut().insert(render_id, render.clone());
    for def in registry.values() {
      if def.placements & placement == 0 {
        continue;
      }
      if !registry.contains(def.token) {
        continue;
      }
      let index = {
        let mut rows = render.rows.borrow_mut();
        rows.push(RenderRow {
          token: def.token,
          text: match &def.label {
            Label::Static(text) => Some(text.clone()),
            Label::Dynamic(_) => None,
          },
          icon: match &def.icon {
            Some(ActionIcon::Static { spec, .. }) => Some(spec.clone()),
            _ => None,
          },
          dropped: false,
          pending: 0,
        });
        rows.len() - 1
      };
      match def.visible.as_ref().filter(|_| !settings) {
        Some(visible) => self.run_getter(ctx, &render, index, Getter::Visible, visible, &context)?,
        None => self.run_presentation(ctx, &render, index, &def, &context)?,
      }
    }
    render.started.set(true);
    self.answer_if_settled(ctx, &render);
    Ok(())
  }

  fn run_presentation<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    render: &Rc<Render>,
    index: usize,
    def: &ActionDef,
    context: &Value<'js>,
  ) -> JsResult<()> {
    if let Label::Dynamic(text) = &def.label {
      self.run_getter(ctx, render, index, Getter::Text, text, context)?;
    }
    if let Some(ActionIcon::Dynamic(icon)) = &def.icon {
      if !render.rows.borrow()[index].dropped {
        self.run_getter(ctx, render, index, Getter::Icon, icon, context)?;
      }
    }
    Ok(())
  }

  fn run_getter<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    render: &Rc<Render>,
    index: usize,
    getter: Getter,
    function: &Persistent<Function<'static>>,
    context: &Value<'js>,
  ) -> JsResult<()> {
    let function = function.clone().restore(ctx)?;
    render.rows.borrow_mut()[index].pending += 1;
    let value = match function.call::<_, Value>((context.clone(),)) {
      Ok(value) => value,
      Err(rquickjs::Error::Exception) => {
        (self.log)(&format!("{} row threw while rendering: {}", kind_name(render.kind), format_exception(ctx)));
        self.finish_getter(ctx, render, index, false);
        return Ok(());
      }
      Err(e) => return Err(e),
    };
    let render_id = render.id;
    let ok = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        state.settle_getter(&ctx, render_id, index, getter, Ok(value))
      })?
    };
    let err = {
      let state = self.clone();
      Function::new(ctx.clone(), move |ctx: Ctx<'js>, value: Value<'js>| {
        state.settle_getter(&ctx, render_id, index, getter, Err(value))
      })?
    };
    resolve_and_then(ctx, value, ok, err)
  }

  fn settle_getter<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    render_id: i64,
    index: usize,
    getter: Getter,
    result: Result<Value<'js>, Value<'js>>,
  ) {
    let Some(render) = self.renders.borrow().get(&render_id).cloned() else {
      return;
    };
    let kind = render.kind;
    let applied = match result {
      Ok(value) => self.apply_getter(ctx, &render, index, getter, value),
      Err(reason) => {
        (self.log)(&format!("{} row rejected while rendering: {}", kind_name(kind), format_thrown(ctx, &reason)));
        Ok(false)
      }
    };
    let keep = match applied {
      Ok(keep) => keep,
      Err(rquickjs::Error::Exception) => {
        (self.log)(&format!("{} row threw while rendering: {}", kind_name(kind), format_exception(ctx)));
        false
      }
      Err(e) => {
        (self.log)(&format!("{} row failed to render: {e:?}", kind_name(kind)));
        false
      }
    };
    self.finish_getter(ctx, &render, index, keep);
  }

  fn apply_getter<'js>(
    self: &Rc<Self>,
    ctx: &Ctx<'js>,
    render: &Rc<Render>,
    index: usize,
    getter: Getter,
    value: Value<'js>,
  ) -> JsResult<bool> {
    let token = render.rows.borrow()[index].token;
    let Some(def) = self.registry(render.kind).and_then(|registry| registry.get(token)) else {
      return Ok(false);
    };
    match getter {
      Getter::Visible => {
        if !value.as_bool().unwrap_or(false) {
          return Ok(false);
        }
        let context = render.context.clone().restore(ctx)?;
        self.run_presentation(ctx, render, index, &def, &context)?;
      }
      Getter::Text => {
        let text = rquickjs::Coerced::<String>::from_js(ctx, value)?.0;
        render.rows.borrow_mut()[index].text = Some(text);
      }
      Getter::Icon => {
        let icon = icon_from_value(ctx, value, kind_name(render.kind), self.jvm.as_ref())?;
        let Some(Icon { spec, retained_value }) = icon else {
          return Ok(true);
        };
        if let Some(value) = retained_value {
          let mut retained = def.retained_icons.borrow_mut();
          if retained.len() == 4 {
            retained.remove(0);
          }
          retained.push(Persistent::save(ctx, value));
        }
        render.rows.borrow_mut()[index].icon = Some(spec);
      }
    }
    Ok(true)
  }

  fn finish_getter(&self, ctx: &Ctx<'_>, render: &Render, index: usize, keep: bool) {
    {
      let mut rows = render.rows.borrow_mut();
      rows[index].pending -= 1;
      rows[index].dropped |= !keep;
    }
    self.answer_if_settled(ctx, render);
  }

  fn answer_if_settled(&self, ctx: &Ctx<'_>, render: &Render) {
    if !render.started.get() || render.rows.borrow().iter().any(|row| row.pending != 0) {
      return;
    }
    let Some(render) = self.renders.borrow_mut().remove(&render.id) else {
      return;
    };
    match self.serialize_rows(ctx, &render) {
      Ok(json) => self.host.actions_rendered(render.id, Some(&json)),
      Err(e) => {
        (self.log)(&format!("{}: render failed: {}", kind_name(render.kind), describe_js_error(ctx, e)));
        self.host.actions_rendered(render.id, None);
      }
    }
  }

  fn serialize_rows<'js>(&self, ctx: &Ctx<'js>, render: &Render) -> JsResult<String> {
    let registry = self.registry(render.kind);
    let out = Array::new(ctx.clone())?;
    let mut index = 0;
    for row in render.rows.borrow().iter() {
      if row.dropped || row.pending != 0 || !registry.is_some_and(|registry| registry.contains(row.token)) {
        continue;
      }
      let Some(text) = &row.text else {
        continue;
      };
      let entry = Object::new(ctx.clone())?;
      entry.set("token", row.token)?;
      entry.set("text", text.as_str())?;
      if let Some(icon) = &row.icon {
        entry.set("icon", icon.as_str())?;
      }
      out.set(index, entry)?;
      index += 1;
    }
    stringify_json(ctx, out.into_value(), "render: serialization produced no output")
  }
}

impl ActionState {
  /// answered exactly once through [`ActionHost::actions_rendered`], unless abandoned first
  pub fn render(self: &Rc<Self>, context: &rquickjs::Context, render_id: i64, kind: i32, surface_json: &str) {
    enter_js(context, |ctx| {
      let Some(registry) = self.registry(kind) else {
        (self.log)(&format!("render: unknown action kind {kind}"));
        self.host.actions_rendered(render_id, None);
        return;
      };
      if registry.is_empty() {
        self.host.actions_rendered(render_id, Some("[]"));
        return;
      }
      let settings = surface_json == "null";
      let (context, placement) = if settings {
        (Value::new_null(ctx.clone()), ALL_PLACEMENTS)
      } else {
        let Some((context, placement)) = self.surface_context(&ctx, kind, surface_json) else {
          self.host.actions_rendered(render_id, None);
          return;
        };
        (context.into_value(), placement)
      };
      let Err(e) = self.start_render(&ctx, render_id, kind, registry, context, placement, settings) else {
        return;
      };
      match e {
        rquickjs::Error::Exception => {
          (self.log)(&crate::fault(format_args!("{}: render failed: {}", kind_name(kind), format_exception(&ctx))));
        }
        e => (self.log)(&format!("{}: render failed: {e:?}", kind_name(kind))),
      }
      let abandoned = self.renders.borrow_mut().remove(&render_id);
      drop(abandoned);
      self.host.actions_rendered(render_id, None);
    });
    pump_jobs(context, self.log.as_ref());
  }

  /// the host stopped waiting: answers the rows that already settled, and a late answer is dropped.
  /// None once the render answered on its own
  pub fn abandon_render(&self, context: &rquickjs::Context, render_id: i64) -> Option<String> {
    enter_js(context, |ctx| {
      let render = self.renders.borrow_mut().remove(&render_id)?;
      match self.serialize_rows(&ctx, &render) {
        Ok(json) => Some(json),
        Err(e) => {
          (self.log)(&format!("{}: render failed: {}", kind_name(render.kind), describe_js_error(&ctx, e)));
          None
        }
      }
    })
  }

  pub fn dispatch(
    self: &Rc<Self>,
    context: &rquickjs::Context,
    kind: i32,
    token: u32,
    secondary: bool,
    surface_json: &str,
  ) {
    if self.lifecycle.is_unloading() {
      return;
    }
    enter_js(context, |ctx| {
      let Some(registry) = self.registry(kind) else {
        return;
      };
      let Some(def) = registry.get(token) else {
        return;
      };
      let callback = if secondary {
        let Some(callback) = &def.secondary_callback else {
          return;
        };
        callback
      } else {
        &def.callback
      };
      let callback = match callback.clone().restore(&ctx) {
        Ok(f) => f,
        Err(e) => {
          (self.log)(&format!("{}: failed to restore callback: {e:?}", kind_name(kind)));
          return;
        }
      };
      let Some((context_obj, placement)) = self.surface_context(&ctx, kind, surface_json) else {
        return;
      };
      if def.placements & placement == 0 {
        return;
      }
      let name = if secondary { "secondary callback" } else { "callback" };
      call_callback(&ctx, &self.log, &format!("{} {name}", kind_name(kind)), &callback, (context_obj,));
    });
    pump_jobs(context, self.log.as_ref());
  }
}

impl Dispose for ActionState {
  fn dispose(&self, context: &rquickjs::Context) {
    enter_js(context, |_| {
      for registry in &self.kinds {
        drop(registry.remove_matching(|_| true));
      }
      let renders: Vec<_> = self.renders.borrow_mut().drain().collect();
      drop(renders);
    });
  }
}

#[cfg(test)]
#[path = "actions_tests.rs"]
mod tests;

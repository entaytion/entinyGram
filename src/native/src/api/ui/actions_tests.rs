use super::*;
use std::cell::{Cell, RefCell};

use rquickjs::{Context, Runtime};

/// mirrors Kotlin `ActionRegistry` rather than flagging refusals, because the two rules only
/// interact: rows are keyed by id per kind and the cap is consulted only for an id the host does
/// not already hold. `limit` 0 is unlimited.
#[derive(Default)]
pub(crate) struct TestActionHost {
  registered: RefCell<Vec<(i32, u32, String, Option<String>, Option<String>, i32)>>,
  unregistered: RefCell<Vec<(i32, u32)>>,
  rows: RefCell<Vec<(i32, String, u32, i32)>>,
  pub(crate) limit: Cell<usize>,
  refuse_register: RefCell<Option<String>>,
  rendered: RefCell<Vec<(i64, Option<String>)>>,
  next_render: Cell<i64>,
}

impl ActionHost for TestActionHost {
  fn action_register(
    &self,
    kind: i32,
    token: u32,
    id: &str,
    placements: i32,
    text: Option<&str>,
    icon: Option<&str>,
    dynamic_fields: i32,
  ) -> Option<String> {
    if let Some(err) = self.refuse_register.borrow().clone() {
      return Some(err);
    }
    let mut rows = self.rows.borrow_mut();
    if self.limit.get() != 0 {
      for bit in 0..i32::BITS {
        let placement = 1_i32.wrapping_shl(bit);
        if placements & placement == 0 {
          continue;
        }
        let count = rows.iter().filter(|r| r.0 == kind && r.1 != id && r.3 & placement != 0).count();
        if count >= self.limit.get() {
          return Some(format!("Pquota-exceeded\n\n\n\n'{id}' would be row {}", count + 1));
        }
      }
    }
    match rows.iter_mut().find(|r| r.0 == kind && r.1 == id) {
      Some(row) => {
        row.2 = token;
        row.3 = placements;
      }
      None => rows.push((kind, id.to_string(), token, placements)),
    }
    self.registered.borrow_mut().push((
      kind,
      token,
      id.to_string(),
      text.map(str::to_string),
      icon.map(str::to_string),
      dynamic_fields,
    ));
    None
  }
  fn action_unregister(&self, kind: i32, token: u32) {
    self.rows.borrow_mut().retain(|r| !(r.0 == kind && r.2 == token));
    self.unregistered.borrow_mut().push((kind, token));
  }
  fn actions_rendered(&self, render_id: i64, json: Option<&str>) {
    self.rendered.borrow_mut().push((render_id, json.map(str::to_string)));
  }
}

impl TestActionHost {
  fn answers(&self, render_id: i64) -> Vec<Option<String>> {
    self.rendered.borrow().iter().filter(|(id, _)| *id == render_id).map(|(_, json)| json.clone()).collect()
  }
}

fn start(state: &Disposing, host: &TestActionHost, ctx: &Context, kind: i32, surface: &str) -> i64 {
  let render_id = host.next_render.get() + 1;
  host.next_render.set(render_id);
  state.render(ctx, render_id, kind, surface);
  render_id
}

fn render(state: &Disposing, host: &TestActionHost, ctx: &Context, kind: i32, surface: &str) -> Option<String> {
  let render_id = start(state, host, ctx, kind, surface);
  let answers = host.answers(render_id);
  assert_eq!(answers.len(), 1, "a render answers exactly once: {answers:?}");
  answers.into_iter().next().unwrap()
}

fn run_jobs(rt: &Runtime) {
  while rt.is_job_pending() {
    rt.execute_pending_job().ok();
  }
}

pub(crate) type Disposing = crate::testing::harness::DisposeOnDrop<ActionState>;
pub(crate) type Fixture =
  (Runtime, Context, Rc<TestActionHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

pub(crate) fn setup() -> Fixture {
  setup_with(Lifecycle::new())
}

fn setup_with(lifecycle: Rc<Lifecycle>) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(TestActionHost::default());
  let host_dyn: Rc<dyn ActionHost> = host.clone();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    let shared = crate::api::tl::utils::install_utils(&ctx, &inu).unwrap();
    crate::api::tl::message::install_message(&ctx, &shared, &inu).unwrap();
    install_actions(&ctx, host_dyn, lifecycle, None, None, log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, state, logs)
}

use crate::testing::harness::eval_unit as eval;

fn eval_err(ctx: &Context, source: &str) -> String {
  ctx.with(|ctx| match ctx.eval::<Value, _>(source) {
    Ok(_) => panic!("expected a throw"),
    Err(rquickjs::Error::Exception) => format_exception(&ctx),
    Err(e) => panic!("{e:?}"),
  })
}

fn read_log(ctx: &Context) -> String {
  crate::testing::harness::eval_json(&ctx, "globalThis.__log")
}

pub(crate) const CHAT_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"topicId":7}"#;
pub(crate) const MESSAGE_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"source":"bubble","messages":[{"_":"message","id":11,"date":1,"message":"one","peer_id":{"_":"peerChat","chat_id":"100"},"dialog_id":"-100","grouped_id":"77"},{"_":"message","id":12,"date":2,"message":"two","peer_id":{"_":"peerChat","chat_id":"100"},"dialog_id":"-100","grouped_id":"77"}]}"#;
pub(crate) const SELECTION_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"topicId":7,"source":"selection","messages":[{"_":"message","id":3,"date":1,"message":"three","peer_id":{"_":"peerChat","chat_id":"200"},"dialog_id":"-200"},{"_":"message","id":14,"date":2,"message":"fourteen","peer_id":{"_":"peerChat","chat_id":"100"},"dialog_id":"-100"}]}"#;
pub(crate) const SHARED_MEDIA_SURFACE: &str = r#"{"accountId":0,"dialogId":-100,"source":"sharedMedia","messages":[{"_":"message","id":15,"date":3,"message":"","peer_id":{"_":"peerChat","chat_id":"100"},"dialog_id":"-100"}]}"#;

pub(crate) fn row(token: u32, text: &str) -> String {
  format!(r#"{{"token":{token},"text":"{text}"}}"#)
}

pub(crate) fn rows(entries: &[String]) -> String {
  format!("[{}]", entries.join(","))
}

#[test]
fn a_static_row_renders_without_running_plugin_code() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__log = [];
      inu.registerChatAction({ id: 'a', text: 'Alpha', callback: () => { __log.push('a') } })
    "#,
  );
  assert_eq!(host.registered.borrow().len(), 1);
  assert_eq!(
    host.registered.borrow()[0],
    (KIND_CHAT, 1, "a".to_string(), Some("Alpha".to_string()), None, 0),
  );
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "Alpha")]));
}

#[test]
fn a_dynamic_label_and_visible_see_the_surface() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerMessageAction({
        id: 'a',
        text: ctx => `${ctx.source} ${ctx.messages.map(message => message.id).join('+')} in ${ctx.dialogId}`,
        visible: ctx => ctx.messages.length === 2,
        callback: () => {},
      });
      inu.registerMessageAction({
        id: 'b', text: 'never', visible: () => false, callback: () => {},
      })
    "#,
  );
  assert_eq!(host.registered.borrow()[0].3.as_deref(), None);
  assert_eq!(host.registered.borrow()[0].5, DYNAMIC_TEXT | DYNAMIC_VISIBLE);
  assert_eq!(host.registered.borrow()[1].3.as_deref(), Some("never"));
  assert_eq!(host.registered.borrow()[1].5, DYNAMIC_VISIBLE);
  let json = render(&state, &host, &ctx, KIND_MESSAGE, MESSAGE_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "bubble 11+12 in -100")]));
}

#[test]
fn message_placements_filter_each_surface_and_settings_include_every_placement() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerMessageAction({ id: 'bubble', text: 'bubble', callback: () => {} });
      inu.registerMessageAction({ id: 'selection', placements: ['selection'], text: 'selection', callback: () => {} });
      inu.registerMessageAction({ id: 'both', placements: ['bubble', 'selection'], text: 'both', callback: () => {} });
      inu.registerMessageAction({ id: 'media', placements: ['selection', 'sharedMedia'], text: 'media', callback: () => {} })
    "#,
  );

  assert_eq!(
    render(&state, &host, &ctx, KIND_MESSAGE, MESSAGE_SURFACE).unwrap(),
    rows(&[row(1, "bubble"), row(3, "both")])
  );
  assert_eq!(
    render(&state, &host, &ctx, KIND_MESSAGE, SELECTION_SURFACE).unwrap(),
    rows(&[row(2, "selection"), row(3, "both"), row(4, "media")]),
  );
  assert_eq!(render(&state, &host, &ctx, KIND_MESSAGE, SHARED_MEDIA_SURFACE).unwrap(), rows(&[row(4, "media")]));
  assert_eq!(
    render(&state, &host, &ctx, KIND_MESSAGE, "null").unwrap(),
    rows(&[row(1, "bubble"), row(2, "selection"), row(3, "both"), row(4, "media")]),
  );
}

#[test]
fn message_placements_reject_empty_unknown_and_non_array_values() {
  let (_rt, ctx, _host, _state, _logs) = setup();
  for source in [
    "inu.registerMessageAction({ id: 'a', placements: [], text: 'a', callback: () => {} })",
    "inu.registerMessageAction({ id: 'a', placements: ['other'], text: 'a', callback: () => {} })",
    "inu.registerMessageAction({ id: 'a', placements: 'bubble', text: 'a', callback: () => {} })",
  ] {
    let error = eval_err(&ctx, source);
    assert!(error.contains("placement"), "{source}: {error}");
  }
}

#[test]
fn re_registering_an_id_replaces_the_row_in_place_and_retires_its_token() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
      inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
      globalThis.__disposeFirst = null;
      inu.registerChatAction({ id: 'a', text: 'A2', callback: () => {} })
    "#,
  );
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(3, "A2"), row(2, "B")]), "the replacement keeps its predecessor's position");
  assert_eq!(
    *host.unregistered.borrow(),
    vec![(KIND_CHAT, 1)],
    "the host is told the displaced row is gone, or it keeps sizing a menu for it"
  );
}

#[test]
fn a_disposer_removes_the_row_and_tells_the_host_once() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
      inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
      __d(); __d()
    "#,
  );
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(2, "B")]));
  assert_eq!(*host.unregistered.borrow(), vec![(KIND_CHAT, 1)]);
}

#[test]
fn registering_after_unload_began_registers_nothing() {
  let lifecycle = Lifecycle::new();
  lifecycle.begin_unload();
  let (_rt, ctx, host, state, _logs) = setup_with(lifecycle);
  eval(
    &ctx,
    r#"
      globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A', callback: () => {} });
      __d()
    "#,
  );
  assert!(host.registered.borrow().is_empty());
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, "[]");
}

#[test]
fn a_malformed_registration_throws_even_while_unloading() {
  let lifecycle = Lifecycle::new();
  lifecycle.begin_unload();
  let (_rt, ctx, _host, _state, _logs) = setup_with(lifecycle);
  let err = eval_err(&ctx, "inu.registerChatAction({ id: 'a', text: 'A' })");
  assert!(err.contains("'callback' must be a function"), "{err}");
}

#[test]
fn static_and_dynamic_icons_are_rendered() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({
        id: 'a', text: 'A', icon: { __inuIcon: 'rmsg_settings' }, callback: () => {},
      });
      inu.registerChatAction({
        id: 'b', text: 'B', icon: () => ({ __inuIcon: 'rmsg_pin' }), callback: () => {},
      })
    "#,
  );
  assert_eq!(host.registered.borrow()[0].4.as_deref(), Some("rmsg_settings"));
  assert_eq!(host.registered.borrow()[0].5, 0);
  assert_eq!(host.registered.borrow()[1].4.as_deref(), None);
  assert_eq!(host.registered.borrow()[1].5, DYNAMIC_ICON);
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, r#"[{"token":1,"text":"A","icon":"rmsg_settings"},{"token":2,"text":"B","icon":"rmsg_pin"}]"#,);
}

#[test]
fn chat_and_message_getters_receive_null_in_settings() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({
        id: 'a', text: ctx => ctx === null ? 'chat settings' : 'chat', callback: () => {},
      });
      inu.registerMessageAction({
        id: 'b', text: ctx => ctx === null ? 'message settings' : 'message', callback: () => {},
      })
    "#,
  );
  assert_eq!(render(&state, &host, &ctx, KIND_CHAT, "null").unwrap(), rows(&[row(1, "chat settings")]));
  assert_eq!(render(&state, &host, &ctx, KIND_MESSAGE, "null").unwrap(), rows(&[row(1, "message settings")]));
}

/// a JNI failure is the bridge's, so the plugin is told `internal` rather than to shed rows
#[test]
fn a_host_that_refuses_a_registration_throws_internal_and_leaves_nothing_behind() {
  let (_rt, ctx, host, state, _logs) = setup();
  *host.refuse_register.borrow_mut() = Some("registerAction: JNI env unavailable".to_string());
  let caught = crate::testing::harness::catch_json(
    &ctx,
    "inu.registerChatAction({ id: 'a', text: ctx => 'A', callback: () => {} })",
  );
  assert!(
    caught.starts_with(r#"[true,"internal",null,"#) && caught.contains("JNI env unavailable"),
    "{caught}"
  );
  assert_eq!(render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap(), "[]");
}

#[test]
fn a_callback_that_throws_is_the_plugins_fault() {
  let (_rt, ctx, _host, state, logs) = setup();
  eval(
    &ctx,
    r#"inu.registerChatAction({ id: 'a', text: 'A', callback: () => { throw new Error('nope') } })"#,
  );
  state.dispatch(&ctx, KIND_CHAT, 1, false, CHAT_SURFACE);
  let logs = logs.borrow();
  assert!(logs.iter().any(|l| l.starts_with(crate::FAULT_PREFIX) && l.contains("nope")), "{logs:#?}");
}

#[test]
fn primary_and_secondary_callbacks_dispatch_independently() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__log = [];
      inu.registerChatAction({
        id: 'both', text: 'Both',
        callback: ctx => __log.push(`tap ${ctx.dialogId}`),
        secondaryCallback: ctx => __log.push(`long ${ctx.dialogId}`),
      });
      inu.registerChatAction({
        id: 'tap', text: 'Tap', callback: () => __log.push('tap only'),
      })
    "#,
  );
  assert_eq!(host.registered.borrow()[0].5, HAS_SECONDARY_CALLBACK);
  assert_eq!(host.registered.borrow()[1].5, 0);

  state.dispatch(&ctx, KIND_CHAT, 1, false, CHAT_SURFACE);
  state.dispatch(&ctx, KIND_CHAT, 1, true, CHAT_SURFACE);
  state.dispatch(&ctx, KIND_CHAT, 2, false, CHAT_SURFACE);
  state.dispatch(&ctx, KIND_CHAT, 2, true, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), r#"["tap -100","long -100","tap only"]"#);
}

/// tokens are never reused, so a row drawn before a replacement cannot be answered by the row
/// that replaced it, which is what makes a menu left open across a reload safe
#[test]
fn a_dispatch_for_a_disposed_or_replaced_row_does_nothing() {
  let (_rt, ctx, _host, state, logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__log = [];
      inu.registerChatAction({ id: 'a', text: 'A', callback: () => { __log.push('first') } });
      globalThis.__d = inu.registerChatAction({ id: 'a', text: 'A2', callback: () => { __log.push('second') } })
    "#,
  );
  state.dispatch(&ctx, KIND_CHAT, 1, false, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), "[]");
  state.dispatch(&ctx, KIND_CHAT, 2, false, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), r#"["second"]"#);
  eval(&ctx, "__d()");
  state.dispatch(&ctx, KIND_CHAT, 2, false, CHAT_SURFACE);
  assert_eq!(read_log(&ctx), r#"["second"]"#, "the stale row is inert");
  assert!(logs.borrow().is_empty());
}

#[test]
fn a_row_disposed_by_an_earlier_rows_visible_is_not_drawn() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__d = null;
      inu.registerChatAction({ id: 'a', text: 'A', visible: () => { __d(); return true }, callback: () => {} });
      globalThis.__d = inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} })
    "#,
  );
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A")]));
}

#[test]
fn a_row_registered_mid_render_joins_the_next_one() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({
        id: 'a', text: 'A',
        visible: () => {
          if (!globalThis.__added) {
            globalThis.__added = true;
            inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} });
          }
          return true;
        },
        callback: () => {},
      })
    "#,
  );
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A")]));
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, rows(&[row(1, "A"), row(2, "B")]));
}

#[test]
fn kinds_are_separate_registries() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({ id: 'a', text: 'chat', callback: () => {} });
      inu.registerMessageAction({ id: 'a', text: 'message', callback: () => {} })
    "#,
  );
  assert_eq!(render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap(), rows(&[row(1, "chat")]));
  assert_eq!(render(&state, &host, &ctx, KIND_MESSAGE, MESSAGE_SURFACE).unwrap(), rows(&[row(1, "message")]));
}

#[test]
fn the_bundled_actions_test_plugin_passes() {
  let (rt, ctx, host, state, logs) = setup();
  // the oracle asserts the cap, so the harness has to have one; the device's is the same 8
  host.limit.set(8);
  let lines = crate::testing::harness::install_capturing_console(&ctx);
  eval(&ctx, crate::testing::test_plugin!("actions-test.js"));

  assert_eq!(render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap(), rows(&[row(2, "Chat row")]));
  assert_eq!(render(&state, &host, &ctx, KIND_MESSAGE, MESSAGE_SURFACE).unwrap(), rows(&[row(1, "Message row")]));
  state.dispatch(&ctx, KIND_MESSAGE, 1, false, MESSAGE_SURFACE);
  state.dispatch(&ctx, KIND_MESSAGE, 1, false, SELECTION_SURFACE);
  state.dispatch(&ctx, KIND_PROFILE, 1, false, CHAT_SURFACE);
  state.dispatch(&ctx, KIND_GLOBAL, 1, false, r#"{"accountId":0}"#);

  while rt.is_job_pending() {
    rt.execute_pending_job().ok();
  }
  let lines = lines.borrow().clone();
  crate::testing::harness::assert_oracle_exact(&lines, "actions test done", 16);
  // what the oracle cannot see about itself: its throwing row was dropped from the render
  // above and left the plugin running, so every assertion after it still ran
  let logs = logs.borrow();
  assert!(logs.iter().any(|l| l.contains("visible blew up")), "{logs:#?}");
  assert!(!logs.iter().any(|l| l.starts_with(crate::FAULT_PREFIX)), "{logs:#?}");
}

#[test]
fn async_getters_are_awaited_within_the_render() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({
        id: 'a',
        text: async ctx => `chat ${ctx.dialogId}`,
        icon: async () => ({ __inuIcon: 'rmsg_pin' }),
        visible: async () => true,
        callback: () => {},
      });
      inu.registerChatAction({ id: 'b', text: 'B', visible: async () => false, callback: () => {} });
      inu.registerChatAction({ id: 'c', text: 'C', callback: () => {} })
    "#,
  );
  let json = render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap();
  assert_eq!(json, format!(r#"[{{"token":1,"text":"chat -100","icon":"rmsg_pin"}},{}]"#, row(3, "C")));
}

#[test]
fn a_rejected_getter_drops_only_its_row() {
  let (_rt, ctx, host, state, logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({ id: 'a', text: async () => { throw new Error('text blew up') }, callback: () => {} });
      inu.registerChatAction({ id: 'b', text: 'B', visible: () => Promise.reject(new Error('nope')), callback: () => {} });
      inu.registerChatAction({ id: 'c', text: 'C', callback: () => {} })
    "#,
  );
  assert_eq!(render(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE).unwrap(), rows(&[row(3, "C")]));
  let logs = logs.borrow();
  assert!(logs.iter().any(|l| l.contains("text blew up")), "{logs:#?}");
  assert!(!logs.iter().any(|l| l.starts_with(crate::FAULT_PREFIX)), "{logs:#?}");
}

#[test]
fn a_render_answers_once_its_last_getter_settles() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__resolve = [];
      inu.registerChatAction({
        id: 'a', text: () => new Promise(resolve => __resolve.push(resolve)), callback: () => {},
      });
      inu.registerChatAction({
        id: 'b', text: 'B', visible: () => new Promise(resolve => __resolve.push(resolve)), callback: () => {},
      })
    "#,
  );
  let render_id = start(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE);
  assert!(host.answers(render_id).is_empty());
  eval(&ctx, "__resolve[0]('A')");
  run_jobs(&rt);
  assert!(host.answers(render_id).is_empty());
  eval(&ctx, "__resolve[1](true)");
  run_jobs(&rt);
  assert_eq!(host.answers(render_id), vec![Some(rows(&[row(1, "A"), row(2, "B")]))]);
}

#[test]
fn an_abandoned_render_never_answers() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__resolve = null;
      inu.registerChatAction({
        id: 'a', text: () => new Promise(resolve => { __resolve = resolve }), callback: () => {},
      })
    "#,
  );
  let render_id = start(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE);
  assert_eq!(state.abandon_render(&ctx, render_id), Some("[]".to_string()));
  assert_eq!(state.abandon_render(&ctx, render_id), None);
  eval(&ctx, "__resolve('A')");
  run_jobs(&rt);
  assert!(host.answers(render_id).is_empty());
}

#[test]
fn a_row_disposed_while_its_getter_is_pending_is_not_drawn() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      globalThis.__resolve = null;
      globalThis.__d = inu.registerChatAction({
        id: 'a', text: () => new Promise(resolve => { __resolve = resolve }), callback: () => {},
      });
      inu.registerChatAction({ id: 'b', text: 'B', callback: () => {} })
    "#,
  );
  let render_id = start(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE);
  eval(&ctx, "__d(); __resolve('A')");
  run_jobs(&rt);
  assert_eq!(host.answers(render_id), vec![Some(rows(&[row(2, "B")]))]);
}

#[test]
fn an_abandoned_render_answers_the_rows_that_already_settled() {
  let (rt, ctx, host, state, _logs) = setup();
  eval(
    &ctx,
    r#"
      inu.registerChatAction({ id: 'slow', text: () => new Promise(() => {}), callback: () => {} });
      inu.registerChatAction({ id: 'async', text: async () => 'Async', callback: () => {} });
      inu.registerChatAction({
        id: 'half', text: async () => 'half', icon: () => new Promise(() => {}), callback: () => {},
      });
      inu.registerChatAction({ id: 'static', text: 'Static', callback: () => {} })
    "#,
  );
  let render_id = start(&state, &host, &ctx, KIND_CHAT, CHAT_SURFACE);
  run_jobs(&rt);
  assert!(host.answers(render_id).is_empty());
  assert_eq!(state.abandon_render(&ctx, render_id), Some(rows(&[row(2, "Async"), row(4, "Static")])));
}

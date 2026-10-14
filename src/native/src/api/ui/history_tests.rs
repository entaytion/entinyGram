use super::*;
use crate::testing::harness::{catch_json, eval_json, eval_unit as eval};
use rquickjs::{Context, Runtime};

#[derive(Default)]
struct TestHistoryHost {
  opened: RefCell<Vec<(i64, String)>>,
  updates: RefCell<Vec<(i64, i32, String)>>,
  closed: RefCell<Vec<i64>>,
  pages: RefCell<Vec<(i64, String)>>,
  menus: RefCell<Vec<(i64, String)>>,
  refuse_open: RefCell<Option<String>>,
}

impl HistoryHost for TestHistoryHost {
  fn history_open(&self, history_id: i64, options_json: &str) -> Option<String> {
    self.opened.borrow_mut().push((history_id, options_json.to_string()));
    self.refuse_open.borrow().clone()
  }
  fn history_update(&self, history_id: i64, op: i32, json: &str) -> Option<String> {
    self.updates.borrow_mut().push((history_id, op, json.to_string()));
    None
  }
  fn history_close(&self, history_id: i64) {
    self.closed.borrow_mut().push(history_id);
  }
  fn history_page(&self, request_id: i64, wire: &str) {
    self.pages.borrow_mut().push((request_id, wire.to_string()));
  }
  fn history_menu(&self, request_id: i64, wire: &str) {
    self.menus.borrow_mut().push((request_id, wire.to_string()));
  }
}

type Disposing = crate::testing::harness::DisposeOnDrop<HistoryState>;
type Fixture = (Runtime, Context, Rc<TestHistoryHost>, Disposing, std::sync::Arc<crate::testing::harness::Logs>);

fn setup() -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let host = Rc::new(TestHistoryHost::default());
  let host_dyn: Rc<dyn HistoryHost> = host.clone();
  let logs = crate::testing::harness::Logs::new();
  let log = crate::testing::harness::log_sink(&logs);
  let state = ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    crate::api::ui::icons::install_icons(&ctx, crate::api::ui::icons::tests::TestIconHost::without(&[]), None, &inu).unwrap();
    install_history(&ctx, host_dyn, None, Lifecycle::new(), log, &inu).unwrap()
  });
  let state = Disposing::new(&ctx, state, |ctx, state| state.dispose(ctx));
  (rt, ctx, host, state, logs)
}

const OPEN: &str = r#"
  globalThis.__loads = [];
  globalThis.__h = inu.ui.openChatHistory({
    title: { text: 'Feed', entities: [{ _: 'messageEntityBold', offset: 0, length: 4 }] },
    subtitle: 'two chats',
    account: 1,
    synthetic: true,
    avatars: false,
    icon: inu.icons.avatar(777000),
    onRead: key => { globalThis.__read = key },
    onHeaderTap: () => { globalThis.__headerTaps = (globalThis.__headerTaps ?? 0) + 1 },
    button: { text: 'Mark read', onClick: () => { globalThis.__clicks = (globalThis.__clicks ?? 0) + 1 } },
    menu: async () => [
      { text: 'Refresh', icon: inu.icons.avatar(42), onClick: () => { globalThis.__menu = 'refresh' } },
      { text: 'Muted', checked: globalThis.__muted ?? false, danger: true, onClick: () => { globalThis.__muted = true } },
    ],
    loadAround: target => {
      globalThis.__loads.push(target);
      return globalThis.__answer(target);
    },
    loadMore: (cursor, direction) => {
      globalThis.__loads.push([cursor, direction]);
      return globalThis.__answer(cursor, direction);
    },
  });
  globalThis.__h.closed.then(() => { globalThis.__closed = true });
"#;

#[test]
fn open_hands_the_host_the_screen_options_and_returns_a_handle() {
  let (_rt, ctx, host, _state, _logs) = setup();
  eval(&ctx, OPEN);
  let opened = host.opened.borrow();
  assert_eq!(opened.len(), 1);
  assert_eq!(opened[0].0, 1);
  assert_eq!(
    opened[0].1,
    r#"{"title":"Feed","titleEntities":[{"_":"messageEntityBold","offset":0,"length":4}],"subtitle":"two chats","account":1,"synthetic":true,"avatars":false,"icon":"p-1:777000","read":true,"headerTap":true,"button":{"text":"Mark read"},"menu":true}"#
  );
  assert_eq!(
    eval_json(&ctx, "Object.keys(globalThis.__h).sort()"),
    r#"["append","close","closed","remove","replace","scrollTo","setButton","setUnreadCount"]"#
  );
}

#[test]
fn a_refused_open_throws_and_leaves_no_history_behind() {
  let (_rt, ctx, host, state, _logs) = setup();
  *host.refuse_open.borrow_mut() = Some("Pnot-found\n\n\n\nno such account".to_string());
  let caught = catch_json(&ctx, "inu.ui.openChatHistory({ title: 'x', loadAround: () => ({ entries: [] }), loadMore: () => ({ entries: [] }) })");
  assert!(caught.contains("not-found"), "{caught}");
  assert!(state.defs.borrow().is_empty());
  let caught = catch_json(
    &ctx,
    "inu.ui.openChatHistory({ title: 'x', icon: inu.icons.customEmoji('5361751237382052539'), loadAround: () => ({ entries: [] }), loadMore: () => ({ entries: [] }) })",
  );
  assert!(caught.contains("chat avatar"), "{caught}");
  *host.refuse_open.borrow_mut() = None;
  eval(&ctx, "globalThis.__hidden = inu.ui.openChatHistory({ title: 'x', button: { onClick: () => {} }, loadAround: () => ({ entries: [] }), loadMore: () => ({ entries: [] }) })");
  assert!(host.opened.borrow().last().unwrap().1.contains(r#""button":{}"#));
  eval(&ctx, "globalThis.__hidden.setButton({ text: 'later' })");
  assert_eq!(host.updates.borrow().last().unwrap().2, r#"{"text":"later"}"#);
}

#[test]
fn load_more_is_asked_per_direction_and_its_page_reaches_the_host_serialized() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(&ctx, OPEN);
  eval(
    &ctx,
    r#"globalThis.__answer = async (cursor, direction) => ({
      entries: [
        { key: 'a', replyTo: 'b', synthetic: false, message: { _: 'message', id: 5, peer_id: { _: 'peerUser', user_id: 7 }, message: 'hi' } },
        { replyTo: { key: 'b', synthetic: true, replyTo: 'c', message: { _: 'message', id: 4 } }, message: { _: 'message', id: 6 } },
      ],
      users: [{ _: 'user', id: 7 }],
      next: direction + ':' + cursor,
      older: 'o1',
      newer: 'n1',
      focus: 'a',
      unreadCount: 3,
    })"#,
  );
  state.dispatch_load_more(&ctx, 1, 10, "c1", false);
  state.dispatch_load_more(&ctx, 1, 11, "c2", true);
  assert_eq!(eval_json(&ctx, "globalThis.__loads"), r#"[["c1","older"],["c2","newer"]]"#);
  let pages = host.pages.borrow();
  assert_eq!(pages.len(), 2);
  assert_eq!(pages[0].0, 10);
  assert_eq!(
    pages[0].1,
    r#"S{"entries":[{"message":{"_":"message","id":5,"peer_id":{"_":"peerUser","user_id":7},"message":"hi"},"key":"a","replyTo":"b","synthetic":false},{"message":{"_":"message","id":6},"replyTo":{"message":{"_":"message","id":4},"key":"b"}}],"users":[{"_":"user","id":7}],"next":"older:c1","unreadCount":3}"#
  );
  assert_eq!(pages[1].0, 11);
  assert!(pages[1].1.starts_with("S{"), "{}", pages[1].1);
}

#[test]
fn load_around_places_the_start_or_jumps_to_a_key_or_a_message() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(&ctx, OPEN);
  eval(
    &ctx,
    r#"globalThis.__answer = async target => target.key === 'gone' ? null : ({
      entries: [{ key: 'a', message: { _: 'message', id: 5 } }],
      next: 'ignored',
      older: 'o1',
      newer: 'n1',
      ...(target === 'newest' ? {} : { focus: 'a' }),
    })"#,
  );
  state.dispatch_load_around(&ctx, 1, 10, "Ka");
  state.dispatch_load_around(&ctx, 1, 11, "M-1000000000042:7");
  state.dispatch_load_around(&ctx, 1, 12, "Kgone");
  state.dispatch_load_around(&ctx, 1, 13, "I");
  state.dispatch_load_around(&ctx, 1, 14, "L");
  state.dispatch_load_around(&ctx, 1, 15, "Mnope");
  eval(&ctx, "globalThis.__answer = async () => ({ entries: [] })");
  state.dispatch_load_around(&ctx, 1, 16, "Ka");
  eval(&ctx, "globalThis.__answer = async () => null");
  state.dispatch_load_around(&ctx, 1, 17, "I");
  assert_eq!(
    eval_json(&ctx, "globalThis.__loads"),
    r#"[{"key":"a"},{"dialogId":-1000000000042,"messageId":7},{"key":"gone"},"initial","newest",{"key":"a"},"initial"]"#
  );
  let pages = host.pages.borrow();
  let focused = r#"S{"entries":[{"message":{"_":"message","id":5},"key":"a"}],"older":"o1","newer":"n1","focus":"a"}"#;
  assert_eq!(pages[0], (10, focused.to_string()));
  assert_eq!(pages[1], (11, focused.to_string()));
  assert_eq!(pages[2], (12, "N".to_string()));
  assert_eq!(pages[3], (13, focused.to_string()));
  assert_eq!(pages[4], (14, r#"S{"entries":[{"message":{"_":"message","id":5},"key":"a"}],"older":"o1","newer":"n1"}"#.to_string()));
  assert!(pages[5].0 == 15 && pages[5].1.starts_with("E"), "{:?}", pages[5]);
  assert!(pages[6].0 == 16 && pages[6].1.starts_with("E") && pages[6].1.contains("focus"), "{:?}", pages[6]);
  assert!(pages[7].0 == 17 && pages[7].1.starts_with("E") && pages[7].1.contains("page object"), "{:?}", pages[7]);
}

#[test]
fn a_bad_page_or_a_rejected_load_answers_the_host_with_an_error() {
  let (_rt, ctx, host, state, logs) = setup();
  eval(&ctx, OPEN);
  eval(&ctx, "globalThis.__answer = () => ({ entries: [{ message: 'nope' }] })");
  state.dispatch_load_around(&ctx, 1, 10, "I");
  eval(&ctx, "globalThis.__answer = () => ({ entries: [{ replyTo: 5, message: { _: 'message', id: 1 } }] })");
  state.dispatch_load_around(&ctx, 1, 9, "I");
  eval(&ctx, "globalThis.__answer = () => Promise.reject(new Error('boom'))");
  state.dispatch_load_more(&ctx, 1, 11, "c", false);
  let pages = host.pages.borrow();
  assert_eq!(pages[0].0, 10);
  assert!(pages[0].1.starts_with("E") && pages[0].1.contains("TL message"), "{}", pages[0].1);
  assert_eq!(pages[1].0, 9);
  assert!(pages[1].1.starts_with("E") && pages[1].1.contains("'replyTo' must be a key or an entry"), "{}", pages[1].1);
  assert_eq!(pages[2].0, 11);
  assert!(pages[2].1.starts_with("E") && pages[2].1.contains("boom"), "{}", pages[2].1);
  assert!(logs.borrow().iter().any(|line| line.contains("boom")));
}

#[test]
fn updates_are_forwarded_and_refused_once_the_screen_closed() {
  let (_rt, ctx, host, state, _logs) = setup();
  eval(&ctx, OPEN);
  assert!(catch_json(&ctx, "globalThis.__h.remove([1])").contains("strings"));
  eval(
    &ctx,
    r#"
      globalThis.__h.append({ entries: [{ key: 'k', message: { _: 'message', id: 1 } }], unreadCount: 2 });
      globalThis.__h.replace({ entries: [{ key: 'k', message: { _: 'message', id: 1, message: 'edited' } }], next: 'ignored' });
      globalThis.__h.remove(['k']);
      globalThis.__h.setUnreadCount(4);
      globalThis.__h.setButton({ text: 'Marked' });
      globalThis.__h.setButton(null);
      globalThis.__h.setButton({ text: 'Again' });
      globalThis.__h.scrollTo('k');
      globalThis.__h.close();
    "#,
  );
  {
    let updates = host.updates.borrow();
    assert_eq!(
      updates[0],
      (1, OP_APPEND, r#"{"entries":[{"message":{"_":"message","id":1},"key":"k"}],"unreadCount":2}"#.to_string())
    );
    assert_eq!(
      updates[1],
      (
        1,
        OP_REPLACE,
        r#"{"entries":[{"message":{"_":"message","id":1,"message":"edited"},"key":"k"}]}"#.to_string()
      )
    );
    assert_eq!(updates[2], (1, OP_REMOVE, r#"["k"]"#.to_string()));
    assert_eq!(updates[3], (1, OP_UNREAD_COUNT, "4".to_string()));
    assert_eq!(updates[4], (1, OP_BUTTON, r#"{"text":"Marked"}"#.to_string()));
    assert_eq!(updates[5], (1, OP_BUTTON, "null".to_string()));
    assert_eq!(updates[6], (1, OP_BUTTON, r#"{"text":"Again"}"#.to_string()));
    assert_eq!(updates[7], (1, OP_SCROLL, "k".to_string()));

    assert!(catch_json(&ctx, "globalThis.__h.setUnreadCount(-1)").contains("non-negative"));
    assert!(catch_json(&ctx, "globalThis.__h.scrollTo(1)").contains("string key"));
    assert_eq!(*host.closed.borrow(), vec![1]);
  }
  assert_eq!(eval_json(&ctx, "globalThis.__closed === true"), "false");
  state.dispatch_read(&ctx, 1, "k");
  assert_eq!(eval_json(&ctx, "globalThis.__read"), r#""k""#);
  state.dispatch_button_click(&ctx, 1);
  assert_eq!(eval_json(&ctx, "globalThis.__clicks"), "1");
  state.dispatch_header_tap(&ctx, 1);
  assert_eq!(eval_json(&ctx, "globalThis.__headerTaps"), "1");
  state.dispatch_menu(&ctx, 1, 7);
  state.dispatch_menu(&ctx, 1, 8);
  assert_eq!(
    *host.menus.borrow(),
    [7, 8].map(|id| (id, r#"S[{"text":"Refresh","icon":"p-1:42","danger":false},{"text":"Muted","danger":true,"checked":false}]"#.to_string()))
  );
  state.dispatch_menu_click(&ctx, 1, 7, 0);
  assert_eq!(eval_json(&ctx, "globalThis.__menu ?? null"), "null", "a click on a replaced menu is dropped");
  state.dispatch_menu_click(&ctx, 1, 8, 0);
  state.dispatch_menu_click(&ctx, 1, 8, 5);
  assert_eq!(eval_json(&ctx, "globalThis.__menu"), r#""refresh""#);
  state.dispatch_closed(&ctx, 1);
  state.dispatch_read(&ctx, 1, "later");
  assert_eq!(eval_json(&ctx, "globalThis.__read"), r#""k""#);
  assert_eq!(eval_json(&ctx, "globalThis.__closed === true"), "true");
  let caught = catch_json(&ctx, "globalThis.__h.append({ entries: [] })");
  assert!(caught.contains("handle-expired"), "{caught}");
  state.dispatch_load_around(&ctx, 1, 12, "I");
  assert_eq!(host.pages.borrow().last().unwrap().1, "Ethe chat history was closed");
}

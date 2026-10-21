use super::*;
use crate::testing::harness::{install_sandbox_globals, TestDir};
use rquickjs::{Context, Runtime};

struct Fixture {
  rt: Runtime,
  ctx: Context,
  _spill: TestDir,
}

fn setup(dir: &TestDir) -> Fixture {
  let (rt, ctx) = crate::testing::harness::new_engine();
  let spill = TestDir::new("assets-spill");
  fs::write(dir.path().join("0"), b"\x89PNG").unwrap();
  let entries = [
    "icons/frame.png".to_string(),
    "image/png".to_string(),
    dir.path().join("0").to_string_lossy().into_owned(),
    "gone.bin".to_string(),
    "application/octet-stream".to_string(),
    dir.path().join("1").to_string_lossy().into_owned(),
  ];
  ctx.with(|ctx| {
    let inu = crate::testing::harness::get_api_globals(&ctx);
    install_sandbox_globals(&ctx, spill.path()).unwrap();
    install_assets(&ctx, parse_assets(&entries), &inu).unwrap();
  });
  Fixture { rt, ctx, _spill: spill }
}

fn settle(f: &Fixture, expr: &str) -> String {
  crate::testing::harness::eval_unit(
    &f.ctx,
    &format!(
      r#"
        globalThis.__out = 'pending';
        Promise.resolve().then(() => {expr}).then(
          v => {{ globalThis.__out = 'ok:' + v; }},
          e => {{ globalThis.__out = `${{e.code ?? e.name}}:${{e.message}}`; }},
        );
      "#,
    ),
  );
  while f.rt.is_job_pending() {
    f.rt.execute_pending_job().ok();
  }
  crate::testing::harness::eval_string(&f.ctx, "String(globalThis.__out)")
}

#[test]
fn an_asset_is_a_named_typed_file_over_the_extracted_bytes() {
  let dir = TestDir::new("assets");
  let f = setup(&dir);
  assert_eq!(
    settle(&f, "(f => [f instanceof File, f.name, f.type, f.size].join('|'))(inu.assets.get('icons/frame.png'))"),
    "ok:true|frame.png|image/png|4",
  );
  assert_eq!(settle(&f, "inu.assets.get('icons/frame.png').bytes().then(b => [...b].join(','))"), "ok:137,80,78,71");
}

#[test]
fn an_unknown_or_missing_asset_throws() {
  let dir = TestDir::new("assets-missing");
  let f = setup(&dir);
  assert_eq!(settle(&f, "inu.assets.get('nope.png')"), "not-found:assets.get: no asset named 'nope.png'");
  assert_eq!(settle(&f, "inu.assets.get('gone.bin')"), "handle-expired:assets.get: the file behind 'gone.bin' is gone");
}

#[test]
fn disposing_an_asset_handle_keeps_the_file_for_the_next_one() {
  let dir = TestDir::new("assets-dispose");
  let f = setup(&dir);
  crate::testing::harness::eval_unit(&f.ctx, "inu.assets.get('icons/frame.png').dispose()");
  assert!(dir.path().join("0").exists());
  assert_eq!(settle(&f, "inu.assets.get('icons/frame.png').size"), "ok:4");
}

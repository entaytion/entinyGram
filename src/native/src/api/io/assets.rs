use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

use rquickjs::{Ctx, Function, Object, Result as JsResult, Value};

use crate::api::error::PluginErrorCode;
use crate::api::io::blob::{mint_app_file, mtime_millis};

pub struct Asset {
  pub mime: String,
  pub path: PathBuf,
}

/// [entries] is `[name, mime, path]` triples, as the host flattens them
pub fn parse_assets(entries: &[String]) -> HashMap<String, Asset> {
  entries
    .chunks_exact(3)
    .map(|triple| {
      let asset = Asset {
        mime: triple[1].clone(),
        path: PathBuf::from(&triple[2]),
      };
      (triple[0].clone(), asset)
    })
    .collect()
}

pub fn install_assets<'js>(
  ctx: &Ctx<'js>,
  assets: HashMap<String, Asset>,
  globals: &crate::api::Globals<'js>,
) -> JsResult<()> {
  let namespace = Object::new(ctx.clone())?;
  namespace.set(
    "get",
    Function::new(ctx.clone(), move |ctx: Ctx<'js>, name: String| -> JsResult<Value<'js>> {
      let Some(asset) = assets.get(&name) else {
        return PluginErrorCode::NotFound.throw(&ctx, &format!("assets.get: no asset named '{name}'"));
      };
      let Ok(meta) = fs::metadata(&asset.path) else {
        return PluginErrorCode::HandleExpired.throw(&ctx, &format!("assets.get: the file behind '{name}' is gone"));
      };
      let file_name = name.rsplit('/').next().unwrap_or(&name);
      mint_app_file(&ctx, &asset.path, meta.len(), &asset.mime, Some(file_name), mtime_millis(meta.modified().ok()))
    })?,
  )?;
  globals.inu.set("assets", namespace)?;
  Ok(())
}

#[cfg(test)]
#[path = "assets_tests.rs"]
mod tests;

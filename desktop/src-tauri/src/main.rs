//! Earmark desktop: the shared ClojureScript UI plus local marking/reading via the wmark crate.
//! Audio never leaves the machine; only payload candidates and fingerprints go to the server.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::path::PathBuf;
use tauri_plugin_opener::OpenerExt;

#[tauri::command]
async fn embed(input: String, output: String, payload: String) -> Result<wmark::EmbedReport, String> {
    tauri::async_runtime::spawn_blocking(move || {
        wmark::embed(&PathBuf::from(input), &PathBuf::from(output), &payload)
    })
    .await
    .map_err(|e| e.to_string())?
    .map_err(|e| format!("{e:#}"))
}

#[tauri::command]
async fn detect(input: String) -> Result<wmark::DetectReport, String> {
    tauri::async_runtime::spawn_blocking(move || wmark::detect(&PathBuf::from(input)))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| format!("{e:#}"))
}

#[tauri::command]
fn reveal(app: tauri::AppHandle, path: String) -> Result<(), String> {
    app.opener().reveal_item_in_dir(path).map_err(|e| e.to_string())
}

fn main() {
    // WebKitGTK's DMA-BUF renderer shows a blank window on NVIDIA drivers. Must be set before
    // the webview starts; respect an explicit user setting.
    #[cfg(target_os = "linux")]
    if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![embed, detect, reveal])
        .run(tauri::generate_context!())
        .expect("error while running earmark");
}

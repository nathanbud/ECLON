mod print;

use tauri::Manager;
use tauri_plugin_window_state::StateFlags;

/// Prints the page in the calling window to the default printer with no print window.
#[tauri::command]
async fn print_silent(window: tauri::WebviewWindow) -> Result<(), String> {
  print::silent(window).await
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  tauri::Builder::default()
    // must come first: a second launch hands over to the running copy and quits, so one PC never has two
    // sign-ins or two drawers open side by side
    .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
      if let Some(main) = app.get_webview_window("main") {
        let _ = main.unminimize();
        let _ = main.show();
        let _ = main.set_focus();
      }
    }))
    // the main window reopens where and as large as it was left; the loan and loan setup windows place themselves
    .plugin(
      tauri_plugin_window_state::Builder::default()
        .with_state_flags(StateFlags::SIZE | StateFlags::POSITION | StateFlags::MAXIMIZED)
        .with_filter(|label| label == "main")
        .build(),
    )
    // a log file in the app's log folder (%LOCALAPPDATA%\ph.coop.platform\logs) in the installed app too, for support;
    // the console as well while developing
    .plugin(
      tauri_plugin_log::Builder::default()
        .level(if cfg!(debug_assertions) { log::LevelFilter::Info } else { log::LevelFilter::Warn })
        .max_file_size(2_000_000)
        .rotation_strategy(tauri_plugin_log::RotationStrategy::KeepSome(5))
        .build(),
    )
    .invoke_handler(tauri::generate_handler![print_silent])
    .run(tauri::generate_context!())
    .expect("error while building tauri application");
}

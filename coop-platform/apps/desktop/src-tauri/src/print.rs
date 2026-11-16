//! Silent printing: sends the page as it would print (the app's print style shows only the invoice) to the computer's default printer, with no print window.
//! The web view's own print function does the work (WebView2 `Print` with an empty printer name, which means the default printer).

#[cfg(windows)]
pub async fn silent(window: tauri::WebviewWindow) -> Result<(), String> {
    use std::sync::mpsc;
    use std::time::Duration;
    use webview2_com::Microsoft::Web::WebView2::Win32::{ICoreWebView2Environment6, ICoreWebView2_16, COREWEBVIEW2_PRINT_STATUS_SUCCEEDED};
    use webview2_com::PrintCompletedHandler;
    use windows::core::Interface;

    let (tx, rx) = mpsc::channel::<Result<(), String>>();
    let failed = tx.clone();
    window
        .with_webview(move |wv| {
            let start = || -> Result<(), String> {
                // SAFETY: called on the web view's own thread with the interfaces the web view just gave us.
                unsafe {
                    let core = wv.controller().CoreWebView2().map_err(|e| e.to_string())?;
                    let core16: ICoreWebView2_16 = core.cast().map_err(|e| e.to_string())?;
                    let env: ICoreWebView2Environment6 = wv.environment().cast().map_err(|e| e.to_string())?;
                    let settings = env.CreatePrintSettings().map_err(|e| e.to_string())?;
                    // no browser header or footer (date, address, page number) and no margins of its own: the invoice sets its own
                    let _ = settings.SetShouldPrintHeaderAndFooter(false);
                    let _ = settings.SetShouldPrintBackgrounds(false);
                    let _ = settings.SetMarginTop(0.0);
                    let _ = settings.SetMarginBottom(0.0);
                    let _ = settings.SetMarginLeft(0.0);
                    let _ = settings.SetMarginRight(0.0);
                    let done = tx.clone();
                    let handler = PrintCompletedHandler::create(Box::new(move |result, status| {
                        let outcome = match result {
                            Ok(()) if status == COREWEBVIEW2_PRINT_STATUS_SUCCEEDED => Ok(()),
                            Ok(()) => Err("The printer did not accept the invoice. Check that a default printer is set and online.".to_string()),
                            Err(e) => Err(e.to_string()),
                        };
                        let _ = done.send(outcome);
                        Ok(())
                    }));
                    core16.Print(&settings, &handler).map_err(|e| e.to_string())
                }
            };
            if let Err(e) = start() {
                let _ = failed.send(Err(e));
            }
        })
        .map_err(|e| e.to_string())?;
    // wait for the printer's answer without holding up the window
    tauri::async_runtime::spawn_blocking(move || rx.recv_timeout(Duration::from_secs(60)).unwrap_or_else(|_| Err("The printer did not answer.".to_string())))
        .await
        .map_err(|e| e.to_string())?
}

#[cfg(not(windows))]
pub async fn silent(_window: tauri::WebviewWindow) -> Result<(), String> {
    Err("Printing without a print window is only available on Windows.".to_string())
}

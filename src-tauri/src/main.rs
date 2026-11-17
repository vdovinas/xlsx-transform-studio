#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde_json::{json, Value};
#[cfg(windows)]
use std::os::windows::process::CommandExt;
use std::{
    fs,
    io::{BufRead, BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{ChildStdin, Command, Stdio},
    sync::Mutex,
};
use tauri::{Emitter, Manager};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons};

type Result<T> = std::result::Result<T, String>;
#[derive(Default)]
struct Session {
    input: Option<PathBuf>,
    output: Option<PathBuf>,
    busy: bool,
    cancelled: bool,
    stdin: Option<ChildStdin>,
}
#[derive(Default)]
struct State {
    session: Mutex<Session>,
    drafts: Mutex<()>,
}
fn error(e: impl std::fmt::Display) -> String {
    e.to_string()
}
fn resource(app: &tauri::AppHandle, name: &str) -> Result<PathBuf> {
    if cfg!(debug_assertions) {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
        Ok(root.join(if name == "engine" {
            "tools/engine"
        } else {
            "src/main/resources/profiles.json"
        }))
    } else {
        Ok(app.path().resource_dir().map_err(error)?.join(name))
    }
}
fn csv(v: &Value) -> bool {
    v.is_object()
        && ["auto", "UTF-8", "windows-1251"].contains(&v["encoding"].as_str().unwrap_or(""))
        && ["auto", ";", ",", "\t"].contains(&v["delimiter"].as_str().unwrap_or(""))
}
fn optional_list(v: &Value, check: impl Fn(&Value) -> bool) -> bool {
    v.is_null() || v.as_array().is_some_and(|a| a.iter().all(check))
}
fn condition(v: &Value) -> bool {
    v.is_object()
        && v["source"].is_string()
        && v["value"].is_string()
        && [
            "EQUALS",
            "NOT_EQUALS",
            "CONTAINS",
            "NOT_CONTAINS",
            "STARTS_WITH",
            "EMPTY",
            "NOT_EMPTY",
            "GREATER",
            "LESS",
            "DATE_BEFORE",
            "DATE_AFTER",
        ]
        .contains(&v["operator"].as_str().unwrap_or(""))
        && (v["ignoreCase"].is_null() || v["ignoreCase"].is_boolean())
}
fn group(v: &Value) -> bool {
    v.is_object()
        && (v["matchAll"].is_null() || v["matchAll"].is_boolean())
        && optional_list(&v["conditions"], condition)
}
fn validate(v: &Value) -> Result<()> {
    let valid = group(v)
        && v["name"].is_string()
        && v["sheetName"].is_string()
        && v["headerRow"]
            .as_u64()
            .is_some_and(|n| (1..=1048576).contains(&n))
        && (v["csv"].is_null() || csv(&v["csv"]))
        && optional_list(&v["filters"], condition)
        && optional_list(&v["filterGroups"], group)
        && v["fields"].as_array().is_some_and(|fields| {
            fields.len() <= 16384
                && fields.iter().all(|f| {
                    f.is_object()
                        && f["name"].is_string()
                        && f["source"].is_string()
                        && ["TEXT", "NUMBER", "DATE"].contains(&f["kind"].as_str().unwrap_or(""))
                        && (f["format"].is_null() || f["format"].is_string())
                        && (f["required"].is_null() || f["required"].is_boolean())
                        && optional_list(&f["steps"], |s| {
                            group(s)
                                && [
                                    "TRIM",
                                    "LOWER",
                                    "UPPER",
                                    "REPLACE",
                                    "EXTRACT",
                                    "CONSTANT",
                                    "IF_EMPTY",
                                    "IF_EMPTY_SOURCE",
                                    "CLASSIFY",
                                    "NUMBER",
                                    "DATE",
                                ]
                                .contains(&s["action"].as_str().unwrap_or(""))
                                && (s["argument"].is_null() || s["argument"].is_string())
                                && (s["replacement"].is_null() || s["replacement"].is_string())
                                && optional_list(&s["cases"], |b| {
                                    group(b) && b["value"].is_string()
                                })
                        })
                })
        })
        && v.to_string().len() <= 5_000_000;
    if valid {
        Ok(())
    } else {
        Err("Некорректный профиль. Проверьте настройки профиля.".into())
    }
}
fn validate_collection(v: &Value) -> Result<()> {
    let profiles = v["profiles"]
        .as_array()
        .filter(|p| !p.is_empty())
        .ok_or("Некорректная коллекция профилей")?;
    let mut names = std::collections::HashSet::new();
    for profile in profiles {
        validate(profile)?;
        let name = profile["name"].as_str().unwrap().trim();
        if name.is_empty() {
            return Err("Введите название профиля".into());
        }
        if !names.insert(name.to_lowercase()) {
            return Err("Профиль с таким названием уже существует".into());
        }
    }
    if !v["active"]
        .as_u64()
        .is_some_and(|i| i < profiles.len() as u64)
        || v.to_string().len() > 5_000_000
    {
        return Err("Некорректная коллекция профилей".into());
    }
    Ok(())
}
fn read_json(path: &Path) -> Result<Value> {
    if fs::metadata(path).map_err(error)?.len() > 5_000_000 {
        return Err("Слишком большой профиль".into());
    }
    serde_json::from_slice(&fs::read(path).map_err(error)?).map_err(error)
}
fn atomic_json(path: &Path, v: &Value) -> Result<()> {
    let temp = path.with_extension(format!("{}.tmp", std::process::id()));
    let result = (|| {
        let mut file = fs::File::create(&temp).map_err(error)?;
        file.write_all(&serde_json::to_vec_pretty(v).map_err(error)?)
            .map_err(error)?;
        file.sync_all().map_err(error)?;
        drop(file);
        // Windows rename replaces an existing file atomically through MoveFileExW below.
        replace(&temp, path)
    })();
    let _ = fs::remove_file(&temp);
    result
}
#[cfg(windows)]
fn replace(from: &Path, to: &Path) -> Result<()> {
    use std::os::windows::ffi::OsStrExt;
    #[link(name = "kernel32")]
    extern "system" {
        fn MoveFileExW(from: *const u16, to: *const u16, flags: u32) -> i32;
    }
    let a: Vec<_> = from.as_os_str().encode_wide().chain(Some(0)).collect();
    let b: Vec<_> = to.as_os_str().encode_wide().chain(Some(0)).collect();
    if unsafe { MoveFileExW(a.as_ptr(), b.as_ptr(), 1 | 8) } == 0 {
        Err(error(std::io::Error::last_os_error()))
    } else {
        Ok(())
    }
}
#[cfg(not(windows))]
fn replace(from: &Path, to: &Path) -> Result<()> {
    fs::rename(from, to).map_err(error)
}
fn cancel(app: &tauri::AppHandle) -> Result<()> {
    let state = app.state::<State>();
    let mut s = state.session.lock().map_err(error)?;
    s.cancelled = true;
    if let Some(stdin) = &mut s.stdin {
        stdin.write_all(b"cancel\n").map_err(error)?;
    }
    Ok(())
}
fn run(app: &tauri::AppHandle, request: Value) -> Result<Value> {
    let engine = resource(app, "engine")?;
    run_engine(&engine, request, &app.state::<State>(), |count| {
        let _ = app.emit("progress", count);
    })
}
fn run_engine(
    engine: &Path,
    request: Value,
    state: &State,
    progress: impl Fn(&Value),
) -> Result<Value> {
    let mut command = Command::new(engine.join("runtime/bin/java.exe"));
    command
        .args(["-Xmx1536m", "-Dfile.encoding=UTF-8", "-cp"])
        .arg(engine.join("xlsx-desktop.jar"))
        .arg("local.xlsx.cli.EngineCommand")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let mut child = command.spawn().map_err(error)?;
    let mut stdin = child.stdin.take().unwrap();
    if let Err(e) = writeln!(stdin, "{request}") {
        let _ = child.kill();
        let _ = child.wait();
        return Err(error(e));
    }
    {
        let mut s = state.session.lock().map_err(error)?;
        if s.cancelled {
            let _ = stdin.write_all(b"cancel\n");
        }
        s.stdin = Some(stdin);
    }
    let stderr = child.stderr.take().unwrap();
    let errors = std::thread::spawn(move || {
        let mut tail = std::collections::VecDeque::with_capacity(4000);
        for byte in BufReader::new(stderr).bytes().flatten() {
            if tail.len() == 4000 {
                tail.pop_front();
            }
            tail.push_back(byte);
        }
        String::from_utf8_lossy(&tail.into_iter().collect::<Vec<_>>()).into_owned()
    });
    let mut received = Value::Null;
    for line in BufReader::new(child.stdout.take().unwrap()).lines() {
        match line {
            Ok(line) => {
                if let Ok(v) = serde_json::from_str::<Value>(&line) {
                    if v["type"] == "progress" {
                        progress(&v["count"]);
                    } else {
                        received = v;
                    }
                }
            }
            Err(_) => break,
        }
    }
    let status = child.wait().map_err(error);
    state.session.lock().map_err(error)?.stdin = None;
    let stderr = errors.join().unwrap_or_default();
    let status = status?;
    if received["type"] == "cancelled" {
        Ok(json!({"cancelled":true}))
    } else if status.success() && received["type"] == "result" {
        Ok(received["value"].take())
    } else {
        Err(received["message"]
            .as_str()
            .unwrap_or(if stderr.is_empty() {
                "Обработчик завершился без результата"
            } else {
                &stderr
            })
            .into())
    }
}
fn choose(
    app: &tauri::AppHandle,
    save: bool,
    name: &str,
    extensions: &[&str],
) -> Result<Option<PathBuf>> {
    let dialog = app
        .dialog()
        .file()
        .add_filter(name, extensions)
        .set_parent(&app.get_webview_window("main").ok_or("Окно закрыто")?);
    let file = if save {
        dialog.set_file_name(name).blocking_save_file()
    } else {
        dialog.blocking_pick_file()
    };
    file.map(|f| f.into_path().map_err(error)).transpose()
}
fn destination(app: &tauri::AppHandle, name: &str, extension: &str) -> Result<Option<PathBuf>> {
    let Some(mut path) = choose(app, true, name, &[extension])? else {
        return Ok(None);
    };
    if !path
        .to_string_lossy()
        .to_lowercase()
        .ends_with(&format!(".{extension}"))
    {
        path = PathBuf::from(format!("{}.{extension}", path.display()));
    }
    if path.exists()
        && !app
            .dialog()
            .message(format!("Заменить существующий файл?\n{}", path.display()))
            .title("Подтверждение замены")
            .buttons(MessageDialogButtons::OkCancel)
            .blocking_show()
    {
        return Ok(None);
    }
    Ok(Some(path))
}
fn dispatch(app: &tauri::AppHandle, operation: &str, request: Value) -> Result<Value> {
    let state = app.state::<State>();
    match operation {
        "bootstrap" => {
            let folder = app.path().app_data_dir().map_err(error)?;
            fs::create_dir_all(&folder).map_err(error)?;
            let collection = folder.join("profiles.json");
            if collection.exists() {
                let saved = read_json(&collection)?;
                validate_collection(&saved)?;
                return Ok(saved);
            }
            let draft = read_json(&folder.join("draft.json"))
                .ok()
                .filter(|v| validate(v).is_ok());
            // Preserve drafts created by the previous Electron application.
            let draft = draft.or_else(|| {
                std::env::var_os("APPDATA")
                    .and_then(|base| {
                        read_json(&PathBuf::from(base).join("xlsx-studio/draft.json")).ok()
                    })
                    .filter(|v| validate(v).is_ok())
            });
            Ok(json!({"profiles":read_json(&resource(app,"profiles.json")?)?,"draft":draft}))
        }
        "choose-input" => {
            let Some(path) = choose(app, false, "Таблицы XLSX и CSV", &["xlsx", "csv"])?
            else {
                return Ok(Value::Null);
            };
            let format = path
                .extension()
                .unwrap_or_default()
                .to_string_lossy()
                .to_lowercase();
            if !["xlsx", "csv"].contains(&format.as_str()) {
                return Err("Выберите XLSX или CSV".into());
            }
            state.session.lock().map_err(error)?.input = Some(path.clone());
            Ok(
                json!({"name":path.file_name().unwrap_or_default().to_string_lossy(),"path":path,"format":format}),
            )
        }
        "inspect" | "convert" => {
            let input = state
                .session
                .lock()
                .map_err(error)?
                .input
                .clone()
                .ok_or("Сначала выберите файл")?;
            if !request["sheet"]
                .as_u64()
                .is_some_and(|n| n <= i32::MAX as u64)
            {
                return Err("Выберите лист".into());
            }
            let mut job = json!({"operation":operation,"input":input,"sheet":request["sheet"]});
            if operation == "inspect" {
                if !request["headerRow"]
                    .as_u64()
                    .is_some_and(|n| (1..=1048576).contains(&n))
                    || (!request["csv"].is_null() && !csv(&request["csv"]))
                {
                    return Err("Проверьте строку заголовков и настройки CSV".into());
                }
                job["headerRow"] = request["headerRow"].clone();
                job["csv"] = request["csv"].clone();
                run(app, job)
            } else {
                validate(&request["profile"])?;
                let name = format!(
                    "{}-результат.xlsx",
                    input.file_stem().unwrap_or_default().to_string_lossy()
                );
                let Some(output) = destination(app, &name, "xlsx")? else {
                    return Ok(Value::Null);
                };
                job["output"] = json!(output);
                job["profile"] = request["profile"].clone();
                let mut result = run(app, job)?;
                if result["cancelled"] != true {
                    state.session.lock().map_err(error)?.output = Some(output.clone());
                }
                result["path"] = json!(output);
                Ok(result)
            }
        }
        "confirm-profile-deletion" => {
            let name = request.as_str().ok_or("Некорректное название профиля")?;
            Ok(json!(app
                .dialog()
                .message(format!(
                    "Удалить профиль «{name}»? Это действие нельзя отменить."
                ))
                .title("Удаление профиля")
                .buttons(MessageDialogButtons::OkCancel)
                .blocking_show()))
        }
        "save-profiles" => {
            validate_collection(&request)?;
            let _guard = state.drafts.lock().map_err(error)?;
            let folder = app.path().app_data_dir().map_err(error)?;
            fs::create_dir_all(&folder).map_err(error)?;
            atomic_json(&folder.join("profiles.json"), &request)?;
            Ok(json!(true))
        }
        "reveal-output" => {
            if let Some(path) = &state.session.lock().map_err(error)?.output {
                Command::new("explorer.exe")
                    .arg(format!("/select,{}", path.display()))
                    .spawn()
                    .map_err(error)?;
            }
            Ok(Value::Null)
        }
        _ => Err("Неизвестная операция".into()),
    }
}
#[tauri::command]
async fn studio(app: tauri::AppHandle, operation: String, request: Value) -> Result<Value> {
    if operation == "cancel" {
        cancel(&app)?;
        return Ok(Value::Null);
    }
    let exclusive = ["choose-input", "inspect", "convert"].contains(&operation.as_str());
    if exclusive {
        let state = app.state::<State>();
        let mut s = state.session.lock().map_err(error)?;
        if s.busy {
            return Err("Дождитесь завершения текущей операции".into());
        }
        s.busy = true;
        s.cancelled = false;
    }
    let worker = app.clone();
    let result =
        tauri::async_runtime::spawn_blocking(move || dispatch(&worker, &operation, request))
            .await
            .map_err(error);
    if exclusive {
        app.state::<State>().session.lock().map_err(error)?.busy = false;
    }
    result?
}
fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(State::default())
        .invoke_handler(tauri::generate_handler![studio])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if window
                    .state::<State>()
                    .session
                    .lock()
                    .map(|s| s.busy)
                    .unwrap_or(true)
                {
                    api.prevent_close();
                    let _ = cancel(window.app_handle());
                    let _ = window.emit("closing", ());
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("Не удалось запустить XLSX Studio");
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn engine_protocol_and_early_cancel() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
        let engine = root.join("tools/engine");
        let fixture = root.join("target/electron-fixture.xlsx");
        let state = State::default();
        let inspected = run_engine(
            &engine,
            json!({"operation":"inspect", "input":fixture, "sheet":0, "headerRow":1}),
            &state,
            |_| {},
        )
        .unwrap();
        assert_eq!(inspected["columns"].as_array().unwrap().len(), 5);
        let output = root.join("target/tauri-cancelled.xlsx");
        fs::write(&output, "existing data").unwrap();
        state.session.lock().unwrap().cancelled = true;
        let result = run_engine(&engine,json!({"operation":"convert","input":fixture,"output":output,"sheet":0,"profile":{"name":"Тест","sheetName":"Результат","headerRow":1,"fields":[{"name":"Сумма","source":"Сумма","kind":"NUMBER","steps":[]}]}}),&state, |_| {}).unwrap();
        assert_eq!(result, json!({"cancelled":true}));
        assert_eq!(fs::read_to_string(output).unwrap(), "existing data");
    }
    #[test]
    fn profiles_and_invalid_rules() {
        let profiles: Value =
            serde_json::from_str(include_str!("../../src/main/resources/profiles.json")).unwrap();
        for profile in profiles.as_array().unwrap() {
            validate(profile).unwrap();
        }
        let mut profile = profiles[0].clone();
        profile["fields"][0]["steps"] = json!([{"action":"EXECUTE"}]);
        assert!(validate(&profile).is_err());
        assert!(validate(&json!({"fields":null})).is_err());
    }
    #[test]
    fn collection_names_and_selection() {
        let p = json!({"name":"Банк", "sheetName":"Результат", "headerRow":1, "fields":[]});
        let mut collection = json!({"profiles":[p.clone()], "active":0});
        validate_collection(&collection).unwrap();
        let mut duplicate = p.clone();
        duplicate["name"] = json!(" банк ");
        collection["profiles"]
            .as_array_mut()
            .unwrap()
            .push(duplicate);
        assert!(validate_collection(&collection).is_err());
        collection["profiles"][1]["name"] = json!("Банк 2");
        validate_collection(&collection).unwrap();
        collection["active"] = json!(2);
        assert!(validate_collection(&collection).is_err());
        collection["active"] = json!(0);
        collection["profiles"][1]["name"] = json!("  ");
        assert!(validate_collection(&collection).is_err());
    }
    #[test]
    fn atomic_replacement_preserves_unicode() {
        let path = std::env::temp_dir().join(format!("xlsx-studio-{}.json", std::process::id()));
        atomic_json(&path, &json!({"name":"Первый"})).unwrap();
        atomic_json(&path, &json!({"name":"Второй"})).unwrap();
        assert_eq!(read_json(&path).unwrap()["name"], "Второй");
        fs::remove_file(path).unwrap();
    }
}

//! 青简 Android 壳的 JNI 桥。
//!
//! 只有四件事：建会话（加载词库）、喂按键拿候选、按下标上屏、销毁。
//! 跨语言边界一律走 JSON 字符串，Kotlin 侧用 org.json 解析，省得维护一堆 JNI 对象。
//!
//! 依赖刻意只挂 `qingjian-core` / `qingjian-dictionary` / `qingjian-learning`：
//! 上游把 `qingjian-platform` 拉进来就会经 `qingjian-predict → async-openai → reqwest(native-tls)`
//! 落到 `openssl-sys`，在 `aarch64-linux-android` 上编不过（上游 issue #235）。

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};

use qingjian_core::{Candidate, Engine};
use qingjian_dictionary::Dictionary;
use qingjian_learning::FrequencyLearner;

/// 一个输入会话：引擎 + 上一次查询的候选（上屏时按下标取）。
struct Session {
    engine: Engine,
    candidates: Vec<Candidate>,
}

impl Session {
    fn new(dict_path: &str) -> Result<Self, String> {
        let dictionary = Dictionary::from_path(dict_path).map_err(|e| format!("词库加载失败: {e}"))?;
        let engine = Engine::new(dictionary).with_learner(Box::new(FrequencyLearner::default()));
        Ok(Self {
            engine,
            candidates: Vec::new(),
        })
    }
}

/// 把 JSON 字符串交给 Kotlin；构造失败时退化成一段合法的 JSON 错误对象。
fn reply(env: &mut JNIEnv, payload: String) -> jstring {
    match env.new_string(payload) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn error_json(message: &str) -> String {
    let escaped = message.replace('\\', "\\\\").replace('"', "\\\"");
    format!("{{\"error\":\"{escaped}\"}}")
}

/// 会话指针有 0 与悬垂两种坏情况，统一在这里挡掉。
unsafe fn session<'a>(handle: jlong) -> Option<&'a mut Session> {
    if handle == 0 {
        None
    } else {
        Some(unsafe { &mut *(handle as *mut Session) })
    }
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.into())
        .map_err(|e| format!("JNI 取字符串失败: {e}"))
}

/// 建会话，返回非 0 句柄；失败返回 0（错误细节走日志）。
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_ime_NativeEngine_nativeCreate(
    mut env: JNIEnv,
    _this: JObject,
    dict_path: JString,
) -> jlong {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = read_string(&mut env, &dict_path)?;
        let session = Session::new(&path)?;
        Ok::<jlong, String>(Box::into_raw(Box::new(session)) as jlong)
    }));
    match result {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => {
            tracing::error!(%message, "建会话失败");
            0
        }
        Err(_) => {
            tracing::error!("建会话时 panic");
            0
        }
    }
}

/// 喂按键：返回 `{"keys":…,"preedit":…,"candidates":[{"text":…,"syllables":[…]}]}`。
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_ime_NativeEngine_nativeSetInput(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    keys: JString,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let keys = read_string(&mut env, &keys)?;
        let session = unsafe { session(handle) }.ok_or_else(|| "会话句柄无效".to_owned())?;
        session.engine.clear();
        session.engine.set_input(&keys);
        let query = session.engine.query().map_err(|e| format!("查询失败: {e}"))?;
        session.candidates = query.candidates.items.clone();
        let items: Vec<serde_json::Value> = session
            .candidates
            .iter()
            .map(|c| {
                serde_json::json!({
                    "text": c.text,
                    "syllables": c.syllables,
                    "auxCode": c.aux_code,
                })
            })
            .collect();
        Ok::<String, String>(
            serde_json::json!({
                "keys": keys,
                "preedit": session.engine.composition().text(),
                "candidates": items,
            })
            .to_string(),
        )
    }));
    match result {
        Ok(Ok(json)) => reply(&mut env, json),
        Ok(Err(message)) => reply(&mut env, error_json(&message)),
        Err(_) => reply(&mut env, error_json("查询时 panic")),
    }
}

/// 上屏第 `index` 个候选：返回 `{"text":"上屏文本","preedit":"剩余拼音","candidates":[…]}`。
/// 候选比输入短时（`kaifazhe` 选「开发」），剩余拼音留在缓冲区，这里顺手再查一次。
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_ime_NativeEngine_nativeCommit(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    index: jint,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let session = unsafe { session(handle) }.ok_or_else(|| "会话句柄无效".to_owned())?;
        let index = index.max(0) as usize;
        let candidate = session
            .candidates
            .get(index)
            .cloned()
            .ok_or_else(|| format!("没有第 {index} 个候选"))?;
        let text = session.engine.commit(&candidate);
        let rest = session.engine.composition().text().to_owned();
        let query = session.engine.query().map_err(|e| format!("查询失败: {e}"))?;
        session.candidates = query.candidates.items.clone();
        let items: Vec<serde_json::Value> = session
            .candidates
            .iter()
            .map(|c| {
                serde_json::json!({
                    "text": c.text,
                    "syllables": c.syllables,
                    "auxCode": c.aux_code,
                })
            })
            .collect();
        Ok::<String, String>(
            serde_json::json!({
                "text": text,
                "preedit": rest,
                "candidates": items,
            })
            .to_string(),
        )
    }));
    match result {
        Ok(Ok(json)) => reply(&mut env, json),
        Ok(Err(message)) => reply(&mut env, error_json(&message)),
        Err(_) => reply(&mut env, error_json("上屏时 panic")),
    }
}

/// 切私密输入：引擎自带「不学、不记、不发云端」开关（`Engine::set_private`）。
/// 壳在密码类输入框里置 true，同时自己也不再走引擎（按键直接上屏）。
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_ime_NativeEngine_nativeSetPrivate(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
    private: jboolean,
) {
    if handle == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Some(session) = unsafe { session(handle) } {
            session.engine.set_private(private != 0);
            if private != 0 {
                // 进私密输入时把候选与缓冲区一起丢掉，不留痕迹
                session.engine.clear();
                session.candidates.clear();
            }
        }
    }));
}

/// 清空缓冲区。
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_ime_NativeEngine_nativeClear(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let session = unsafe { session(handle) }.ok_or_else(|| "会话句柄无效".to_owned())?;
        session.engine.clear();
        session.candidates.clear();
        Ok::<String, String>(serde_json::json!({"preedit": "", "candidates": []}).to_string())
    }));
    match result {
        Ok(Ok(json)) => reply(&mut env, json),
        Ok(Err(message)) => reply(&mut env, error_json(&message)),
        Err(_) => reply(&mut env, error_json("清空时 panic")),
    }
}

/// 释放会话。重复调用是安全的调用方错误：这里只删一次，句柄由 Kotlin 侧置 0。
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_qingjian_ime_NativeEngine_nativeDestroy(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        drop(unsafe { Box::from_raw(handle as *mut Session) });
    }));
}

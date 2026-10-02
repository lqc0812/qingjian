package app.qingjian.ime

/**
 * Rust 侧的 JNI 入口。对应 `apps/android/native/src/lib.rs` 里的
 * `Java_app_qingjian_ime_NativeEngine_*`，方法名与签名必须一一对应。
 *
 * 跨边界一律用 JSON 字符串：Kotlin 侧用 org.json 解析，省得维护 JNI 对象。
 */
object NativeEngine {

    init {
        // cargo-ndk 产出的 libqingjian_android.so
        System.loadLibrary("qingjian_android")
    }

    /**
     * 建会话（同时加载词库）。成功返回非 0 句柄，失败返回 0。
     * 词库必须是真实文件路径：引擎用 mmap 打开，APK 里的 asset 得先复制到 filesDir。
     */
    external fun nativeCreate(dictPath: String): Long

    /** 喂按键，返回 `{"keys":…,"preedit":…,"candidates":[{"text":…,"syllables":[…]}]}`。 */
    external fun nativeSetInput(handle: Long, keys: String): String

    /** 上屏第 index 个候选，返回 `{"text":…,"preedit":…,"candidates":[…]}`。 */
    external fun nativeCommit(handle: Long, index: Int): String

    /** 清空缓冲区。 */
    external fun nativeClear(handle: Long): String

    /**
     * 切私密输入（密码类输入框）。引擎侧不学、不记、不发云端，并丢掉当前缓冲区与候选。
     * 壳侧同时不再把按键交给引擎——两边一起兜住。
     */
    external fun nativeSetPrivate(handle: Long, private: Boolean)

    /** 释放会话。 */
    external fun nativeDestroy(handle: Long)
}

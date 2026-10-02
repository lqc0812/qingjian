# 青简 Android 壳（第一版）

给青简做一个能在安卓上打字的外壳。**内核一行没改**，全部复用平台无关的
`qingjian-core` / `qingjian-dictionary` / `qingjian-learning`，只在中间加一层 JNI 桥。

```
apps/android/
├── app/                     Kotlin 侧：InputMethodService + 键盘 + 候选条
│   └── src/main/
│       ├── java/app/qingjian/ime/
│       │   ├── QingjianImeService.kt   输入法本体（喂按键、画候选、上屏）
│       │   ├── NativeEngine.kt         JNI 声明（对应 native/src/lib.rs）
│       │   └── EnableActivity.kt       一屏说明 + 跳系统输入法设置
│       ├── assets/dict.qj              主词库（3.4 MB，来自官方的 data 发行版）
│       └── res/, AndroidManifest.xml
└── native/                  Rust 侧：JNI 桥（cdylib）
    ├── Cargo.toml           crate 名 qingjian-android，产物 libqingjian_android.so
    └── src/lib.rs           建会话 / 喂按键 / 上屏 / 清空 / 释放
```

## 为什么这么切

上游卡安卓的地方只有一条依赖链：

```
qingjian-platform → qingjian-predict → async-openai → reqwest(native-tls) → openssl-sys
```

`native-tls` 在 Windows 走 schannel、Apple 走 Security.framework，**只有 Linux/Android 落到 openssl**，
交叉编译要 NDK + 交叉 openssl（上游 issue #235 记录的就是这个）。所以这层桥**不挂 `qingjian-platform`**，
只挂引擎栈：`qingjian-core + qingjian-dictionary + qingjian-learning`，纯 Rust，`aarch64-linux-android`
直接编得过。代价是第一版没有配置读取、没有云联想、没有检查更新。

## 跨语言接口

只有五个函数，全部走 JSON 字符串（Kotlin 侧用 `org.json` 解析，不维护 JNI 对象）：

| Rust (`Java_app_qingjian_ime_NativeEngine_*`) | 作用 | 返回 |
| --- | --- | --- |
| `nativeCreate(dictPath)` | 建会话、mmap 词库 | 句柄（0 = 失败） |
| `nativeSetInput(handle, keys)` | 喂按键 | `{"keys","preedit","candidates":[{"text","syllables","auxCode"}]}` |
| `nativeCommit(handle, index)` | 上屏第 index 个候选 | `{"text","preedit","candidates":[…]}` |
| `nativeClear(handle)` | 清缓冲区 | 同上（空） |
| `nativeDestroy(handle)` | 释放会话 | — |

`commit` 之后会顺手再查一次：候选比输入短时（`kaifazhe` 选「开发」）剩余拼音留在缓冲区，壳接着显示。

## 怎么编

本机不需要任何工具链，靠 GitHub Actions 的 Android runner 出包，见
`.github/workflows/android.yml`：装 NDK r27 → `cargo ndk -t arm64-v8a` 编 `.so` 到 `jniLibs/`
→ Gradle 打 debug APK → 传成 artifact。

本地想编的话：JDK 17、Android SDK/NDK、Rust 1.96 + `aarch64-linux-android`，然后

```bash
cargo install cargo-ndk
cd apps/android/native && cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
cd .. && gradle assembleDebug
```

## 隐私（实测，不是承诺）

输入法能看到你打的每一个字，所以这一版是按「结构上做不到」来做的：

| 项 | 事实 |
| --- | --- |
| Android 权限 | **零权限**：清单里没有一条 `uses-permission`，**没有 INTERNET**，因此无法联网上传任何东西 |
| 输入日志 | 引擎缺省就是 `NoInputLogger`（`Engine::new` 里写死），壳没替换 → 不记录按键与上屏内容 |
| 学习数据 | 只挂内存版 `FrequencyLearner`，**不落盘**；进程结束即消失 |
| 云联想 / 更新检查 | `qingjian-predict`、`qingjian-update` 这些 crate **没有编进 APK**（同时也是绕开 openssl 依赖的手段） |
| 磁盘写入 | 只有 `filesDir/dict.qj`（词库副本），没有配置、日志、历史文件 |
| 系统备份 | `allowBackup="false"` + `fullBackupContent="false"`，App 数据不会被同步到云端 |
| 密码类输入框 | 判定为密码框时：按键**直接上屏、不经过引擎**，并调 `Engine::set_private(true)`（引擎的私密输入：不学、不记、不发云端），同时清空缓冲区与候选 |

已知的一个小口子：这是 **debug 构建**（`android:debuggable="true"`）。在打开 USB 调试的手机上，
有物理访问权的人可以 `adb` 进 App 目录。日常用的话建议关掉 USB 调试，或者要一个自签名 release 包
（那样更新时需要先卸载重装，因为签名不同）。

## 第一版的能力与欠缺

有：全拼打字、候选条点击上屏、空格选首选、退格、中/英直输切换，词库 92,825 条（含成语等附加词库未装）。

没有（后续版本再说）：候选旁的译词（要 `glossary-*.qj`）、神经整句模型（要 `model.qjm` 53 MB + candle 在
Android 上跑通）、双拼/注音/模糊音、自定义短语、学习数据落盘、候选翻页与简拼优化。

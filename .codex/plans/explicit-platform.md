接下去帮我做另一件事. 由于每个子项目可以支持的平台有很大差异, 所以我想取消
KotlinMultiplatformExtension.configureAllTargets, 然后在每个子项目中直接配置平台. 请按照如下方案进行配置

```
**compression**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅、`js(nodejs)` ✅、`js(browser)` ✅、`wasmJs(nodejs)` ✅、`wasmJs(browser)` ✅、`wasmJs(d8)` ✅、`wasmWasi(nodejs)` ✅

---

**nbt**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅、`js(nodejs)` ✅、`js(browser)` ✅、`wasmJs(nodejs)` ✅、`wasmJs(browser)` ✅、`wasmJs(d8)` ✅、`wasmWasi(nodejs)` ✅

---

**protocol-model**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅、`js(nodejs)` ✅、`js(browser)` ✅、`wasmJs(nodejs)` ✅、`wasmJs(browser)` ✅、`wasmJs(d8)` ✅、`wasmWasi(nodejs)` ✅

---

**protocol-serialization**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅、`js(nodejs)` ✅、`js(browser)` ✅、`wasmJs(nodejs)` ✅、`wasmJs(browser)` ✅、`wasmJs(d8)` ✅、`wasmWasi(nodejs)` ✅

---

**protocol-vanilla-data**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅、`js(nodejs)` ✅、`js(browser)` ✅、`wasmJs(nodejs)` ✅、`wasmJs(browser)` ✅、`wasmJs(d8)` ✅、`wasmWasi(nodejs)` ✅

---

**protocol-transport**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅

`js(nodejs)` ❌——`ktor-network` 的 TCP 实现依赖 `node:net`，但 `js` 是单一 target，产物会被浏览器和 Node.js 同时消费。浏览器中无 `node:net`，TCP 必然崩溃。为避免浏览器 footgun，整条 `js` target 不编译。Node.js 下的 TCP 由 `wasmJs(nodejs)` 覆盖。

`js(browser)` ❌——同上，且浏览器本身没有原生 TCP socket。

`wasmJs(nodejs)` ✅

`wasmJs(browser)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。浏览器中无 `node:net`，TCP 必然崩溃。

`wasmJs(d8)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。D8 中无 `node:net`，TCP 必然崩溃。

`wasmWasi(nodejs)` ❌——WASI preview1 无 socket API。

---

**protocol-session**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅

`js(nodejs)` ❌——传递依赖 `protocol-transport`，TCP socket 需求相同。`js` 是单一 target，产物会被浏览器消费，浏览器中无 TCP。为消除浏览器 footgun，整条 `js` target 不编译。

`js(browser)` ❌——同上，且浏览器本身没有原生 TCP socket。

`wasmJs(nodejs)` ✅

`wasmJs(browser)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。浏览器中无 `node:net`，TCP 必然崩溃。

`wasmJs(d8)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。D8 中无 `node:net`，TCP 必然崩溃。

`wasmWasi(nodejs)` ❌——WASI preview1 无 socket API。

---

**protocol-auth**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅

`js(nodejs)` ✅

`js(browser)` ✅——`ktor-client` 的 HTTP 实现在浏览器中用 `fetch` API，Node.js 中用 CIO engine，两种环境均可用。`js` 产物在浏览器和 Node.js 中均能正常运行，保留 `js` target。

`wasmJs(nodejs)` ✅

`wasmJs(browser)` ✅——`ktor-client` 的 HTTP 实现在浏览器 Wasm 中可用。

`wasmJs(d8)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。D8 中无 `fetch` API 也无 Node.js HTTP 模块，HTTP 调用必然崩溃。

`wasmWasi(nodejs)` ❌——WASI preview1 无 HTTP/网络 API。

---

**protocol-client**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅

`js(nodejs)` ❌——依赖 `protocol-transport` 做 TCP socket。`js` 是单一 target，产物会被浏览器和 Node.js 同时消费，浏览器中无 TCP。为消除浏览器 footgun，整条 `js` target 不编译。Node.js 下的 TCP 由 `wasmJs(nodejs)` 覆盖。

`js(browser)` ❌——同上，且浏览器本身没有原生 TCP socket。

`wasmJs(nodejs)` ✅

`wasmJs(browser)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。浏览器中无 `node:net`，TCP 必然崩溃。

`wasmJs(d8)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。D8 中无 `node:net`，TCP 必然崩溃。

`wasmWasi(nodejs)` ❌——WASI preview1 无 socket API。

---

**protocol-server**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅

`js(nodejs)` ❌——依赖 `protocol-transport` 做 TCP socket（含监听端口）。`js` 是单一 target，产物会被浏览器和 Node.js 同时消费，浏览器中无 TCP 且无法监听端口。为消除浏览器 footgun，整条 `js` target 不编译。Node.js 下的 TCP 由 `wasmJs(nodejs)` 覆盖。

`js(browser)` ❌——同上，且浏览器本身没有原生 TCP socket，更无法监听端口。

`wasmJs(nodejs)` ✅

`wasmJs(browser)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。浏览器中无 `node:net`，TCP 必然崩溃。

`wasmJs(d8)` ❌——`wasmJs` 是单一 target，与 `wasmJs(nodejs)` 共享同一产物。D8 中无 `node:net`，TCP 必然崩溃。

`wasmWasi(nodejs)` ❌——WASI preview1 无 socket API。

---

**world-format**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅、`js(nodejs)` ✅、`js(browser)` ✅、`wasmJs(nodejs)` ✅、`wasmJs(browser)` ✅、`wasmJs(d8)` ✅、`wasmWasi(nodejs)` ✅

---

**world-io**

`jvm` ✅、`android` ✅、`linuxArm64` ✅、`linuxX64` ✅、`mingwX64` ✅、`macosArm64` ✅、`iosArm64` ✅、`iosSimulatorArm64` ✅、`iosX64` ✅、`watchosArm32` ✅、`watchosArm64` ✅、`watchosDeviceArm64` ✅、`watchosSimulatorArm64` ✅、`tvosArm64` ✅、`tvosSimulatorArm64` ✅、`androidNativeArm32` ✅、`androidNativeArm64` ✅、`androidNativeX64` ✅、`androidNativeX86` ✅

`js(nodejs)` ❌——依赖 `kotlinx.io.files.FileSystem` 做实际文件读写，该库不支持 `js` 编译目标。

`js(browser)` ❌——`kotlinx.io.files` 不支持 `js` 编译目标，且浏览器本身没有文件系统。

`wasmJs(nodejs)` ❌——`kotlinx.io.files` 不支持 `wasmJs` 编译目标。

`wasmJs(browser)` ❌——`kotlinx.io.files` 不支持 `wasmJs` 编译目标，且浏览器本身没有文件系统。

`wasmJs(d8)` ❌——`kotlinx.io.files` 不支持 `wasmJs` 编译目标，且 D8 本身没有文件系统。

`wasmWasi(nodejs)` ❌——`kotlinx.io.files` 不支持 `wasmWasi` 编译目标。
```
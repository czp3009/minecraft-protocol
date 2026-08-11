# 跨平台正版账号登录与 Online Mode 加密改造计划

## 计划定位

- 状态：已实施。
- 目标版本：只实现仓库通过 `MinecraftTarget.MINECRAFT_VERSION` 选定的官方 Minecraft Java Edition 发布版，不兼容旧版登录协议、旧
  Mojang 账号或旧 Yggdrasil 登录入口。
- `protocol-auth` 的“全平台”是硬性完成条件：该模块当前发布的 JVM、Android、全部 Native、JS Node、JS browser、WasmJS Node 和
  WasmJS browser 目标都必须提供可运行的账号 OAuth、session service、server hash 和 Login RSA 能力，不能只有 JVM
  完整、其他目标要求用户注入或在运行时抛 unsupported。
- 当前 TCP client/server 的端到端验收仍覆盖它们已经发布的 socket 目标。浏览器 WebSocket carrier 与浏览器 AES/CFB8
  由独立的 [browser-websocket-transport.md](browser-websocket-transport.md) 计划负责；两份计划保持独立，只在“浏览器 online
  Login 需要 auth RSA 产物再启用 transport AES”这一边界互相引用。涉及 `protocol-auth` 的模块/API/RSA 决策以本计划为准；关联计划中仍标为未决的
  `MinecraftCryptography` 拆分问题由本计划明确解决为“同一模块内的 internal `expect`/`actual`”。
- 本计划同时覆盖账号登录和游戏连接认证，但二者是两条相接而不相同的链路：Microsoft OAuth 最终取得 Minecraft access
  token；Login socket 再用该 token 向 Mojang session server 证明玩家正在加入某个加密连接。
- 浏览器的密码算法不是剩余阻塞，但 Web 安全模型不允许脚本绕过上游 CORS。为使账号交换和 session service 真正可运行，
  `protocol-auth` 的 service 直接使用调用方提供的 `HttpClient`，并可通过额外的 relay endpoint 参数改走受限 relay；模块还提供
  可嵌入应用后端的 relay handler。后端部署仍是下游应用的基础设施责任，不新拆模块，也不与 WebSocket/TCP 字节中继混为一谈。

## 当前实现基线与算法可行性

本计划以“Online Mode 尚未完整实现”为起点，必须区分以下两种状态：

- **当前已支持**：仓库的 production source 已为该 target 提供默认实现，普通调用方不需要注入平台密码学 provider 或自行实现算法。
- **具备实现潜质**：已有维护中的平台 API 或第三方库覆盖所需算法，但本计划中的 source-set 接入、公共 API 去平台化和逐 target
  验证尚未完成。仅找到库不算计划完成。

下表只审视 `protocol-client`、`protocol-server`、`protocol-session` 和 `protocol-transport` 当前已经声明的 TCP/socket
target；它不新增 browser socket 范围：

| 当前 socket target 组                         | 连续 AES-128/CFB8 stream                                     | Login RSA/CSPRNG 默认实现                                            | 完成本计划所需的算法覆盖                                                                       | 当前结论                                              |
|-----------------------------------------------|--------------------------------------------------------------|----------------------------------------------------------------------|------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| JVM                                           | 已支持：JCA `AES/CFB8/NoPadding`                             | 已支持，但经公开的 `JvmMinecraftCryptography` 提供，API 形态仍待移除 | JCA RSA/PKCS#1 v1.5、SPKI 与 `SecureRandom`                                                    | 算法链已有；OAuth/account service 与目标 API 尚未完成 |
| Android                                       | 已支持：与 JVM 共享 JCA AES actual                           | 未内置，当前要求调用方提供 `MinecraftCryptography`                   | Android JCA RSA/PKCS#1 v1.5、SPKI 与 CSPRNG                                                    | 无算法缺口，auth actual 待接入                        |
| 当前全部 Native target（Apple、Linux、MinGW） | 已支持：`cryptography-kotlin` optimal provider 的 `AES.CFB8` | 未内置，当前要求调用方提供 `MinecraftCryptography`                   | 同一 optimal provider 的 `RSA.PKCS1` 与平台 CSPRNG；Apple 走系统实现，Linux/MinGW 走 OpenSSL 3 | 无算法缺口，auth actual 待接入                        |
| Kotlin/JS Node                                | 已支持：Node `crypto` 的 `aes-128-cfb8`                      | 未内置，当前要求调用方提供 `MinecraftCryptography`                   | `node-forge@1.4.0` 的 RSA/SPKI/PKCS#1 v1.5 与 `cryptography-random`                            | 无算法缺口，auth adapter 待接入                       |
| Kotlin/WasmJS Node                            | 已支持：Node `crypto` 的 `aes-128-cfb8`                      | 未内置，当前要求调用方提供 `MinecraftCryptography`                   | 与 JS 共用 `node-forge@1.4.0` 和 `cryptography-random`                                         | 无算法缺口，WasmJS interop 与 auth adapter 待接入     |

现状和计划范围据此固定为：

1. `protocol-transport` 已通过 internal `expect`/`actual` 为所有当前 socket target 提供 AES-128/CFB8；它以 shared secret
   同时作为 key/IV，并为收发方向创建两个持续到连接结束的独立 cipher state。这部分不是 Online Mode 尚未完成的算法缺口。
2. common `minecraftServerHash` 和 `MinecraftSessionService.join/hasJoined` 已存在；完整 Microsoft OAuth → Xbox → XSTS →
   Minecraft Services account flow 在任何 target 都尚未实现。
3. Login Request/Response 所需的 RSA-1024、X.509 SPKI DER、RSAES-PKCS1-v1_5 和 CSPRNG 仅 JVM 已有仓库默认实现。其他当前
   socket target 都已找到可覆盖算法的库，但在相应 actual 落地且通过互操作测试前，不得宣称仓库已经全平台支持 Online Mode。
4. 本计划完成后，所有当前 socket target 都必须无需用户提供 `MinecraftCryptography` 即可消费已有 Minecraft account
   或库内账号登录结果，并完成 `/join`、RSA secret 交换及后续 AES stream；不得以删减 target、运行时 unsupported 或用户注入
   provider 代替实现。

## 结果定义

完成后，只有真正发起 Microsoft OAuth 的对象需要应用提供自己的获批 client ID；负责 Xbox/Minecraft Services 交换的对象不隐式拥有某个
app registration。多种入口最终都汇合成同一个 `MinecraftAccountLoginResult`，库不替应用选择
UI，也不要求应用选择平台加密实现。JVM、Android、Native、Node 等不受 browser CORS 限制的运行时把调用方的 client 直接交给
service：

```kotlin
val oauthService = MicrosoftOAuthService(
    httpClient = httpClient,
    application = MicrosoftOAuthApplication(
        clientId = approvedClientId,
        scopes = applicationApprovedMinecraftScopes,
    ),
)
val accountService = MinecraftAccountService(httpClient)
val sessionService = MinecraftSessionService(httpClient)
```

浏览器使用完全相同的 service/API，但 Authorization Code + PKCE 的 Microsoft token exchange 与 Minecraft back-channel
要明确分路：Microsoft 要求 SPA redirect 以 `spa` 类型注册并由带 `Origin` 的 browser request 兑换 code；Minecraft
Services/sessionserver 则必须走应用的同源 relay。这是网络拓扑，不是 crypto provider 选择：

```kotlin
val oauthService = MicrosoftOAuthService(
    httpClient = browserHttpClient,
    application = MicrosoftOAuthApplication(
        clientId = approvedClientId,
        scopes = applicationApprovedMinecraftScopes,
    ),
)
val accountService = MinecraftAccountService(
    browserHttpClient,
    applicationSameOriginAuthenticationRelay,
)
val sessionService = MinecraftSessionService(
    browserHttpClient,
    applicationSameOriginAuthenticationRelay,
)
```

Authorization Code + PKCE 可以由应用自己的系统浏览器或 WebView 承载：

```kotlin
val authorization = oauthService.beginAuthorizationCodeLogin(
    redirectUri = applicationRedirectUri,
)
applicationUi.openAuthorizationPage(authorization.authorizationUri)

// deep link、loopback callback 或 WebView navigation listener 取得完整 redirect URI。
val microsoftTokens = oauthService.completeAuthorizationCodeLogin(
    authorization = authorization,
    redirectedUri = redirectedUri,
)
val login = accountService.loginWithMicrosoftTokens(microsoftTokens)
```

Device Code 是并列的另一种入口，不会产生 authorization code：

```kotlin
val deviceAuthorization = oauthService.beginDeviceCodeLogin()
applicationUi.showDeviceCode(
    deviceAuthorization.userCode,
    deviceAuthorization.verificationUri,
) // 返回 Unit；用于轮询的 device_code 由 opaque pending object 内部持有。
val microsoftTokens = oauthService.awaitDeviceCodeLogin(deviceAuthorization)
val login = accountService.loginWithMicrosoftTokens(microsoftTokens)
```

在 browser 中选择 Device Code 时，应使用带 relay endpoint 的
`MicrosoftOAuthService(browserHttpClient, application, relayEndpoint)`， 因为 device authorization endpoint 没有 SPA
token endpoint 的 CORS contract；选择 PKCE 时则使用不带 endpoint 的构造器。应用是在 选择 OAuth flow 和网络
route，不是在选择平台实现。

使用 MSAL/WAM/系统 broker 的应用可以交入已有 Microsoft token；已有 refresh token 的应用可以无 UI 刷新：

```kotlin
val login = accountService.loginWithMicrosoftAccessToken(platformToken)
val refreshedMicrosoftTokens = oauthService.refresh(storedRefreshToken)
val refreshed = accountService.loginWithMicrosoftTokens(refreshedMicrosoftTokens)
```

上述任一入口成功后都使用相同连接代码：

```kotlin
val identity = MinecraftOnlineIdentity(account = login.account, sessionService = sessionService)
clientProtocol.login(identity)
```

客户端 offline 是显式的另一种 identity；每次连接选择其中一个，不把 online 失败当作 fallback：

```kotlin
clientProtocol.login(MinecraftOfflineIdentity(name = playerName))
```

服务端只选择模式：

```kotlin
val online = MinecraftServerAuthentication.online(
    sessionService = sessionService,
)
val offline = MinecraftServerAuthentication.Offline
```

两侧不是必须一起设置的“全局模式”：server 的 Online/Offline 决定是否发送 Encryption Request 并调用 `/hasJoined`；client 选择
identity 表示自己是否持有正版账号并能响应这条流程。`MinecraftOnlineIdentity` 可以连接 online server，也可以连接不发
Encryption Request 的 offline server（后者不会验证该账号）；`MinecraftOfflineIdentity` 只能完成 offline 登录，收到要求正版认证的
Encryption Request 时明确失败。文档不得把 online client 连接 offline server 描述为“该 server 也变成 online”，也不得自动把失败的
online 登录降级。

目标公共 API 中不再出现 `JvmMinecraftCryptography`，高层 client/server 构造也不再要求 `MinecraftCryptography` 或 RSA key
pair。平台选择由 Kotlin `expect`/`actual` 完成；自定义加密实现只保留为内部测试接缝，不再成为普通用户的责任。

## 三个容易混淆的层次

1. **Microsoft/Xbox/Minecraft Services 账号登录**
    - 在连接游戏服务器之前发生。
    - 对端依次是 Microsoft identity platform、Xbox Live、Minecraft Services。
    - 产物是经过 Minecraft Services 接受的 Minecraft access token 和 Java profile。
2. **Minecraft session server 加入证明**
    - 在 Login socket 的 Encryption Request/Response 之间发生。
    - 客户端向 `/session/minecraft/join` 登记“该 profile 正在加入该 server hash”；游戏服务端向
      `/session/minecraft/hasJoined` 反查。
    - 游戏服务端永远不接收 Microsoft、Xbox、XSTS 或 Minecraft access token。
3. **游戏 socket 加密**
    - RSA 只用于交换一个随机的 16 字节 AES secret 和回显 challenge。
    - 随后的 TCP 字节流使用 AES/CFB8，key 与 IV 都是该 secret，并在整个连接上连续演进，不能按 packet 重置。
    - 现有 `protocol-transport` 已为当前 socket 目标实现这一层；本计划只修正其启用时序和上层平台抽象，不重写 AES/CFB8。

### 官方 Launcher 与游戏进程的边界

官方流程不是“游戏 JAR 打开 Microsoft 登录页，再把 OAuth packet 发进游戏 socket”：

1. 官方 Launcher 的账号层完成 Microsoft/Xbox/Minecraft Services 交换并取得 Java profile。
2. 匹配官方 client 的版本参数模板把 `--username`、`--uuid`、`--accessToken`、`--clientId` 和 `--xuid` 等账号结果传给游戏进程；其中
   socket 认证真正使用的是最终 Minecraft access token 与 profile。
3. 游戏 client 建立服务器连接。收到 Encryption Request 后，它才用该 Minecraft access token 调 session server `/join`。
4. 因此本库把 launcher/account-service 能力放在 `protocol-auth`，把消费账号结果的连接编排放在 `protocol-client`
   ；两者可由同一应用组合，但没有 UI、进程启动或平台 secret storage 的隐式耦合。

## 协议库与下游应用的责任边界

官方 Launcher 是一个具体应用，所以它可以拥有自己的 app registration、client ID、WebView、redirect
handler、账号选择界面和平台安全存储。本仓库是可复用协议库，不能把官方 Launcher 的应用层选择伪装成所有下游应用都应继承的默认行为。

| 事项                                              | 本库负责                                                                                                                                                   | 使用本库构建的应用负责                                                                                                                                                         |
|---------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Entra/Minecraft app registration                  | 表达所需配置并对已知拒绝返回 typed error                                                                                                                   | 注册并维护自己的 public-client 应用，取得 Minecraft Services 所需认可                                                                                                          |
| OAuth client ID                                   | 要求显式传入并原样用于该 registration 的请求                                                                                                               | 提供自己拥有且获准使用的 client ID；可按产品/发行渠道使用不同 ID                                                                                                               |
| client secret                                     | API 中完全不存在，也不读取配置或环境变量                                                                                                                   | native/mobile/browser 应用同样不得保存或发送 secret；若另有 confidential backend，由该后端在本库范围外负责                                                                     |
| tenant、scopes、redirect URI                      | 正确编码请求、校验响应与 redirect/state 一致性                                                                                                             | 选择并在 app registration 中配置允许值；保证传入值与已注册值一致                                                                                                               |
| 系统浏览器、WebView、deep link、loopback callback | 返回 authorization URI，解析应用交回的完整 redirect URI                                                                                                    | 选择 UI host、打开页面、接收 navigation/deep-link/HTTP callback，并处理窗口生命周期                                                                                            |
| Device Code 展示                                  | 返回 display-only user code/URI 和持有 private device code 的 opaque pending state，按规范轮询                                                             | 展示、复制或生成二维码；决定取消和用户提示，不从 UI 返回 authorization code                                                                                                    |
| MSAL/WAM/平台 broker                              | 接受外部取得的正确 Microsoft token 并继续 Xbox/Minecraft 交换                                                                                              | 选择和配置平台 SDK、完成其 UI/SSO 生命周期                                                                                                                                     |
| 认证 HTTP 调用                                    | service 直接使用调用方 client 构造 endpoint-specific form/JSON、限制响应、解析状态并保留序列化失败 cause；可用额外 endpoint 选择 relay                     | 创建并关闭 Ktor `HttpClient`，选择 engine、代理、TLS、timeout、网络权限和 app 生命周期；为运行环境选择 direct 或 relay                                                         |
| 浏览器认证 relay                                  | 在 `protocol-auth` 内提供固定 operation 的 client、versioned wire format 和 framework-neutral handler；复用同一 endpoint codec，拒绝任意 URL/method/header | 部署可信的同源 HTTPS endpoint，把所属 Web framework 的 raw bounded body/response 接到 handler；负责访问控制、CSRF、限流、TLS、监控和运维，并接受 relay 能看见 token 的信任边界 |
| Xbox/XSTS/Minecraft Services 交换                 | 完整实现并隐藏中间 token，返回 typed account/profile/entitlements                                                                                          | 决定何时发起、如何向用户呈现账号/权益错误                                                                                                                                      |
| refresh                                           | 校验并执行 token refresh，返回 replacement token 和新的账号结果                                                                                            | 安排刷新时机；仅在完整成功后把 replacement 原子写入安全存储                                                                                                                    |
| token 持久化与退出                                | 提供 opaque/redacted wrapper 和明确的安全导出入口，不建立全局账号缓存                                                                                      | 使用 Keychain/Keystore/credential vault 等保存；实现账号列表、切换、删除与登出 UX                                                                                              |
| Minecraft Login socket 认证                       | `/join`、server hash、RSA challenge/response、`/hasJoined` 的协议语义                                                                                      | 选择 online/offline 身份、目标服务器和连接生命周期                                                                                                                             |
| WebSocket/TCP carrier 与 AES stream               | 由各 owning transport 模块/计划实现，auth 只交付 shared secret                                                                                             | 选择可用 carrier；浏览器应用还负责独立的 WebSocket/TCP 中继部署和访问策略；它不是上述认证 HTTP relay                                                                           |
| 日志、遥测与隐私声明                              | 库自身从不记录 token/request body，异常与 `toString` 脱敏                                                                                                  | 确保 app 日志、崩溃报告、分析 SDK 和 UI 不泄露 redirect code/token，并履行品牌、隐私和许可义务                                                                                 |
| 启动官方游戏进程                                  | 不负责                                                                                                                                                     | 若应用是 launcher，自行管理安装、进程参数、资源和生命周期                                                                                                                      |

### OAuth 入口是并列能力，不是全局模式开关

| 入口                                 | 适用场景                                               | 应用交互                                              | 库的完成信号                                                |
|--------------------------------------|--------------------------------------------------------|-------------------------------------------------------|-------------------------------------------------------------|
| Authorization Code + PKCE            | 桌面、移动端、browser SPA、带浏览器的 GUI              | 系统浏览器、WebView 或当前 browser tab；捕获 redirect | 校验 `state` 后以一次性 code + verifier 换 token            |
| Device Code                          | CLI、输入受限或无法可靠接收 redirect 的设备            | 展示 user code/verification URI                       | 用内部 device code 轮询，token endpoint 直接返回 token      |
| 外部 Microsoft access token          | MSAL、WAM、平台 broker、应用已有 SSO                   | 由外部组件完成                                        | 校验输入形态后从 Xbox 交换链路继续                          |
| Refresh token                        | 已登录账号恢复、无 UI 续期                             | 无交互；应用提供持久化 token                          | 返回新账号结果与可能轮换的 refresh token                    |
| 已有 Minecraft account token/profile | 上游 launcher 已完成整个 Microsoft/Xbox/Minecraft 交换 | 无 OAuth UI                                           | 构造 redacted `MinecraftOnlineAccount`，直接供 `/join` 使用 |

- 系统浏览器和嵌入式 WebView 是 Authorization Code + PKCE 的两种应用层承载方式，不是两个不同 grant；本库不引入跨平台 UI
  抽象。
- 不提供 username/password、ROPC、implicit grant 或 client-credentials 用户登录。
- 各入口使用独立方法和独立 pending state，因为它们的交互、取消和完成条件不同；不要用一个 enum + 大量 nullable
  字段抹平语义。它们只在成功结果 `MinecraftAccountLoginResult` 处汇合。

### Client ID 的硬约束

1. OAuth 协议请求需要 client ID，但 core library **没有内置默认值**。`MicrosoftOAuthApplication.clientId` 是非空必填项。
2. client ID 是公开标识而不是 secret，可以出现在应用二进制中；但它代表具体应用，其 redirect URI、允许
   flow、scopes、审核和撤销状态均不属于本库。
3. 禁止使用官方 Minecraft Launcher、其他 launcher 或从网络示例复制的 client ID。库也不得在缺少配置时静默回退到某个已知 ID。
4. `MicrosoftOAuthApplication` 不定义 `clientSecret` 字段。public KMP 应用无法保密；PKCE/Device Code 才是这里允许的
   public-client 方案。
5. refresh token 必须与取得它时的 application/client ID 配套使用；不能在不同 `MicrosoftOAuthApplication` 之间迁移或猜测。
6. 如果未来本项目作者拥有一个明确获准供任意下游应用共享的 registration，那是新的产品、合规和运维决策：应作为单独 opt-in
   companion/application 评审，不能悄悄成为 `protocol-auth` 默认值，也不能改变 caller-supplied client ID 的核心 API。
7. scopes 同样是非空、caller-supplied registration 配置，core 不把互相冲突的 Xbox website/Entra 示例组合成隐藏默认值。库提供
   validated scope value type 和文档化常量候选，但应用必须显式选择其获批的 Xbox sign-in/refresh scopes；不能把 Microsoft
   Graph 等另一 audience 的 scope 混进同一次 token 请求。

### 浏览器 HTTP/CORS 与认证 relay 的固定设计

算法库不能突破浏览器同源策略。本计划调查期间对真实 endpoint 做了不带凭据的 `OPTIONS`/无效请求探测：Microsoft token
endpoint 与 Xbox user/XSTS endpoint 暴露了可用的 CORS 响应，但 Microsoft device-code endpoint 未返回允许 origin 的
header；`api.minecraftservices.com` 的 `login_with_xbox`、entitlements、profile，以及 `sessionserver.mojang.com` 的 `/join`、
`/hasJoined` 都没有形成可供任意 browser app 直接调用的完整 CORS 路径。CORS 是可变的服务端策略，Phase 0 要复测，但实现不能把“某几个
endpoint 当前碰巧可跨域”当作全链路保证。

因此 browser target 的完整基线路径固定为：Authorization 页面仍由应用直接导航到 Microsoft；SPA Authorization Code + PKCE 的
code/refresh token exchange 直接使用调用方的 browser client，让 browser 按 Microsoft 官方 contract 携带 `Origin`，且
redirect URI
必须以 `spa` 类型登记；Device Code 的 device authorization/polling，以及 XBL、XSTS、Minecraft Services 和 session-service
操作使用应用配置的同源 HTTPS relay。非浏览器 target 通常全程 direct；应用也可以在任意 target 主动选择 relay。两条路径 进入相同
common service，不产生 `BrowserMinecraftAccountService`、平台 enum、公共 transport 包装对象或公共 crypto provider。

```kotlin
// 普通应用进程。
val direct = MinecraftAccountService(httpClient)

// Browser 的 Device Code 与 Xbox/Minecraft/session back-channel；
// endpoint 是应用自己的受信后端，不是本库的公共服务。
val relayed = MinecraftAccountService(browserHttpClient, applicationSameOriginAuthenticationRelay)
```

`protocol-auth` 内的 relay contract 必须满足以下约束：

1. wire request 是带协议版本的 sealed、typed、endpoint-specific operation，只覆盖 Microsoft device authorization/token（含
   Device Code polling；也允许非 SPA public client 显式走 relay）、XBL user auth、XSTS、Minecraft
   login/profile/entitlements、session `/join` 和 `/hasJoined`。不接受 caller-supplied upstream URL、HTTP
   method、Host、Authorization header、Cookie 或任意 header map，因而不能退化为 open proxy/SSRF primitive。
2. common direct executor 与 relay handler 使用同一组 operation、request builder、response byte limit、JSON/form codec 和
   typed error mapper；relay 不是第二套登录逻辑。handler 只需要调用方提供的 upstream Ktor `HttpClient`，不引入 Ktor server
   engine，也不规定 Ktor/Spring/Node 等 Web framework。
3. handler policy 要求应用显式给出 operation 集合，并为其中启用的 OAuth operation 配置允许的 client ID；库没有默认
   ID，也不自动开放 server-only `/hasJoined`。handler 禁止 upstream redirect，固定 HTTPS host/route/method/content
   type，严格限制 request/response bytes，并只把该 operation 所需的状态、有限响应字段和 `Retry-After` 语义返回 browser；不得转发
   cookies、hop-by-hop headers 或应用用于调用 relay 的鉴权 header。
4. relay response 使用 `Cache-Control: no-store`；库的 model、异常、`toString` 和日志全部脱敏。relay 必然能看见
   Microsoft/Minecraft token，所以它是应用的可信后端，不是匿名第三方代理。库不能替应用决定用户会话鉴权、Origin/CSRF
   规则、并发/rate limit、审计、地区合规、TLS certificate 或部署拓扑。
5. 应用可以把认证 HTTP relay 与关联计划的 WebSocket/TCP 中继部署在同一服务中，但二者使用不同 endpoint、协议和权限：前者理解一组
   allowlisted auth operation 并接触 token；后者只透明转发 Minecraft 字节流且不理解 OAuth/Minecraft packet。生产
   API、测试和文档不得把它们合并成一个“万能代理”。
6. 没有任何 npm/JS 库可以在页面脚本中合法绕过上游 CORS，因此这里需要的是受信网络拓扑而不是继续寻找密码算法包。提供
   direct/relay client + handler 后，browser 的 `protocol-auth` 能力才算完成；只让应用自行实现一个未知代理协议不算完成。
7. relay handler 是逐 operation、无账号会话状态的：Device Code 的 interval/`slow_down`/取消仍由 browser 中的
   `MicrosoftOAuthService` 管理，每次到期只发一次 typed polling operation。handler 不替客户端无限轮询，也不缓存 pending
   code/token。

## 完整账号登录流程

下述“库向某 endpoint 发送”描述的是逻辑接收方。direct transport 由当前进程直接发送；relay transport 先把 typed operation
发给应用的可信 relay，再由 relay 发送完全相同的上游请求。无论路径如何，Microsoft/Xbox/Minecraft Services/sessionserver
都是最终协议对端，游戏服务端都不会因此看到账号 token。

### 0. 应用注册是外部前置条件

1. 库的使用方在 Microsoft Entra 中注册自己的 **public client** 应用，账号类型允许个人 Microsoft 账号。
2. 使用方申请 Minecraft Services 所要求的应用注册许可。未获许可的自建 client ID 可能在 Minecraft Services 阶段收到
   `403 Invalid app registration`。
3. 库不内置 Mojang、Microsoft、第三方 launcher 的 client ID，不接受也不分发 client secret。
4. `clientId`、tenant、redirect URI 和 scopes 是应用配置。默认 tenant 使用 `consumers`；scopes 是非空必填项，并用该应用获批
   registration 做人工 smoke，不把 Xbox 文档与 Entra 文档中不同的 offline scope 写法混成库默认值。browser PKCE redirect
   必须按 Microsoft 要求登记为 `spa`；native custom scheme/loopback redirect 不能假装成 SPA 配置。
5. “应用未获批”必须映射为明确、不可盲目重试的错误，而不是普通网络失败。
6. 应用要使用 Device Code 时，还必须在自己的 registration 中启用相应 public-client flow；库不通过更换 client ID 或降级到别的
   grant 绕过 registration policy。

### 1A. Device Code 登录

适合 CLI、电视、无可靠回调 URI 的桌面程序，也可作为跨平台基线流程。

有可靠浏览器和 redirect 的交互式应用优先提供 Authorization Code + PKCE；Device Code 是可选方案，不应仅因为实现 UI 简单就成为所有
GUI 的无提示默认值，应用还要防止 code-phishing/social-engineering 提示被滥用。

1. 应用调用库，库向 Microsoft `/oauth2/v2.0/devicecode` 发送 `client_id` 和 scopes。
2. 库把 `user_code`、`verification_uri`、可选 `verification_uri_complete`、展示消息、`expires_in` 和轮询 `interval` 返回给应用。
3. 应用负责向用户展示信息；库不擅自打开浏览器或打印到终端。
4. 库按服务端给出的 interval 轮询 `/oauth2/v2.0/token`，grant type 为 device-code grant：
    - `authorization_pending`：继续等待；
    - `slow_down`：按规范增加间隔；
    - 用户拒绝、code 过期或错误：以终态返回；
    - coroutine 取消：立即停止轮询；
    - 成功：取得 Microsoft access token、过期时间和可选 refresh token。
5. Microsoft token 被视为 opaque value；不得靠解析 JWT payload 推断到期时间或权限。

### 1B. Authorization Code + PKCE 登录

适合能接收 redirect/deep link 的桌面、Android、Apple 平台应用和 browser SPA。

1. 库用 CSPRNG 生成至少 128-bit `state` 与满足 RFC 7636 长度/字符集的 PKCE `code_verifier`，以 SHA-256 +
   base64url-no-padding 计算 S256 `code_challenge`，返回 authorization URI 和一个不可伪造的 pending transaction。
2. 应用负责打开系统浏览器和接收 redirect URI；库不依赖某个平台 UI、loopback server 或 deep-link framework。
3. 应用把完整 redirect 交回库。库先验证 scheme/authority/path、OAuth error 与 constant-time `state`，再使用 authorization
   code、原始 redirect URI 和 `code_verifier` 调用 token endpoint。
4. public client 不发送 client secret。
5. browser SPA 通过 direct transport 兑换 code/refresh，让浏览器生成 `Origin` header；库不伪造 forbidden browser
   header。redirect 必须以 `spa` 类型注册，否则 Microsoft token endpoint 会拒绝 CORS redemption。
6. pending transaction 绑定 application/client ID、redirect URI 和 transport transaction，只能消费一次，并受到期时间约束。

### 1C. 已有 Microsoft token 与刷新

- 提供 `loginWithMicrosoftAccessToken(...)`，让使用 MSAL、系统 SSO 或企业账号组件的应用跳过本库的交互授权，但继续复用
  Xbox/Minecraft 交换链路。
- 提供 `refresh(...)`，使用 Microsoft refresh token 取得新的 Microsoft access token，然后重新执行 Xbox Live、XSTS 和
  Minecraft Services 交换。
- Microsoft 每次 refresh 可能返回替代 refresh token。库把新 token 返回给调用方；调用方只有在整个 refresh
  成功后才原子替换持久化值，并安全删除旧值。
- refresh 必须由与初次授权相同的 `MicrosoftOAuthApplication` 和兼容 flow channel 执行：browser SPA PKCE token 用 direct
  OAuth service；browser Device Code token 用 relay OAuth service。`MicrosoftRefreshToken` 的安全存储导出同时携带非秘密的
  application/flow binding metadata，导入时校验，不靠猜测 token 内容选择路径；relay endpoint 可以由应用正常迁移，不成为
  token identity 的一部分。
- 库不自动读写 Keychain、Android Keystore、浏览器 storage 或文件系统。安全存储策略属于应用。

### 2. Microsoft token 交换为 Xbox Live User Token

库向 `https://user.auth.xboxlive.com/user/authenticate` 发送 JSON：

- `AuthMethod = "RPS"`
- `SiteName = "user.auth.xboxlive.com"`
- `RpsTicket = "d=<Microsoft access token>"`
- `RelyingParty = "http://auth.xboxlive.com"`
- `TokenType = "JWT"`

库只在内存中保留返回的 Xbox user token，并读取 `DisplayClaims.xui`。任何响应、异常或日志都不得包含 token。

### 3. Xbox Live User Token 交换为 XSTS Token

库向 `https://xsts.auth.xboxlive.com/xsts/authorize` 发送：

- `SandboxId = "RETAIL"`
- `UserTokens = [<Xbox user token>]`
- `RelyingParty = "rp://api.minecraftservices.com/"`
- `TokenType = "JWT"`

从 XSTS 响应中取得 token 和 `uhs`。必须校验 claims 结构与用户哈希的一致性；Xbox `XErr` 要映射为分阶段、可诊断且不泄露凭据的错误，例如账号无
Xbox profile、地区/年龄限制或服务暂时不可用。

### 4. XSTS Token 交换为 Minecraft Access Token

库向 `https://api.minecraftservices.com/authentication/login_with_xbox` POST：

```json
{
  "identityToken": "XBL3.0 x=<uhs>;<xsts-token>"
}
```

返回的 `access_token` 才是稍后传给 Mojang session server `/join` 的 token。Microsoft access token、Xbox token 和 XSTS
token 均不得代替它。

### 5. 权益和 Java profile

1. 带 `Authorization: Bearer <Minecraft access token>` 请求 `/entitlements/mcstore`。
2. 带同一 token 请求 `/minecraft/profile`，取得 Java profile UUID、玩家名、skins 与 capes。
3. 成功登录的最终判据是 Minecraft Services 接受 token 且返回有效的 Java profile。
4. entitlements 作为完整、可前向兼容的 typed metadata 返回，不用一组硬编码商品名武断否定 Game Pass 或未来权益；未知 item
   原样保留。
5. 不从 profile name 本地计算 online UUID。必须使用 profile endpoint 返回的 UUID。
6. 不硬编码 Wiki 当前展示的 entitlement JWT 公钥。只有匹配官方实现的证据确认了公钥发现、轮换与验证规则后，才增加本地签名校验；在此之前以
   HTTPS API 结果和 profile 校验为准。

### 6. 账号登录产物

`protocol-auth` 返回一个不依赖 `protocol-client` 的结果：

```kotlin
class MinecraftOnlineAccount internal constructor(
    val name: String,
    val id: Uuid,
    // Minecraft access token、expiresAt 等敏感状态不作为可打印属性暴露。
)

class MinecraftAccountLoginResult(
    val account: MinecraftOnlineAccount,
    val refreshToken: MicrosoftRefreshToken?,
    val entitlements: MinecraftEntitlements,
    val profile: MinecraftAccountProfile,
)
```

- `MinecraftOnlineAccount` 提供明确命名的 existing-credential factory，以支持已有 launcher token/profile 的调用方；另提供显式
  `exportForSecureStorage()` credential envelope 供确需缓存 Minecraft token 的应用使用。原始 token 不作为普通属性，
  `toString`、`equals`、异常和普通日志都不泄露它。
- `MicrosoftRefreshToken` 是 opaque/redacted wrapper。因为应用必须持久化它，所以提供明确命名的 `exportForSecureStorage()`
  ，而不是公开 data-class 属性。
- `MinecraftSessionService.join` 改为接受 `MinecraftOnlineAccount`，在 `protocol-auth` 内部读取 Minecraft access token；高层
  client 不再接触 token 字符串。
- 到期时间使用公共 KMP 时间类型和 token 响应的 `expires_in` 计算，并允许测试注入 clock。不得解析 opaque token 猜测时间。

## 完整游戏 socket 登录流程

以下顺序既解释 OAuth 为什么会影响 socket，也规定 client/server 的实现时序。

### 1. Handshake 与 Login Start

1. 客户端先连接目标 Minecraft server 的 TCP socket。
2. 客户端发送 Handshake，包含仓库选定发布版的 protocol version、目标地址/端口和 `nextState = LOGIN`。
3. 客户端发送 Login Start，包含 profile name 和 UUID。
4. online server 此时只能把这些字段当作 **未认证声明**，不得建立已认证 player session，也不得信任客户端声称的 UUID。

### 2. 服务端发 Encryption Request

1. suspend factory `MinecraftServerAuthentication.online(...)` 在服务端启动/配置时生成一次 1024-bit RSA key pair；每条连接共享该
   public key，但每条连接生成新的随机 4 字节 challenge。
2. 服务端发送 Encryption Request：
    - `serverId`：匹配当前官方实现，通常为空字符串；
    - public key：RSA SubjectPublicKeyInfo/X.509 DER；
    - verify token/challenge：本连接随机值；
    - `shouldAuthenticate = true`。
3. 这个 packet 本身仍是明文。

### 3. 客户端生成 secret、计算 hash 并调用 join

1. 客户端解析并验证服务端 RSA public key 的 SPKI 格式与 RSA algorithm；只应用 matching official client 明确执行的
   key-size 检查，不能因为 vanilla server 自己生成 1024-bit key 就额外拒绝官方 client 本来会接受的其他合法 SPKI。
2. 客户端用 CSPRNG 生成独立的 16 字节 shared secret。
3. 客户端计算：

   `SHA-1(ISO-8859-1(serverId) || sharedSecret || encodedPublicKey)`

4. digest 按 Java signed two's-complement `BigInteger` 的十六进制形式编码；不能用普通无符号 SHA-1 hex。
5. 客户端先向 `https://sessionserver.mojang.com/session/minecraft/join` POST：
    - `accessToken`：第 4 步账号链路得到的 **Minecraft** access token；
    - `selectedProfile`：profile UUID；
    - `serverId`：上述 signed hash 字符串。
6. `/join` 成功后，客户端才继续发送 Encryption Response。失败时关闭登录，不得降级为 offline mode。

这就是 OAuth 对 socket 的实际影响：OAuth 字节从不进入 Minecraft packet，但它产生的 Minecraft access token 被用于把“账号
profile”绑定到“本连接 RSA/AES secret 与 server public key 计算出的 hash”。没有该绑定，server 的 `hasJoined` 无法确认这条
socket 属于正版 profile。

### 4. 客户端发送 Encryption Response 并开启 AES

1. 使用 RSAES-PKCS1-v1_5 分别加密：
    - 16 字节 shared secret；
    - Encryption Request 的原始 verify token。
2. 客户端发送 Encryption Response。该 packet 的外层 framing 仍完全明文。
3. 只有在该 packet 写入完成之后，客户端才对后续 socket 字节启用 AES/CFB8：
    - AES-128 key = shared secret；
    - IV = 同一个 shared secret；
    - segment size = 8 bit；
    - encryptor/decryptor 状态贯穿连接，不能每个 packet、每个 frame 或压缩边界重新初始化。

### 5. 服务端处理 Encryption Response

1. 服务端先在明文 framing 下完整读取 Encryption Response。
2. 用 RSA private key 解密 verify token，并与本连接 challenge 做 constant-time 比较。
3. 解密 shared secret，并严格要求 16 字节。
4. 校验通过后立即为后续入站/出站字节启用同样的 AES/CFB8；失败时发送允许的断开信息并关闭，绝不继续解析为明文。
5. RSA ciphertext 长度、DER 长度、verify token 长度和 packet byte-array 上限都要在分配/解密前按匹配官方 codec
   的证据验证，防止无界分配和 provider-specific 异常泄漏。

### 6. 服务端调用 hasJoined

服务端向 `https://sessionserver.mojang.com/session/minecraft/hasJoined` GET：

- `username`：Login Start 的玩家名；
- `serverId`：与客户端相同的 signed hash；
- `ip`：仅在 `preventProxyConnections` 开启时传入真实远端地址。

服务端收到的 profile 才是认证结果：

- 用返回的 UUID/name/properties 建立 authenticated profile；
- 不信任 Login Start 声称的 UUID；
- 校验返回 name 与请求语义，按当前官方行为处理大小写；
- 失败、空响应、超限 JSON 或身份不一致都终止登录；
- session-service 暂时不可用与认证拒绝要使用不同异常类别，便于上层决定提示或重试策略。

### 7. 完成 Login、Configuration、Play

1. server 可在加密已经开启后发送 Set Compression；transport 顺序必须保持“packet 编码/可选压缩与 framing，然后由连续 AES
   stream 加密”。
2. server 发送 Login Finished，内容使用已验证 profile。
3. client 回 Login Acknowledged，双方进入 Configuration。
4. 配置注册表等流程完成后进入 Play。
5. `/player/certificates` 的 profile key、secure-chat 签名和 `PlayerSessionPacket` 是另一套身份能力，不是 online-mode
   socket 加密的前置条件；除非另立需求，本计划不把 signed chat 混入登录验收。

## 令牌和数据到底发给谁

| 数据                                     | 发送方 → 接收方                                   | 游戏服务端可见  | 生命周期                       |
|------------------------------------------|---------------------------------------------------|-----------------|--------------------------------|
| Microsoft authorization code/device code | 应用/库 → Microsoft                               | 否              | 单次、短期                     |
| Microsoft access token                   | 库 → Xbox user authentication                     | 否              | 仅账号交换期间                 |
| Microsoft refresh token                  | Microsoft → 应用，经库返回                        | 否              | 应用安全持久化并轮换           |
| Xbox user token                          | 库 → XSTS                                         | 否              | 内存瞬时值                     |
| XSTS token + `uhs`                       | 库 → Minecraft Services                           | 否              | 内存瞬时值                     |
| Minecraft access token                   | 库 → Minecraft Services/profile 与 Mojang `/join` | 否              | 账号对象内部，过期后刷新       |
| Login Start name/UUID                    | Minecraft client → game server                    | 是，但未认证    | 当前连接                       |
| RSA public key + challenge               | game server → Minecraft client                    | 双方            | 当前 server/keypair 与当前连接 |
| RSA-encrypted secret/challenge           | Minecraft client → game server                    | 是              | 当前连接                       |
| server hash + username + optional IP     | game server → Mojang `/hasJoined`                 | server 自己发出 | 当前连接                       |
| verified profile/properties              | Mojang → game server                              | 是              | 当前已认证 session             |

在 relay 模式下，表中的账号/session HTTP 数据会额外经过应用自己的可信认证 relay，因此 relay 可见这些 token 和
profile；它仍不会把 token 发给 Minecraft game server。这个额外信任边界必须出现在应用隐私与威胁模型中，不能用“只是代理”掩盖。

## `protocol-auth` 单模块全平台密码学设计

### 不拆模块的固定决策

- 不新增 `protocol-auth-cryptography` 或其他认证聚合/平台模块。
- OAuth、offline identity、session-service HTTP、signed SHA-1 server hash、shared secret、verify token、Login RSA façade 与所有平台
  actual 都留在 `protocol-auth`。
- `protocol-client` 和 `protocol-server` 继续只依赖 `protocol-auth`；普通用户不需要为了 online mode 再选择一个依赖，也不需要知道
  JCA、Apple Security、OpenSSL 或 node-forge adapter。
- AES/CFB8 是 socket stream transform，生产实现仍归 `protocol-transport`。这不降低 `protocol-auth` 的全平台要求：auth
  在每个自身目标上完整产出/接受 Login RSA 数据与 shared secret，transport 再消费 secret 开启 stream cipher。

### 内部 expect/actual，而不是公共平台 provider

`protocol-auth/commonMain` 保留无平台差异的 `MinecraftEncryption` façade。CSPRNG、digest、constant-time compare 和 signed
bigint 已有维护型 KMP 库，直接在 common 使用；只有 RSAES-PKCS1-v1_5 这一不可避免的平台差异进入最小 internal expect/actual
backend：

```kotlin
internal interface MinecraftRsaBackend {
    suspend fun generateRsaKeyPair(keySizeBits: Int = 1_024): MinecraftRsaKeyPair
    fun rsaEncrypt(encodedPublicKey: ByteArray, plaintext: ByteArray): ByteArray
    fun rsaDecrypt(privateKey: MinecraftRsaPrivateKey, ciphertext: ByteArray): ByteArray
}

internal expect object PlatformMinecraftRsaBackend : MinecraftRsaBackend
```

对应公共 façade 形态固定为无 provider 参数：

```kotlin
object MinecraftEncryption {
    suspend fun createServerContext(): MinecraftServerEncryptionContext
    fun createServerChallenge(
        context: MinecraftServerEncryptionContext,
        shouldAuthenticate: Boolean = true,
    ): MinecraftEncryptionChallenge
    fun answerServerChallenge(request: EncryptionRequestPacket): MinecraftClientEncryption
    fun acceptClientResponse(
        challenge: MinecraftEncryptionChallenge,
        response: EncryptionResponsePacket,
    ): ByteArray
}
```

- RSA key generation 统一为 suspend API。JCA/Native actual 可以在调用中完成；`webMain` 使用 node-forge
  callback/worker-capable key-generation path，并以 cancellable suspension 等待，browser runtime 必须让出事件循环。coroutine
  取消至少停止等待并丢弃结果；只有 provider 本身提供终止句柄时才承诺中止底层计算，文档不能伪造“密钥生成必然可强制取消”的保证。
- 公共 `MinecraftEncryption` 方法不接收 backend 参数。server key pair 由 opaque server context 持有并在一个 Online
  配置内复用；client result 只公开 response 与防御性复制的 shared secret。
- `MinecraftEncryption.createServerContext()` 是 suspend factory；challenge 创建、client encrypt 与 server response
  decrypt 在已有 context 上执行。`MinecraftServerAuthentication.online(...)` 调用它一次并返回完成初始化的 Online
  配置，不在每连接或构造器 getter 中懒生成。
- 删除公开 `MinecraftCryptography` 注入合同、`JvmMinecraftCryptography` 和平台命名类型。deterministic fake、固定 key
  与故障注入只通过 `protocol-auth` internal/test friend 接缝使用。
- private-key wrapper 与实际 key-pair 保持 internal；跨模块确需持有的 server state 只暴露不含 provider/private-key API 的
  opaque context。

### 各目标 actual

| 能力                       | common/平台实现库                                                                                                                                                                                      | 用途                                                                                                          |
|----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| CSPRNG                     | version catalog 锁定的 `dev.whyoleg.cryptography:cryptography-random`；JVM 使用 `SecureRandom`、Native 使用系统 RNG、JS/WasmJS 使用 `globalThis.crypto.getRandomValues`                                | OAuth state、PKCE verifier、verify token、16-byte shared secret；RSA provider 自己负责 key-generation entropy |
| SHA-1 / SHA-256            | Okio `ByteString.sha1()` / `sha256()`                                                                                                                                                                  | Minecraft signed server hash、PKCE S256 challenge                                                             |
| Base64url without padding  | Kotlin 标准 Base64 API 或 Okio 的受测编码 API                                                                                                                                                          | PKCE challenge；不手写编码器                                                                                  |
| constant-time byte compare | Okio `ByteString.equals(..., constantTime = true)`                                                                                                                                                     | verify token 等固定 secret 比较                                                                               |
| signed big integer         | `dev.whyoleg.cryptography:cryptography-bigint`                                                                                                                                                         | Minecraft 特有的 signed SHA-1 hexadecimal text                                                                |
| HTTP/form/query            | Ktor client core 的 form、URL builder 与 response streaming                                                                                                                                            | Microsoft OAuth、Xbox、Minecraft Services、join/hasJoined                                                     |
| JSON                       | `kotlinx.serialization.json`                                                                                                                                                                           | 所有 JSON 请求/响应，不手写 escaping                                                                          |
| RSAES-PKCS1-v1_5 + SPKI    | 下列 internal expect/actual                                                                                                                                                                            | Login shared secret/challenge 加密、server key generation/decrypt                                             |
| AES-128/CFB8               | 当前全部 socket target 已有 transport actual：JVM/Android 使用 JCA、Native 使用 cryptography-kotlin、JS/WasmJS Node 使用 Node `crypto`；browser 由 `aes-js@3.1.2` 的 `ModeOfOperation.cfb(key, iv, 1)` | `protocol-transport` 的连续 socket stream，不进入 `protocol-auth` 依赖                                        |

RSA actual 的固定选择如下：

1. JVM + Android 共享 `javaCryptoMain`
    - JCA `KeyPairGenerator("RSA")`、`Cipher("RSA/ECB/PKCS1Padding")`。
    - public key 导出 X.509 SubjectPublicKeyInfo DER。
2. Apple、Linux、MinGW 等 Native 目标共享 `nativeMain`
    - 使用 version catalog 已锁定版本的 `cryptography-kotlin` optimal provider `RSA.PKCS1`：Apple targets 由
      `cryptography-provider-apple`/Security.framework 实现 key generation、SPKI DER、PKCS#1 v1.5
      encrypt/decrypt；Linux/MinGW 由 `cryptography-provider-openssl3-prebuilt`/OpenSSL 3 实现。
    - 不自行实现 RSA、ASN.1 或 PKCS#1 padding。
3. JS 与 WasmJS 的所有执行环境共享 default hierarchy 的 `webMain` actual
    - 这里的 `js { nodejs(); browser() }` 是一个 `jsMain` production artifact 的两种 execution，`wasmJs` 同理；不存在可分别发布
      actual 的 `jsNodeMain`/`jsBrowserMain`。因此不能让 Node artifact 依赖 `node:crypto`、browser artifact 另用 npm
      provider。
    - `node-forge@1.4.0` 作为 `jsMain` 与 `wasmJsMain` 的 npm runtime dependency，`webMain` thin adapter 统一负责
      RSA-1024 key generation、SPKI DER 导出/解析和 `RSAES-PKCS1-V1_5` encrypt/decrypt。adapter 在调用 RSA 前启用
      node-forge pure-JS algorithm path；browser bundle 必须使用 package 的 browser mapping，不能出现 Node
      built-ins/polyfill。Node runtime 可由 node-forge 自己使用系统 entropy source，但仓库不写第二套 Node RSA 绑定。
    - common `cryptography-random` 在 JS/WasmJS 的 Node 与 browser runtime 都要求 `globalThis.crypto.getRandomValues`
      ；仓库由 Gradle provision 的 Node 必须通过该 capability gate，外部 consumer 文档也列出这一最低运行时能力。node-forge
      的 padding/key-generation PRNG 必须确认从 Web Crypto/Node crypto 的 CSPRNG 播种；初始化时检测不到强随机源就以 typed
      capability error 失败，禁止退回 `Math.random()`。
    - npm/JS 类型不得进入公共 ABI。相同 actual 必须分别在 JS Node、JS browser、WasmJS Node、WasmJS browser
      执行互操作测试；共享源码不能替代四种 runtime 证据。

### 浏览器整条 online-mode 密码链已经具备算法覆盖

- 本计划补充验证了 `node-forge@1.4.0` 的纯 JS 路径能够生成 RSA-1024 key pair、导出 162-byte SPKI DER、重新解析公钥、以
  PKCS#1 v1.5 加密任意二进制数据，并由对应 private key 解密；128-byte ciphertext 与原文完全匹配。
- 实际 headless Chrome bundle 进一步通过了 `crypto.getRandomValues`、SHA-1、SHA-256，以及上述完整 RSA
  keygen/SPKI/encrypt/decrypt round trip。临时证据位于 `temp/npm-crypto-browser-eval/browser-forge-full-rsa.mjs`、
  `browser-full-auth-smoke.mjs` 和 `browser-full-auth-smoke.html`，只作为 agent 调查证据，不是 Gradle 输入或生产源码。
- 浏览器 stream AES 已由独立计划确认可通过 `aes-js@3.1.2` 的 `ModeOfOperation.cfb(secret, secret, 1)` 实现
  AES-128/CFB8。它已经与 Node/OpenSSL 对齐，并通过不同分块边界、独立收发状态、node-forge RSA 组合和实际 Chrome
  smoke；详细向量、脚本、性能风险与后续 transport
  实施只记录在 [browser-websocket-transport.md](browser-websocket-transport.md)，本计划不复制第二份验证记录。
- 因此浏览器 online Login 当前没有“缺少 random/hash/RSA/AES 算法库”的可行性阻塞。尚待实施验证的是 `webMain` adapter 在
  Kotlin/JS 与 Kotlin/WasmJS 各自的 Node/browser runtime 中的 npm interop、异常归一化及与真实 browser transport
  的端到端连接，而不是重新寻找算法。
- 职责仍然清晰：`protocol-auth` 实现 random/hash/RSA 与 session authentication；`protocol-transport` 实现 AES/CFB8
  continuous stream；浏览器 WebSocket carrier/代理设计见关联计划。
- 这项结论只解决密码算法，不代表 browser 能直接 `fetch` 所有账号/session endpoint；上文的认证 HTTP relay 是 CORS
  所需的独立网络条件。关联计划中的 WebSocket/TCP 中继只解决 browser 没有 raw TCP socket 的问题，二者不可互相替代。

### 全目标一致性和安全接缝

- 所有目标必须通过相同的 RSA/SPKI/PKCS#1 固定互操作 fixtures，而不只做各 provider 的 self-roundtrip。
- 逐目标验证 vanilla server 所用的 1024-bit key generation、DER 跨 provider 读取、任意二进制明文、对应 modulus 长度的
  ciphertext、错误 DER/algorithm/ciphertext 与 provider 异常归一化。client 对非 1024-bit SPKI 的接受/拒绝只按 Phase 0
  官方证据定，不自行收紧。
- 所有 secret/key/ciphertext 输入输出执行防御性 copy。能清零的短期 `ByteArray` 在 finally 中清零，但文档不能承诺 GC
  语言中绝对擦除。
- provider 原始异常统一包装为 `MinecraftAuthenticationException` 的跨平台子类；公共结果与异常类型一致，message/cause/stack
  可以因平台不同。
- 任一已发布 `protocol-auth` target 缺少 actual、只能编译不能执行，或要求调用方补 provider，都视为计划未完成。

## 公共 API 改造

### `protocol-auth`

- 新增 `MicrosoftOAuthApplication`：caller-owned、非空且无默认值的 client ID 与 scopes，以及 tenant 配置；永不包含
  `clientSecret`。scope value type 校验空白/重复/非法字符，但不替 registration 猜授权。
- `MicrosoftOAuthService`、`MinecraftAccountService` 与 `MinecraftSessionService` 直接接收调用方的 `HttpClient`；带一个额外
  `Url` 参数的构造器使用 versioned typed wire contract 调用应用 relay。内部固定 operation 路由不进入公共 API，也不暴露任意
  request/URL proxy API。库不安装、检查、修改或关闭调用方的 client。
- 新增 `MicrosoftOAuthService(httpClient, application)`：只拥有 Authorization Code + PKCE、Device Code 和 refresh，明确以
  app
  registration 为作用域。
- 新增 `MinecraftAccountService(httpClient)`：只拥有 Microsoft token → XBL → XSTS → Minecraft Services →
  profile/entitlements；external Microsoft token 可以直接进入，existing Minecraft account 可以绕过整条 OAuth 交换。
- `MinecraftSessionService` 使用相同构造方式，使 browser 的 `/join` 与 `/hasJoined` 不绕开同一 CORS/relay 策略；它不会在
  browser 静默猜 relay URL。
- 三个 service 可共享同一 `HttpClient`。底层 client 始终由调用方创建、配置和关闭；库不安装 engine、不检查或修改配置，
  也不假定调用方已安装 ContentNegotiation。
- 新增 common `MinecraftAuthenticationRelayHandler`、public redacted handler request/response boundary 和 internal
  versioned typed wire models。应用只把 bounded raw body 交给 handler，再把 status/fixed headers/body 写回所属 Web
  framework；它不需要直接构造会把 token 暴露在 `toString` 的 wire data class。handler 不依赖 Ktor server、Servlet、Node
  server 或新模块。
- direct executor 与 relay handler 复用 internal endpoint operation/codec。production endpoint 固定为 allowlist；test
  endpoint override 只能经 internal/test seam 或显式 unsafe test factory，不能进入普通 production configuration。
- Authorization Code + PKCE、Device Code、external Microsoft token、refresh token、existing Minecraft account 是五组独立入口和
  typed models；它们最终在 `MinecraftAccountLoginResult` 汇合，不用一个 nullable configuration object 混合。
- Authorization Code API 只返回 URI/pending transaction 并消费应用交回的 redirect；Device Code API 只返回 display
  info/opaque polling state。`protocol-auth` 不打开 WebView/浏览器、不监听 deep link/loopback socket、不显示 user code。
- OAuth `state`、PKCE verifier、verify token 和 shared secret 都使用支持 `protocol-auth` 全部现有目标的
  `cryptography-random` CSPRNG。
- refresh API 要求与原登录相同的 `MicrosoftOAuthApplication`，返回 replacement 而不写存储；external-token API 不假装拥有平台
  broker 生命周期。
- 新增 opaque/redacted token wrappers、`MinecraftOnlineAccount`、profile、skin/cape、entitlement models 与 stage-specific
  exceptions。
- `MinecraftSessionService.join` 接受 `MinecraftOnlineAccount`；保留 raw-token 入口时应明确标为高级迁移 API，并同样确保
  redaction。
- production 服务 URL/host/route/method 固定且必须是 HTTPS，所有 upstream redirect 关闭。测试 URL 替换只在 internal/test
  seam 中提供，不能由不可信响应或 browser relay payload 控制。
- HTTP body 用 `kotlinx.serialization.json` 和 Ktor form/URL builder 生成，不手写 JSON escaping 或 query 拼接。
- 每个 endpoint 设独立响应 byte 上限，先限制再 decode。`429`、`5xx`、OAuth pending、永久认证拒绝、应用未获批分别建模。
- relay envelope 本身另设严格 byte 上限、版本不匹配错误和 operation/request-response 配对校验；relay client 只接受固定
  content type，且无论成功失败都不把 raw token body 拼进异常。

### `protocol-client`

- `MinecraftOnlineIdentity` 改为持有 `MinecraftOnlineAccount` 与 `MinecraftSessionService`，不持有 raw access token 或
  `MinecraftCryptography`。
- Encryption Request 到来时使用默认 expect/actual provider，计算 hash，调用 `sessionService.join(account, hash)`，发送
  Encryption Response 后再开启 transport encryption。
- online identity 遇到 `shouldAuthenticate = false` 时仍按匹配官方行为决定是否允许加密而不 join；不得把它解释为自动
  offline fallback。
- offline identity 收到 Encryption Request 继续明确失败。
- README 给出账号服务返回值到 `MinecraftOnlineIdentity` 的完整示例，以及“由 launcher 提供现成 Minecraft token”的高级示例。

### `protocol-server`

- suspend factory `MinecraftServerAuthentication.online(sessionService)` 调用 `protocol-auth` 的默认 expect
  backend，在返回配置前生成一次 RSA key pair；`Online` 构造器不公开 provider/key 参数。
- 每连接只生成新的 verify token 和 shared secret 状态，绝不复用 challenge。
- server 收到 response 后先验证 challenge，再开启 AES，再调用 `hasJoined`；只有返回 profile 才完成认证。
- `preventProxyConnections` 继续决定是否传 `ip`，并测试 IPv4/IPv6/无 IP 三种 query 形态。
- offline mode 不生成 RSA key、不调用 session service、不启用 encryption；online 失败不降级 offline。
- README 明确两种模式的选择方式和安全差异。

### `protocol-model` / `protocol-serialization`

- 对匹配官方发布版的 Encryption Request/Response stream codecs 做证据审计。
- 若官方 codec 定义 byte-array 最大长度，把精确限制表达为现有 wire annotation/serializer 配置；当前没有限制的 `publicKey`、
  `verifyToken`、encrypted secret 和 encrypted verify token 不再无界分配。
- 不凭经验写猜测常量。协议层只限制 wire 长度；RSA/SPKI/key-size/明文语义由 `protocol-auth` 校验。

### `protocol-transport`

- 复核当前各 socket target 的 AES/CFB8 actual 满足 key=IV、连续 stream 和双向独立 cipher state。
- client/server 集成测试验证 Encryption Response 前后切换边界，以及开启 compression 后的正确层次。
- 浏览器同一算法可由 `aes-js@3.1.2` 实现，并已通过 OpenSSL 对照、跨 chunk 连续状态、独立收发实例、RSA 组合和 headless Chrome
  验证；本计划把它列为浏览器正版登录的已满足算法前提。
- `aes-js` browser adapter、WebSocket carrier
  和代理的代码实施仍归独立 [browser-websocket-transport.md](browser-websocket-transport.md)
  ，避免两份计划同时拥有同一生产文件和测试。两者的明确交接合同是：`protocol-auth` 返回 16-byte shared secret，transport 在
  Encryption Response 写完/读完的边界以 key=IV=secret 开启两个独立的 continuous AES-CFB8 state。

### 构建与文档

- 不增加或注册新模块；`protocol-auth` 保持独立、全平台的唯一认证入口。
- 在 `gradle/libs.versions.toml` 增加 `cryptography-random` library alias 和 `node-forge = "1.4.0"` version key；Native
  复用已经存在的 `cryptography-provider-optimal` alias 与统一 `cryptography-kotlin` 版本。
  `protocol-auth/build.gradle.kts` 用 catalog accessor 传给 `npm("node-forge", ...)`，不在 source set 内再写版本字面量。
- `protocol-auth/build.gradle.kts` 在现有 targets 上建立最小 source-set 共享：JVM+Android 的 `javaCryptoMain`、default
  `nativeMain` actual，以及 default `webMain` actual。`node-forge` runtime npm dependency 分别声明给 `jsMain` 与
  `wasmJsMain`，因为 Node/browser 是同一 target 的 execution，不虚构 leaf production source set；JVM/Android/Native
  variants 不获得 npm 依赖。
- `commonMain` 增加 catalog 中已有的 `kotlinx-coroutines-core` implementation，用于 Device Code 规范轮询与 cancellable
  web key-generation bridge；继续复用现有 Ktor client、Okio、`kotlinx.serialization.json`、`cryptography-bigint`
  。只有依赖类型真正进入公共签名时才调整为 `api`。
- direct/relay client、relay wire model 和 framework-neutral handler 都进入 `protocol-auth/commonMain`。继续使用现有 Ktor
  client core 与 `kotlinx.serialization.json`；不为 handler 增加 Ktor server engine、反向依赖 `protocol-server` 或新建
  relay module。
- 如 provider 集成确实需要 Gradle plugin，先在根 `build.gradle.kts` 以 `apply false` 声明，再由子项目引用。
- 更新 `protocol-auth/AGENTS.md`、auth/client/server README 与根 README 的能力说明；根模块图不增加节点。实现期间先阅读每个被改模块最近的
  `AGENTS.md`。
- 不修改生成源码，不把 app registration、token、人工验证结果或反编译材料提交进仓库。

### 预计文件级改动

| 位置                                                                       | 计划改动                                                                                                                                                                             |
|----------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `gradle/libs.versions.toml`                                                | 新增 `cryptography-random` library alias 和 `node-forge` version key；复用现有 optimal-provider alias                                                                                |
| `protocol-auth/build.gradle.kts`                                           | 保留全部现有 targets；加入 coroutines、OAuth HTTP/JSON、全目标 CSPRNG、Native provider、`webMain` node-forge RSA actual 所需依赖与 source hierarchy；不引入 AES                      |
| `protocol-auth/src/commonMain/.../MinecraftEncryption.kt`                  | 原地保留 façade/hash/challenge 逻辑，删除公开 provider 参数，改用 internal RSA expect backend 与 common CSPRNG                                                                       |
| `protocol-auth/src/commonMain/.../PlatformMinecraftRsaBackend.kt`          | 新增 internal expect contract、opaque private-key/key-pair holder                                                                                                                    |
| `protocol-auth/src/jvmMain/.../JvmMinecraftCryptography.kt`                | 删除                                                                                                                                                                                 |
| `protocol-auth/src/javaCryptoMain/...`                                     | 新增 JVM+Android JCA actual                                                                                                                                                          |
| `protocol-auth/src/nativeMain/...`                                         | 新增 cryptography-kotlin optimal provider actual                                                                                                                                     |
| `protocol-auth/src/webMain/...`                                            | 新增单一 node-forge RSA actual 与薄 external declarations，供 JS/WasmJS 的 Node/browser execution 共用；不依赖 Node built-ins                                                        |
| `protocol-auth/src/commonMain/.../MinecraftAuthenticationHttpTransport.kt` | 新增 internal direct/relay route、固定 operation dispatch 与 service 使用的网络实现；无公共 transport 包装对象、无平台类型、无任意 URL proxy                                         |
| `protocol-auth/src/commonMain/.../MinecraftAuthenticationRelay*.kt`        | 新增 internal versioned typed wire models、public redacted raw-body boundary 与 framework-neutral relay handler；固定上游 allowlist、response limits、redirect/header/cache 安全约束 |
| `protocol-auth/src/commonMain/.../MinecraftSessionService.kt`              | `join` 消费 `MinecraftOnlineAccount`，保留 `hasJoined`，统一走 authentication HTTP transport、错误和响应限制                                                                         |
| `protocol-auth/src/commonMain/.../MicrosoftOAuth*.kt`                      | 新增 caller-owned app 配置、`MicrosoftOAuthService`、device code、PKCE、external-token、refresh、token models 与 OAuth errors；实际按职责拆成小文件而不是单个巨型文件                |
| `protocol-auth/src/commonMain/.../MinecraftAccountService.kt`              | 新增 XBL、XSTS、Minecraft token、entitlements/profile 的编排 façade                                                                                                                  |
| `protocol-client/src/commonMain/.../MinecraftClientIdentity.kt`            | online identity 改持有 account + session service，删除 raw token/crypto 字段                                                                                                         |
| `protocol-client/src/commonMain/.../MinecraftClientProtocol.kt`            | 调整 `/join`、Encryption Response 与 `enableEncryption` 的严格时序                                                                                                                   |
| `protocol-server/src/commonMain/.../MinecraftServerAuthentication.kt`      | 新增 suspend `online(sessionService)` factory；Online 内部持有一次生成的 opaque server crypto context，公共构造不接收 provider/key                                                   |
| `protocol-server/src/commonMain/.../MinecraftServerProtocol.kt`            | challenge/decrypt/AES/`hasJoined` 编排与 verified profile 处理                                                                                                                       |
| `protocol-model/src/commonMain/.../LoginPackets.kt`                        | 只在官方 codec 证据支持时增加 Encryption byte-array wire limits                                                                                                                      |
| `protocol-auth` common/platform tests                                      | 迁移旧 JVM-only crypto tests，新增 OAuth/direct/relay contract、relay allowlist 与安全限制、统一 RSA fixtures、每个 actual、browser bundle/interop 与 API 边界测试                   |
| auth/client/server README、`protocol-auth/AGENTS.md`、根 README            | 记录多登录入口、app/library/relay 边界、无内置 client ID、browser CORS、online/offline 选择和全平台算法矩阵                                                                          |
| `protocol-transport` browser files                                         | 不由本计划重复拥有；`aes-js` adapter 与 WebSocket 实施见关联计划，本计划只验证 shared-secret 交接合同                                                                                |

## 分阶段实施

### Phase 0：证据与可行性门禁

1. 用 `./gradlew -q minecraftVersion` 取得仓库选择版本，不在源码/文档复制版本字面量。
2. 以匹配官方 client/server JAR 与 authlib 为第一证据，记录 Login Start、Encryption Request/Response、RSA
   DER、hash、join/hasJoined、AES 切换和 Login Finished 的精确行为。
3. 官方实现未公开 launcher 账号交换细节时，再使用 revision-matched Minecraft Wiki；Microsoft OAuth/Xbox 部分使用 Microsoft
   官方文档。
4. 用应用作者自己拥有的获批测试 registration 做一次人工 OAuth scope/app-approval smoke test，只记录该 application
   configuration 的结论，不保存 client ID/scopes 作为库默认值，也不保存凭据。
5. 从实际 browser origin 复测 Microsoft device/token、XBL、XSTS、Minecraft Services 与 sessionserver 的 preflight/actual
   CORS。结果只用于确认 relay 覆盖范围；即使所有 endpoint 暂时开放，也保留 relay API，不能依赖第三方未承诺永久稳定的跨域策略。
6. 将已经完成的 node-forge/aes-js 原始浏览器证据分别归档到本计划和关联 transport 计划；正式实现仍要把相同 fixtures 转为
   owning module tests。
7. 完成 JVM、Android、每组 Native、JS Node、WasmJS Node、JS browser、WasmJS browser 的 RSA provider spike。任何 target
   失败都先换用维护中的平台库；不得退回自实现 RSA/PKCS#1 或 unsupported actual。
8. 验证统一 suspend key-generation factory 在各平台的取消、错误和并发语义；browser main thread 不得承担不可控长阻塞。

### Phase 1：默认加密 provider 与 API 去平台化

1. 在现有 `protocol-auth` 内建立 internal RSA expect/actual source sets，不创建新 module。
2. 用独立固定向量和跨 provider fixtures 实现/验证 common CSPRNG、RSA keygen、SPKI DER、PKCS#1 encryption/decryption；JS/Wasm
   的 Node 与 browser executions 分别执行。
3. 删除 `JvmMinecraftCryptography`，从 client identity 和 server online config 移除 provider 参数。
4. 为 default `webMain` actual 接入 `node-forge@1.4.0`，把已验证 raw JS 场景转成 JS Node/browser 与 WasmJS Node/browser
   owning tests；npm 类型不得进入 ABI，也不能添加不存在的 environment-specific production source set。
5. 将低层 fake/provider 注入改为 `protocol-auth` internal test seam，保持现有 offline API 与行为不变。

### Phase 2：OAuth transport 与安全 model

1. 建立必填 caller-owned client ID、固定 endpoint/operation、请求/响应 serializer、body 上限、redacted token wrappers
   和异常层次；公共 model 中不存在 client secret。
2. 让三个 common service 直接接收调用方 `HttpClient`，并用额外 relay endpoint 构造器显式选择 relay。internal direct route
   直接 使用该 client；internal relay route 只序列化 typed operation，不接受 URL/method/header。两者共享 endpoint codec
   和错误语义。
3. 实现 common、framework-neutral `MinecraftAuthenticationRelayHandler`：version negotiation、operation allowlist、固定
   HTTPS upstream、禁 redirect/cookie/header forwarding、request/response limit、`no-store` 与脱敏错误；不引入 server
   engine。
4. 实现 Authorization Code + PKCE、state 校验和单次 pending transaction；库只收发 URI，不拥有浏览器/WebView/deep-link/loopback
   UI。
5. 实现 Device Code display state 与规范轮询，明确 user code、private device code 和 authorization code 的区别。
6. 实现 refresh-token rotation、externally acquired Microsoft token 与 existing Minecraft account 入口。
7. 用 MockEngine 对同一 operation 比较 direct upstream request 与 relay handler upstream request 的
   method/URL/header/body 完全一致，并验证无 client ID 默认回退、未知 wire version/operation fail closed。

### Phase 3：Xbox 与 Minecraft Services 交换

1. 实现 XBL user authenticate、XSTS authorize 和 `XErr` 映射。
2. 实现 `login_with_xbox`，确保使用匹配的 `uhs`。
3. 实现 entitlements/profile 获取、UUID/name/skin/cape 解码与 unknown-field/unknown-item 前向兼容。
4. 产出 `MinecraftOnlineAccount` 和 rotated refresh token，不让 XBL/XSTS 临时 token 逃逸公共结果；direct 与 relay 路径返回同一
   model/exception。

### Phase 4：session service 与 client/server 编排

1. `join` 改收账号对象，补齐 client 的精确发送/启用顺序。
2. server Online 内建 key pair、逐连接 challenge、decrypt/constant-time verify、AES 切换和 `hasJoined`。
3. 登录成功只采用 `hasJoined` profile，审计 UUID/name/properties 处理。
4. 审计 Encryption packet codec 上限，并只提交官方证据支持的限制。
5. 明确 compression、Login Finished、Configuration 的状态转换回归测试。

### Phase 5：跨平台和发布验证

1. 先顺序运行：
    - `./gradlew :protocol-auth:jvmTest`
    - `./gradlew :protocol-client:jvmTest`
    - `./gradlew :protocol-server:jvmTest`
2. JVM 稳定后，按仓库标准任务覆盖 Android host、Node、Wasm/Node 和当前 desktop/mobile Native 编译与可运行测试；Gradle
   wrapper 命令绝不并发。
3. `protocol-auth` 的 JS browser 与 WasmJS browser klib/bundle 必须成功编译，且分别以实际浏览器 smoke 证明 OAuth
   random/hash、node-forge RSA actual、SPA direct token route 和 relay client 可执行；通过受控 relay/mock upstream 跑完“PKCE
   direct token 或 Device Code relayed token → relayed XBL → XSTS → Minecraft profile → `/join`”的无真实凭据 contract
   scenario。遵守仓库“browser execution 不是标准 gate”的规则，不为此引入通用 browser-driver infrastructure；人工/专项
   evidence 不能被另一个 target 的结果替代。
4. 在每个当前 socket target 至少运行一次“默认 RSA actual + 真实 transport AES/CFB8 + mock session server”的
   client/server 成对 online 登录测试。
5. 做外部 consumer smoke test：只依赖 `protocol-auth` 的 KMP 小项目能在其所有发布 target 编译并调用同一 account/RSA
   façade；同一 browser consumer 可配置 relay；单独的后端 consumer 可只依赖 `protocol-auth` 嵌入 relay handler。依赖
   client/server 的应用代码中没有 JVM/Node/Native/browser crypto 符号。
6. 检查公共签名、POM/Gradle module metadata、runtime classpath 与 source JAR；`protocol-auth` 不依赖
   client/server/transport，只有 JS/WasmJS variants 携带 node-forge，JVM/Android/Native 不携带 npm 依赖，任何 variant 都不携带
   client secret 或内置 client ID。
7. 最后运行适用的 `allTests`。浏览器 socket 端到端属于关联 transport 计划，但 browser `protocol-auth` actual 本身是本计划验收项。

### Phase 6：使用文档与人工互操作

1. 文档并列给出 Authorization Code + PKCE（系统浏览器/WebView/browser host）、Device Code、external MSAL/broker
   token、refresh、已有 Minecraft token，以及 online/offline server/client 的完整最小示例。
2. 分别给出 direct、browser relay client 和框架无关 relay handler 的最小示例；明确认证 HTTP relay 与 WebSocket/TCP
   中继不是同一协议，即使应用把它们共同部署。
3. 单独记录 library/application responsibility table、caller-owned client ID、app approval、redirect ownership、token
   storage、refresh rotation、relay trust/CORS/CSRF/SSRF/rate-limit 边界、日志脱敏和 online 不得自动降级的安全要求。
4. 提供不进入标准 CI 的人工官方互操作 checklist：
    - 获批 app + 自有正版测试账号登录；
    - 在实际 browser 中通过应用自有 relay 完成同一账号登录和 `/join`；
    - 用账号结果连接匹配官方 online server；
    - 本库 online server 接受匹配官方 client；
    - 验证错误 token、错误 challenge、过期 refresh token、无 Java profile 和 app 未获批的行为。
5. 人工测试从交互输入/应用安全存储获得秘密，不在普通测试中读取共享环境变量，不提交日志或 token。

## 测试矩阵

### `protocol-auth/commonTest`

- App configuration：client ID/scopes 必填且 blank/empty/duplicate/混合 audience 拒绝、无默认 fallback、无 `clientSecret`
  API、不同 registration 的 pending/refresh state 不可混用。
- Device code：成功、pending、slow_down、declined、expired、取消、响应超限；relay handler 每次只执行一次 poll，不接管
  interval、不缓存 pending state。
- PKCE：S256 向量、state mismatch、redirect error、重复消费、过期 transaction，以及系统浏览器/WebView 返回相同 redirect
  时完全相同的协议处理。
- Application boundary：库只返回 authorization/display data，不打开 UI、不启动 loopback listener、不写 token store、不关闭
  caller `HttpClient`。
- HTTP routing：direct 与 relay 对每个 operation 产生相同 upstream method/URL/form/JSON；relay client 只访问配置的 relay
  endpoint。browser 只有 SPA code/refresh 按 Microsoft 的正式 CORS contract 直连，其余受阻 endpoint 不被页面脚本直接访问。
- Relay wire/security：版本协商、未知/错配 operation、畸形/超限 envelope、任意 URL/header
  注入尝试、redirect、cookie/app-auth-header 不上游转发、response 超限、`no-store`、429/`Retry-After` 和所有 raw body 脱敏。
- Relay boundary：handler 不安装/关闭 caller `HttpClient`，不启动 server，不实现用户会话鉴权/CSRF/限流，也不依赖
  `protocol-server`、`protocol-client` 或 `protocol-transport`。
- External entry：MSAL/broker Microsoft token 与已有 Minecraft account 能进入正确的后半链路，不要求伪造 UI state。
- Refresh：token 轮换、无新 refresh token、撤销、永久/暂时错误。
- XBL/XSTS：精确 request JSON、`d=` 前缀、wrong/missing `uhs`、各类 `XErr`。
- Minecraft Services：login response、403 app registration、429/5xx、entitlements 变化、profile 缺失/畸形/超限。
- 所有 token wrapper、exception、debug rendering 和 `toString` 的泄漏测试。
- 使用 `runTest`、virtual time 和显式 completion，不使用 delay/sleep 形成概率性轮询测试。

### `protocol-auth` RSA common/platform tests

- 官方形态 1024-bit RSA key pair 与 SPKI DER。
- 不只做 self-roundtrip：使用独立工具生成并审核的固定 DER/ciphertext fixture，验证每个 provider 的互操作。
- PKCS#1 v1.5 最大明文边界、错误 ciphertext、错误 key type 与错误 DER；另用非 1024-bit SPKI 锁定 matching official client
  的实际接受/拒绝行为，而不是预设必须拒绝。
- CSPRNG 长度与不复用的基本性质；不写脆弱的统计随机性测试。
- private key 和 input/output defensive copy。
- provider 原始失败映射为统一公共异常。
- JVM/Android JCA、各 Native optimal provider，以及同一 node-forge `webMain` actual 在 JS Node/browser、WasmJS Node/browser
  四种 execution 中各有实际 backend 证据，不能只验证 expect contract 或其中一种 runtime。
- browser tests 额外覆盖任意二进制 `0x00/0x80/0xff`、纯 browser bundle 无 Node built-ins、Web Crypto CSPRNG，以及 key
  generation 不造成未约束的主线程阻塞。

### `protocol-client` / `protocol-server`

- offline 模式不触发 session HTTP、RSA 或 AES。
- online 模式不要求调用方传 crypto，并且 key pair 每个 Online 配置只生成一次。
- 每连接 challenge 与 secret 不复用。
- `/join` 发生在 Encryption Response 发送前；client 只在 response 写完后 enable encryption。
- server 只在 response 读完并验证后 enable encryption，再调用 `/hasJoined`。
- 错误 challenge、错误 ciphertext、16 字节以外 secret、错误/空 hasJoined profile 全部终止。
- Login Start UUID 不会覆盖 verified profile UUID。
- `preventProxyConnections` 的 IP query 行为。
- 加密后 Set Compression、Login Finished、Acknowledged、Configuration 状态顺序。
- online 认证失败绝不回退 offline。

## 完成标准

- `protocol-auth` 继续是一个模块，并在其每个已发布 target 上以相同 common API 完成 OAuth、Xbox/Minecraft account
  exchange、session service、server hash 和 Login RSA；不存在 compile-only/unsupported actual。browser 以正式 SPA CORS 路径完成
  PKCE code/refresh，并以本模块提供的 relay client + 应用部署的同源 relay handler 完成其余受限 HTTP 链路，而不是把未实现部分交给用户另造协议。
- 使用方可以选择 PKCE、Device Code、外部 Microsoft token、refresh 或已有 Minecraft account，成功后得到相同账号结果；无需引用任何平台
  crypto 类型。
- `JvmMinecraftCryptography` 和公开 provider 注入不存在；internal default RSA backend 由 `protocol-auth` 自己的
  expect/actual 选择。
- core library 没有内置 client ID、默认 scope selection、官方/第三方 fallback 或 `clientSecret` API；即使库提供受测 scope
  value 常量，调用方仍须显式选择。app registration、获批 scopes、UI host、redirect 捕获、HttpClient 生命周期、token storage 与
  relay 部署/访问控制均由下游应用拥有。
- Microsoft → Xbox → XSTS → Minecraft Services → profile → `/join` → `/hasJoined` 的数据接收方、token 类型和时序均由
  typed API 与测试约束。
- 游戏 server 从未收到 access/refresh/XBL/XSTS token，只以 session server 返回 profile 建立认证身份。
- RSA/SPKI/PKCS#1、signed SHA-1 hash、AES/CFB8 切换边界和 continuous stream 与匹配官方实现互操作。
- offline 行为保持可选且不变；online 失败不会静默降级。
- 所有当前 socket target 的 auth actual 至少通过平台 crypto 测试与端到端编排测试；JS/Wasm browser auth actual
  通过各自的实际浏览器算法 smoke；发布 metadata 允许 `protocol-auth` 独立外部消费。
- 本计划明确记录浏览器 AES/CFB8 可由 `aes-js` 实现且已经验证，但其生产 adapter/carrier
  任务与详细验证只由 [browser-websocket-transport.md](browser-websocket-transport.md) 拥有；两份计划没有重复生产文件所有权。
- browser 认证 HTTP relay 只允许 versioned auth operations，不能指定 upstream URL/header；WebSocket/TCP 中继继续保持
  Minecraft 业务不透明。两条 relay 路径可共同部署但在 API、权限、数据可见性和测试上分离。

## 主要风险与处理

| 风险                                                                            | 处理                                                                                                                                                                                 |
|---------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Minecraft Services 拒绝未获批 client ID                                         | Phase 0 前置验证；typed permanent error；要求应用提供其注册，不内置本项目/他人 client ID                                                                                             |
| 下游希望零配置而要求共享 client ID                                              | 保持 core 必填；只有另行获准、另行评审的 opt-in product/companion 才能拥有共享 registration                                                                                          |
| OAuth/Xbox scope 文档差异或服务端变更                                           | scopes 由应用显式配置且不跨 audience 混用；获批应用 smoke test 验证其配置；MockEngine 锁定请求，core 不冻结一个冒充普适的默认值                                                      |
| Microsoft/Minecraft/session endpoint CORS 不完整或变化                          | browser 的 SPA code/refresh 只依赖 Microsoft 明确文档化的 `spa` CORS contract；其他操作始终支持 typed same-origin auth relay。Phase 0 复测不会把偶然开放的 endpoint 当成永久直连合同 |
| 认证 relay 成为 open proxy/SSRF 工具                                            | sealed operation allowlist、固定 HTTPS host/route/method、禁 redirect/任意 header/cookie forwarding、严格 body limit 和 fail-closed wire version                                     |
| 认证 relay 接触 token 后被误当作无信任的透明代理                                | 文档明确其可信后端属性；应用负责 origin/session/CSRF/rate limit/TLS/日志与合规；库默认 `no-store` 且全链路脱敏                                                                       |
| browser 与 relay handler 版本错配                                               | wire protocol 显式版本；未知版本/operation 拒绝；外部 consumer contract tests 同时覆盖 client 与 handler                                                                             |
| 某 Native provider 不允许 RSA-1024/PKCS#1 encryption                            | 平台 spike 先行；改用维护中的 Apple Security/OpenSSL backend；不自造算法                                                                                                             |
| node-forge `webMain` interop 在 JS/WasmJS 或 Node/browser 某一 execution 不等价 | 两个 target、四种 runtime 分别 bundle/运行相同固定 fixture；修正薄 adapter 或寻找能同时覆盖该 production artifact 的维护型替代库，不提交 runtime 分支下的 unsupported                |
| node-forge key generation 在 browser 阻塞 UI                                    | common keygen 为 suspend；使用 callback/worker-capable API 并验证 event-loop progress，固定 RSA-1024；若 provider 无法让出事件循环则视为 browser server capability 未完成            |
| aes-js 维护与吞吐风险                                                           | auth 计划只依赖已验证交接结论；adapter 隔离、性能测量和替换决策由关联 transport 计划拥有                                                                                             |
| token 从 data class、异常或日志泄漏                                             | opaque wrapper、redacted rendering、泄漏测试、无自动日志                                                                                                                             |
| refresh token 更新中途丢失                                                      | 完整 refresh 成功后返回 replacement，由应用原子持久化；库不静默覆盖                                                                                                                  |
| entitlement 商品名变化                                                          | profile 成功作为最终依据，entitlements 保留未知项，不硬编码唯一商品集合                                                                                                              |
| packet byte array 无界                                                          | 审计官方 codec 后在 model/serialization 加精确上限，并在 crypto 前二次语义校验                                                                                                       |
| cipher 在 packet 边界被重置                                                     | transport stateful integration tests 覆盖多 packet、压缩前后与双向流                                                                                                                 |

## 实施时使用的证据入口

证据冲突时严格遵守仓库顺序：匹配官方 JAR/authlib → revision-matched Minecraft Wiki → exact-version MCProtocolLib →
exact-version Minestom。

- 仓库选择版本与官方 artifacts：`MinecraftTarget.MINECRAFT_VERSION`、`./gradlew -q minecraftVersion`、Gradle 产出的
  matching official server/client/authlib。
- 官方代码重点：client login listener、server login listener、`net.minecraft.util.Crypt`、authlib
  `YggdrasilMinecraftSessionService`，以及匹配发布版的 packet stream codecs。
- [Microsoft OAuth 2.0 device authorization grant](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-device-code)
- [Microsoft authorization code flow and PKCE](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)
- [Microsoft SPA redirect URI、PKCE 与 CORS 要求](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow#redirect-uris-for-single-page-apps-spas)
- [Microsoft public and confidential clients](https://learn.microsoft.com/en-us/entra/identity-platform/msal-client-applications)
- [Microsoft browser/WebView hosting guidance](https://learn.microsoft.com/en-us/entra/msal/dotnet/acquiring-tokens/using-web-browsers)
- [Microsoft refresh tokens](https://learn.microsoft.com/en-us/entra/identity-platform/refresh-tokens)
- [Xbox Live website authentication](https://learn.microsoft.com/en-us/gaming/gdk/docs/services/fundamentals/s2s-auth-calls/service-authentication/live-website-authentication)
- [Minecraft application registration information](https://aka.ms/AppRegInfo)
- [Minecraft Wiki：Microsoft authentication scheme](https://minecraft.wiki/w/Microsoft_authentication)
- [Minecraft Wiki：Java Edition protocol encryption](https://minecraft.wiki/w/Java_Edition_protocol/Encryption)
- [Minecraft Wiki：Session server](https://minecraft.wiki/w/Mojang_API#Session_server)
- [cryptography-kotlin operation support matrix](https://whyoleg.github.io/cryptography-kotlin/primitives/operations/)
- [node-forge](https://github.com/digitalbazaar/forge)
- [aes-js](https://github.com/ricmoo/aes-js)
- Browser RSA/AES evidence and transport implementation
  boundary: [browser-websocket-transport.md](browser-websocket-transport.md)

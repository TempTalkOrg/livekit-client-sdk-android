# QUIC 自签证书验证 & 自定义 DNS（IP 直连）方案

## 1. 概述

本方案为 QUIC 信令通道（ttsignal）新增两项安全与网络能力：

| 功能 | 说明 |
|------|------|
| **预埋根 CA 证书验证** | 客户端携带自签根 CA 证书（PEM），在 TLS 握手时验证服务端证书链 + 主机名 |
| **自定义 DNS / IP 直连** | 连接 URL 使用 IP 地址，同时通过独立参数指定真实域名用于 SNI 和主机名校验 |

支持平台：**Android**（通过 JNI）、**桌面端 Electron/Mac**（通过 Node-API）。

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                     应用层 (App)                         │
│  ConnectOptions(quicServerHost, quicCaCertPem)          │  ← Android
│  ttsignal.createConnector({server_host, ca_cert_pem})   │  ← Electron
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                   SDK 接入层                              │
│  Android: QuicTransport.connect() → Config.java         │
│  Electron: index.js → __createConnector__(config)       │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                   桥接层 (JNI / NAPI)                     │
│  JNI_SMPConfig.cpp:  Java Config → BCFObject            │
│  JsSMPConnectorWrap: JS config → BCFObject              │
│  字段映射:                                               │
│    serverHost / server_host → "server_host"             │
│    caCertPem / ca_cert_pem  → "ca_cert_pem"             │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│               C++ 核心层 (SMPConnector)                   │
│  Config::Init()         解析 ca_cert_pem                 │
│  Connect_Internal()     设置 cert_verify_flag + SNI      │
│  on_conn_cert_verify()  OpenSSL 证书链验证 + 主机名校验   │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│                xquic (QUIC 协议栈)                        │
│  xqc_tls_init_client_ssl()  设置 SNI / SSL_VERIFY_PEER  │
│  xqc_ssl_cert_verify_cb()   触发上层 cert_verify_cb      │
└─────────────────────────────────────────────────────────┘
```

## 3. 新增配置参数

### 3.1 参数定义

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `server_host` | String | 仅 IP 直连时必填 | TLS SNI 主机名，用于 ClientHello 中的 SNI 扩展和证书 SAN/CN 主机名校验。不设置时自动使用连接 URL 中的 host |
| `ca_cert_pem` | String (PEM) | 否 | 根 CA 证书（PEM 文本），支持多个证书拼接。设置后启用服务端证书链验证 + 主机名校验。不设置时不进行证书验证（与原有行为一致） |

### 3.2 各层参数命名

| 层级 | server_host | ca_cert_pem |
|------|-------------|-------------|
| Android `ConnectOptions` | `quicServerHost: String?` | `quicCaCertPem: String?` |
| Android `Config.java` | `serverHost` | `caCertPem` |
| JS `createConnector()` | `config.server_host` | `config.ca_cert_pem` |
| C++ `BCFObject` | `"server_host"` | `"ca_cert_pem"` |
| C++ `SMPConnector::Config` | `server_host` | `ca_cert_pem` |

## 4. 调用链路详解

### 4.1 Android 端

```
Room.connect(url, token, ConnectOptions)
  → RTCEngine.join()
    → SignalClient.connect()
      → SignalTransport.Factory.create(options)
        → QuicTransport(connector)
          → QuicTransport.connect(url, token, options, listener)
            ┌─────────────────────────────────────────────┐
            │ Config().apply {                            │
            │   hostname = uri.host      // 连接目标      │
            │   port = uri.port          // 连接端口      │
            │   serverHost = options.quicServerHost  // ★ │
            │   caCertPem  = options.quicCaCertPem   // ★ │
            │ }                                           │
            └─────────────────────┬───────────────────────┘
                                  │ JNI
            ┌─────────────────────▼───────────────────────┐
            │ JNI_SMPConfig::ConvertFromJava()            │
            │   pConfig->PutString("server_host", ...)    │
            │   pConfig->PutString("ca_cert_pem", ...)    │
            └─────────────────────┬───────────────────────┘
                                  ↓ C++
            SMPConnector::Config::Init() → SMPConnection::Connect_Internal()
```

### 4.2 桌面端 (Electron / Mac)

```
ttsignal.createConnector({
  alpn: 'ttsignal',
  server_host: 'real.domain.com',     // ★
  ca_cert_pem: '-----BEGIN...',       // ★
})
  → JsSMPConnectorWrap::Create()
    → sockConfig += *pConfig   // JS 配置合入
      → SMPConnector::Create(&sockConfig)
        → Config::Init()  解析 server_host / ca_cert_pem
```

## 5. 核心实现

### 5.1 TLS 握手流程 (C++ 层)

```
Connect_Internal()
  │
  ├─ ca_cert_pem 非空？
  │   ├─ YES → cert_verify_flag = NEED_VERIFY | ALLOW_SELF_SIGNED
  │   └─ NO  → cert_verify_flag = 0（不验证，保持原有行为）
  │
  ├─ 确定 SNI 主机名:
  │   └─ sni_host = server_host ?: URL中的host
  │
  └─ jqc_connect(engine, settings, sni_host, conn_ssl_config, ...)
       │
       ↓ xquic 内部
  xqc_tls_init_client_ssl()
    ├─ SSL_set_tlsext_host_name(ssl, sni_host)     // 设置 SNI
    ├─ X509_VERIFY_PARAM_set1_host(ssl, sni_host)  // 预设主机名校验
    └─ SSL_set_verify(ssl, SSL_VERIFY_PEER, xqc_ssl_cert_verify_cb)
         │
         ↓ OpenSSL 握手
  xqc_ssl_cert_verify_cb(ok, store_ctx)
    ├─ ok == 1 → 系统信任库验证通过 → 直接返回成功
    ├─ err = UNABLE_TO_GET_ISSUER_CERT_LOCALLY → 穿透 ★
    ├─ err = SELF_SIGNED_CERT (且有 ALLOW_SELF_SIGNED 标志) → 穿透 ★
    └─ 其他错误 → 直接失败
         │
         ↓ 穿透后提取证书链，回调:
  SMPConnector::on_conn_cert_verify(certs[], cert_len[], certs_len)
```

### 5.2 证书验证回调 (`on_conn_cert_verify`)

```
on_conn_cert_verify(certs, cert_len, certs_len, conn_user_data)
  │
  ├─ 1. 前置检查
  │   ├─ ca_cert_pem 为空 → return 0（跳过验证）
  │   └─ 服务端未提供证书 → return -1（失败）
  │
  ├─ 2. 构建信任库 (X509_STORE)
  │   └─ while (PEM_read_bio_X509()) → X509_STORE_add_cert()
  │      支持多个根 CA 证书拼接在同一个 PEM 字符串中
  │
  ├─ 3. 解析服务端证书链
  │   ├─ certs[0] → d2i_X509() → 叶子证书 (DER 格式)
  │   └─ certs[1..n] → d2i_X509() → 中间证书链
  │
  ├─ 4. 主机名校验
  │   ├─ hostname = config.server_host ?: connection.host_
  │   ├─ X509_VERIFY_PARAM_set_hostflags(NO_PARTIAL_WILDCARDS)
  │   └─ X509_VERIFY_PARAM_set1_host(hostname)
  │
  ├─ 5. 执行验证
  │   └─ X509_verify_cert(store_ctx)
  │      ├─ 成功 → return 0
  │      └─ 失败 → 记录错误日志 → return -1
  │
  └─ 6. 资源清理
      X509_STORE_CTX_free / X509_free / sk_X509_pop_free / X509_STORE_free
```

## 6. 改动文件清单

### 6.1 LiveKit SDK 层 (Android)

| 文件 | 改动 |
|------|------|
| `livekit-android-sdk/.../ConnectOptions.kt` | 新增 `quicServerHost` 和 `quicCaCertPem` 参数 |
| `livekit-android-sdk/.../room/transport/QuicTransport.kt` | 将 `ConnectOptions` 中的参数透传到 ttsignal `Config` |

### 6.2 ttsignal Java 层 (Android)

| 文件 | 改动 |
|------|------|
| `ttsignal/src/main/java/org/difft/android/smp/Config.java` | 新增 `serverHost` 和 `caCertPem` 字段 |

### 6.3 ttsignal 桥接层 (JNI / NAPI)

| 文件 | 改动 |
|------|------|
| `ttsignal/ttsignal/src/cpp/jni/JNI_SMPConfig.cpp` | 提取 Java `serverHost` / `caCertPem` → BCFObject |
| `ttsignal/ttsignal/src/cpp/jni/JNI_SMPConnectorWrap.cpp` | 移除硬编码 `server_host = "example.com"` |
| `ttsignal/ttsignal/src/cpp/napi/JsSMPConnectorWrap.cpp` | 移除硬编码 `server_host = "example.com"` |

### 6.4 ttsignal C++ 核心层

| 文件 | 改动 |
|------|------|
| `ttsignal/ttsignal/src/cpp/SMPConnector.h` | `Config` 类新增 `ca_cert_pem` 成员 |
| `ttsignal/ttsignal/src/cpp/SMPConnector.cpp` | `Config::Init()` 解析 ca_cert_pem |
| 同上 | `Connect_Internal()` 设置 cert_verify_flag 和 SNI fallback |
| 同上 | `on_conn_cert_verify()` 完整实现证书链验证 + 主机名校验 |

### 6.5 ttsignal JS 接口层 (Electron)

| 文件 | 改动 |
|------|------|
| `ttsignal/ttsignal/src/js/index.js` | `createConnector` JSDoc 新增 `server_host` / `ca_cert_pem` 参数文档 |

## 7. 上层使用示例

### 7.1 Android 端

```kotlin
// 场景 1: IP 直连 + 自签 CA 验证
val caCert = """
-----BEGIN CERTIFICATE-----
MIIBxTCCAWugAwIBAgIJAL...
-----END CERTIFICATE-----
""".trimIndent()

val options = ConnectOptions(
    useQuicSignal = true,
    quicServerHost = "real.domain.com",   // TLS SNI + 主机名校验
    quicCaCertPem = caCert,               // 预埋根 CA 证书
)
room.connect("wss://1.2.3.4:443", token, options)


// 场景 2: 域名连接 + 自签 CA 验证（无需自定义 DNS）
val options = ConnectOptions(
    useQuicSignal = true,
    // quicServerHost 不设置 → 自动使用 URL 中的域名
    quicCaCertPem = caCert,
)
room.connect("wss://real.domain.com:443", token, options)


// 场景 3: 多个根 CA 证书
val multiCaCert = caCert1 + "\n" + caCert2 + "\n" + caCert3
val options = ConnectOptions(
    useQuicSignal = true,
    quicServerHost = "real.domain.com",
    quicCaCertPem = multiCaCert,         // 拼接多个 PEM 证书
)


// 场景 4: 不启用证书验证（向后兼容，默认行为）
val options = ConnectOptions(useQuicSignal = true)
room.connect("wss://server.com:443", token, options)
```

如需将 DER 二进制证书转为 PEM：

```kotlin
fun derToPem(derBytes: ByteArray): String {
    val base64 = android.util.Base64.encodeToString(derBytes, android.util.Base64.NO_WRAP)
    return "-----BEGIN CERTIFICATE-----\n" +
        base64.chunked(64).joinToString("\n") +
        "\n-----END CERTIFICATE-----"
}

val caCertPem = derFileList.joinToString("\n") { derToPem(it) }
```

### 7.2 桌面端 (Electron / Mac)

```javascript
// 场景 1: IP 直连 + 自签 CA 验证
const connector = ttsignal.createConnector({
    alpn: 'ttsignal',
    server_host: 'real.domain.com',
    ca_cert_pem: '-----BEGIN CERTIFICATE-----\n...\n-----END CERTIFICATE-----'
});

const conn = connector.createConnection({});
conn.connect('https://1.2.3.4:443/path', {}, 10000, callback);


// 场景 2: 域名连接 + 自签 CA 验证
const connector = ttsignal.createConnector({
    alpn: 'ttsignal',
    // server_host 不设置 → 自动使用连接 URL 中的域名
    ca_cert_pem: fs.readFileSync('/path/to/ca.pem', 'utf8')
});


// 场景 3: 多个根 CA 拼接
const ca1 = fs.readFileSync('/path/to/ca1.pem', 'utf8');
const ca2 = fs.readFileSync('/path/to/ca2.pem', 'utf8');
const connector = ttsignal.createConnector({
    alpn: 'ttsignal',
    server_host: 'real.domain.com',
    ca_cert_pem: ca1 + '\n' + ca2
});
```

## 8. 安全设计说明

### 8.1 向后兼容

- `ca_cert_pem` 和 `server_host` 均为可选参数，默认值为空
- 不设置时行为与改动前完全一致：不进行证书验证、`cert_verify_flag = 0`
- 现有调用方无需任何改动

### 8.2 Fallback 机制

| 场景 | SNI (TLS ClientHello) | 主机名校验 |
|------|----------------------|-----------|
| `server_host` + `ca_cert_pem` 均设置 | `server_host` | `server_host` |
| 仅设置 `ca_cert_pem`，`server_host` 为空 | URL 中的 host (fallback) | URL 中的 host (fallback) |
| 均不设置 | URL 中的 host (fallback) | 不校验 |

### 8.3 多根 CA 支持

底层使用 OpenSSL `X509_STORE` 信任库，通过 `while (PEM_read_bio_X509())` 循环加载：

- 支持将多个 PEM 证书拼接在一个字符串中传入
- `X509_verify_cert()` 会自动匹配信任库中的任意 CA
- 推荐统一使用 PEM 文本格式（DER 二进制可在上层转换后拼接）

### 8.4 xquic cert_verify_flag 设计

使用 `XQC_TLS_CERT_FLAG_NEED_VERIFY | XQC_TLS_CERT_FLAG_ALLOW_SELF_SIGNED` 组合：

- `NEED_VERIFY`：启用 `SSL_VERIFY_PEER`，OpenSSL 在握手期间验证服务端证书
- `ALLOW_SELF_SIGNED`：确保以下两种 OpenSSL 错误不被 xquic 直接拒绝，而是穿透到我们的自定义回调处理：
  - `X509_V_ERR_UNABLE_TO_GET_ISSUER_CERT_LOCALLY`（CA 不在系统信任库 — 主要场景）
  - 自签名证书错误（服务端证书本身是自签名的场景）

### 8.5 资源管理

`on_conn_cert_verify` 回调中所有 OpenSSL 对象均在 `cleanup` 标签统一释放，所有 goto 路径无资源泄漏或双重释放风险。

## 9. 限制与注意事项

1. **IP 直连时必须设置 `server_host`**：若 URL 中为 IP、`server_host` 为空，fallback 会用 IP 进行主机名校验。大多数证书不包含 IP SAN，验证将失败
2. **证书格式**：底层仅支持 PEM 文本格式，DER 二进制需在上层（Java/JS）转换为 PEM 后传入
3. **xquic 回调时机**：仅当 OpenSSL 内建验证失败时触发自定义回调。若 CA 恰好在系统信任库中，OpenSSL 直接验证通过，不触发自定义回调（行为正确）

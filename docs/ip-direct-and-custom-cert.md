# IP 直连与自签证书验证 — Android SDK 使用指南

## 1. 功能概述

LiveKit Android SDK 支持 **自签证书验证** 和 **IP 直连** 能力：

| 传输通道 | 自签证书验证 | IP 直连 |
|---------|:----------:|:------:|
| **WebSocket** | ✅ | ✗ |
| **QUIC (ttsignal)** | ✅ | ✅ |

- **WebSocket**：通过域名正常连接，使用自签 CA 证书做证书链校验
- **QUIC**：支持 IP 直连（绕过 DNS） + 自签 CA 证书做证书链校验 + 域名匹配校验

自签名根证书由上层应用传递，SDK 层不内置。

### 向后兼容

| 配置 | 行为 |
|------|------|
| 不设置 `caCertPem` | 使用系统默认信任库，与原有行为完全一致 |
| 设置 `caCertPem` 为空字符串 | 等同于不设置 |
| 设置有效 `caCertPem` | 启用自签证书验证 |
| 设置无效 `caCertPem` | 记录错误日志，降级到系统默认信任库（不崩溃） |

---

## 2. 新增参数

在 `ConnectOptions` 中新增两个参数：

### `caCertPem: String?`

| 属性 | 说明 |
|------|------|
| 类型 | `String?` |
| 默认值 | `null` |
| 格式 | PEM 编码的 Root CA 证书，包含 `-----BEGIN CERTIFICATE-----` 和 `-----END CERTIFICATE-----` |
| 适用传输 | WebSocket（证书验证）和 QUIC（证书验证 + 域名校验） |

### `serverHost: String?`

| 属性 | 说明 |
|------|------|
| 类型 | `String?` |
| 默认值 | `null` |
| 适用传输 | **仅 QUIC**（WebSocket 忽略此参数） |

逻辑域名，用于 QUIC IP 直连场景下的 TLS SNI 和证书 hostname 验证。当连接 URL 使用 IP 地址时必须设置。不设置时自动使用连接 URL 中的 host。

> **重要**：`serverHost` 应设置为证书中 SAN 字段对应的逻辑域名（如 `livekit.example.com`），而非 IP 地址。

---

## 3. 使用示例

### 场景一：WebSocket + 自签证书

WebSocket 通道通过域名正常连接，仅启用自签 CA 证书验证。

```kotlin
val caCert = """
-----BEGIN CERTIFICATE-----
MIIBxTCCAWugAwIBAgIJAL...
...（Root CA 证书内容）...
-----END CERTIFICATE-----
""".trimIndent()

val options = ConnectOptions(
    caCertPem = caCert,
    // serverHost 不需要设置，WebSocket 会忽略
)

room.connect("wss://livekit.mycompany.com:443", token, options)
```

### 场景二：QUIC IP 直连 + 自签证书

QUIC 通道通过 IP 地址直连，同时使用自签 CA 证书验证。

```kotlin
val options = ConnectOptions(
    useQuicSignal = true,
    serverHost = "livekit.mycompany.com",   // 逻辑域名（匹配证书 SAN）
    caCertPem = caCert,                      // 自签 Root CA 证书
)

// URL 使用 IP 地址直连
room.connect("wss://203.0.113.50:443", token, options)
```

### 场景三：QUIC 域名连接 + 自签证书

QUIC 通道通过域名连接（非 IP 直连），使用自签 CA 证书验证。

```kotlin
val options = ConnectOptions(
    useQuicSignal = true,
    caCertPem = caCert,
    // serverHost 不设置 → 自动使用 URL 中的域名
)

room.connect("wss://livekit.mycompany.com:443", token, options)
```

### 场景四：多个根 CA 证书

将多个 PEM 证书拼接在一起即可（WebSocket / QUIC 均适用）：

```kotlin
val multiCaCert = """
-----BEGIN CERTIFICATE-----
...（Root CA 1）...
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
...（Root CA 2）...
-----END CERTIFICATE-----
""".trimIndent()

val options = ConnectOptions(
    caCertPem = multiCaCert,
)
```

### 场景五：不启用证书验证（默认行为）

无需任何额外配置，完全向后兼容：

```kotlin
val options = ConnectOptions()
room.connect("wss://livekit.mycompany.com:443", token, options)
```

---

## 4. 证书要求

| 要求 | 说明 |
|------|------|
| 格式 | PEM 编码（Base64 文本），包含 BEGIN/END 标记 |
| 层级 | 仅需传入 **Root CA** 证书；中间证书由服务端在 TLS 握手时下发 |
| SAN | 服务端叶子证书的 Subject Alternative Name (SAN) 必须包含连接域名 |
| 算法 | 支持 RSA、ECDSA 等标准算法 |

### DER 转 PEM 工具方法

```kotlin
fun derToPem(derBytes: ByteArray): String {
    val base64 = android.util.Base64.encodeToString(derBytes, android.util.Base64.NO_WRAP)
    return "-----BEGIN CERTIFICATE-----\n" +
        base64.chunked(64).joinToString("\n") +
        "\n-----END CERTIFICATE-----"
}
```

---

## 5. 参数组合速查表

### WebSocket

| URL | caCertPem | 效果 |
|-----|-----------|------|
| `wss://domain.com:443` | 有效 PEM | 域名连接 + 自签证书校验 |
| `wss://domain.com:443` | 不设置 | 域名连接 + 系统信任库（默认行为） |

> WebSocket 不支持 IP 直连。`serverHost` 对 WebSocket 无效。

### QUIC

| URL | serverHost | caCertPem | 效果 |
|-----|-----------|-----------|------|
| `wss://1.2.3.4:443` | `domain.com` | 有效 PEM | IP 直连 + 自签证书校验 |
| `wss://domain.com:443` | 不设置 | 有效 PEM | 域名连接 + 自签证书校验 |
| `wss://domain.com:443` | 不设置 | 不设置 | 域名连接 + 默认行为 |
| `wss://1.2.3.4:443` | `domain.com` | 不设置 | IP 直连，无证书校验（原有行为） |

---

## 6. 错误处理

| 场景 | 行为 |
|------|------|
| `caCertPem` 格式无效 | 记录错误日志，降级到系统默认信任库（不崩溃） |
| 证书链校验失败 | TLS 握手失败，连接被拒绝，触发 `RoomEvent.FailedToConnect` |
| 域名不匹配（SAN 不含域名） | TLS 握手失败，连接被拒绝 |

---

## 7. 注意事项

1. **证书由上层应用传递**：SDK 不内置任何自签名根证书。
2. **重连自动继承**：首次连接设置的 `caCertPem` 和 `serverHost` 在断线重连时自动复用。
3. **仅支持 PEM 格式**：DER 二进制需先转换为 PEM 文本。
4. **WebSocket 不支持 IP 直连**：WebSocket 通道必须使用域名连接，`serverHost` 参数对 WebSocket 无效。

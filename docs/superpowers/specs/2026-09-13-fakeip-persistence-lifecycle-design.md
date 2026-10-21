# FakeIP 持久化生命周期设计

## 1. 背景

当前 fakeip 映射由 `gost-x` 的内存 Store 管理。VPN 或 Gost 进程重启后，客户端可能继续使用上一次 DNS 响应中的 fake IP，但新进程已经丢失 `fake IP -> domain` 映射，导致 TUN 数据面无法恢复目标 domain。

持久化能力应由上层应用 `libgost` 管理，而不是由 `gost-x` 内部自动打开和写入文件。`libgost` 是 VPN 应用层，拥有 `StartGost`、`StopGost` 等完整生命周期；`gost-x` 应保持为可复用的代理库。

## 2. 目标

1. fakeip 节点配置只描述地址池，不再包含 `persistence=true` 参数：

   ```text
   fakeip://?inet4=198.18.0.0/15&inet6=fc00::/18
   ```

2. `gost-x` 提供 fakeip Store 的创建、查询、分配、状态加载和状态导出能力，但不自动执行文件 I/O。
3. `libgost` 在 VPN 应用场景中强制启用 fakeip 持久化：
   - `StartGost` 前加载 `fakeip-state.json`；
   - `StopGost` 停止服务后保存 `fakeip-state.json`。
4. 启动时状态文件不存在视为空状态，并在首次保存时创建。
5. 状态文件损坏、版本不支持或 fakeip 地址池不匹配时，启动失败，不能静默使用空 Store。
6. 保持现有 `resolver.FakeIPLookup`、`resolver.FakeIPContains` 数据面查询语义。

## 3. 非目标

- 本次不引入 SQLite、Redis 或其它数据库依赖。
- 本次不实现定时保存或 WAL；保存时机由 `libgost` 控制，第一阶段只实现启动加载和正常停止保存。
- 本次不改变 fakeip 地址分配算法，不做基于 domain hash 的无状态分配。
- 本次不处理未知 fake IP 的可靠 domain 推断；未知映射仍按现有 TUN fallback 处理。
- 本次不支持多个进程同时共享同一个状态文件；应用层按单进程使用约束处理。

## 4. 配置语义

### 4.1 fakeip URL

删除 `persistence` 查询参数的解析和语义。以下配置等价：

```text
fakeip://
fakeip://?inet4=198.18.0.0/15&inet6=fc00::/18
```

fakeip 节点只负责提供 IPv4/IPv6 地址范围。范围省略时继续使用现有默认值。

### 4.2 libgost 持久化文件

`libgost` 使用固定文件名：

```text
fakeip-state.json
```

初始实现使用应用进程的工作目录作为相对路径解析位置。文件打开、临时文件、原子替换、权限和错误日志均由 `libgost` 负责；`gost-x` 不接收文件路径。

## 5. 模块职责

### 5.1 gost-x

`gost-x` 负责：

- fake IP 的分配和复用；
- `domain + address family -> fake IP` 查询；
- `fake IP -> domain` 反向查询；
- 地址池和持久化状态的校验；
- 将状态编码到 `io.Writer`；
- 从 `io.Reader` 恢复状态。

`gost-x` 不负责：

- 判断是否启用持久化；
- 打开或创建 `fakeip-state.json`；
- 在 `Create` 内部自动保存；
- 监听应用停止信号；
- 启动定时保存任务。

### 5.2 libgost

`libgost` 负责：

- 解析 VPN 配置并判断是否存在 fakeip 节点；
- 创建 fakeip Store；
- 在服务启动前加载状态；
- 将已加载的 Store 注入或注册到 gost-x DNS handler；
- 在停止 DNS 和其它 Gost 服务后保存状态；
- 使用临时文件 + `Sync` + `Rename` 完成原子写入；
- 处理状态文件缺失、损坏和保存失败的日志及生命周期错误。

## 6. 跨模块 API

当前 `github.com/go-gost/x/resolver.FakeIPStore` 是数据面只读接口，`libgost` 可以实现该接口用于测试，但不能创建和持久化 gost-x 的真实 Store。因此需要在公开 `resolver` 包中增加可供应用层使用的分配/状态能力。

建议新增一个扩展接口，保留现有只读接口兼容性：

```go
type FakeIPAllocator interface {
    FakeIPStore
    Create(domain string, isIPv6 bool) (netip.Addr, error)
    Load(r io.Reader) error
    Store(w io.Writer) error
    Ranges() (inet4, inet6 netip.Prefix)
}
```

同时提供创建和预置 Store 所需的公开 API：

```go
func NewFakeIPAllocator(inet4Range, inet6Range netip.Prefix) FakeIPAllocator
func GetFakeIPStore() FakeIPStore
func SetFakeIPStore(store FakeIPStore)
```

`SetFakeIPStore` 继续接受只读 `FakeIPStore`，以保持 libgost 数据面和现有测试兼容。`libgost` 在启动服务前创建 `FakeIPAllocator`、调用 `Load`，然后通过 `SetFakeIPStore` 注册；DNS handler 初始化 fakeip 节点时通过 `GetFakeIPStore` 检测已注册对象：如果对象实现 `FakeIPAllocator` 且 `Ranges()` 与当前 fakeip 节点一致，则复用该对象；否则创建新的内存 allocator 并安装它。

`StopGost` 保存完成后调用 `SetFakeIPStore(nil)`，避免下一次启动误复用旧 Store。

具体实现必须满足以下约束：

1. `libgost` 能在启动 DNS handler 前创建并加载真实 allocator。
2. DNS handler 不会用空 Store 覆盖已加载的 allocator。
3. TUN 数据面仍只依赖 `Lookup` 和 `Contains`，不依赖持久化方法。
4. `gost-x` 的内部实现包不被 `libgost` 直接导入。
5. `GetFakeIPStore` / `SetFakeIPStore` 与现有全局数据面访问保持线程安全。
6. 地址池不匹配时不能复用预置 allocator。

## 7. 生命周期

### 7.1 启动

```text
StartGost
  ↓
解析 YAML，识别 fakeip 地址池
  ↓
创建 FakeIPAllocator
  ↓
打开 fakeip-state.json
  ├─ 不存在：使用空 Store，后续首次保存时创建
  └─ 存在：调用 allocator.Load，校验版本和地址池
  ↓
注册/注入 allocator
  ↓
启动 gost DNS、代理服务
```

加载失败时 `StartGost` 返回错误，不启动半初始化的 VPN 服务。

### 7.2 停止

```text
StopGost
  ↓
停止服务并阻止新的 DNS/fakeip 分配
  ↓
等待服务关闭
  ↓
调用 allocator.Store
  ↓
原子替换 fakeip-state.json
  ↓
清理全局 Store 和应用状态
```

持久化挂在 `StopGost`，不挂在 `StopTun`。因为 TUN 停止时 DNS 服务可能仍在运行并继续分配 fake IP；只有 Gost 服务停止后，Store 才不再产生新映射。

只在正常停止时保存意味着 `SIGKILL`、崩溃和断电可能丢失最近一次运行期间的新映射，这是本次设计明确接受的限制。

## 8. 状态格式

状态文件仍使用 JSON，至少包含：

```json
{
  "version": 1,
  "inet4": "198.18.0.0/15",
  "inet6": "fc00::/18",
  "current4": "198.18.0.1",
  "current6": "fc00::1",
  "entries": [
    {
      "domain": "example.com",
      "addr": "198.18.0.1"
    }
  ]
}
```

加载时必须校验：

- `version` 是否支持；
- 状态文件中的地址池是否与当前配置一致；
- entry 的地址是否属于对应地址池；
- 地址和同一地址族的 domain 是否重复；
- 当前分配游标是否合法。

## 9. 错误处理

- 状态文件不存在：按空状态启动。
- 状态文件格式错误：启动失败。
- 状态版本不支持：启动失败。
- 地址池变化：启动失败，并记录当前配置和文件配置。
- 保存失败：`StopGost` 返回错误并保留原状态文件，不删除已有有效文件。
- fakeip 未配置：不创建、加载或保存 fakeip 状态文件。

## 10. 测试要求

### gost-x

- fakeip URL 不再解析 `persistence` 参数；
- Store 可以导出并恢复 IPv4/IPv6 映射；
- 恢复后旧 fake IP 仍能反查 domain；
- 恢复后新分配不会覆盖旧映射；
- 缺失状态文件由上层处理为空状态；
- 错误版本、错误地址池、重复 entry 会被拒绝；
- 现有 `FakeIPLookup` 和 `FakeIPContains` 行为不变。

### libgost

- VPN 配置包含 fakeip 时，`StartGost` 会加载状态；
- 第一次启动没有状态文件时可以正常启动；
- `StopGost` 保存状态并允许下一次启动恢复；
- `StopTun` 不会提前保存或清空仍在运行的 DNS Store；
- 保存采用临时文件和原子替换；
- 启动加载失败时不会启动服务；
- 无 fakeip 配置时不会创建状态文件。

## 11. 迁移

现有包含 `persistence=true` 的 URL 需要改为移除该参数：

```text
fakeip://?inet4=198.18.0.0/15&inet6=fc00::/18
```

由于 `libgost` 对 VPN 模式统一强制持久化，用户不需要额外修改配置来开启持久化。非 `libgost` 的 gost-x 使用者只有在主动调用 `Load`/`Store` 时才会持久化。

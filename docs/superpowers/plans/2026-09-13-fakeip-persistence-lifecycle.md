# FakeIP Persistence Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 fakeip 状态持久化从 gost-x 内部自动文件写入重构为公开 Store API，并由 `libgost` 在 VPN 启停生命周期中统一加载和保存 `fakeip-state.json`。

**Architecture:** gost-x 提供公开的 `FakeIPAllocator`、地址池解析、状态 `Load/Store` 和线程安全的全局 Store 注册能力，但不执行文件 I/O；DNS handler 优先复用上层预加载且地址池匹配的 allocator。libgost 解析 VPN 配置，在启动服务前创建并加载 allocator，在停止 Gost 服务后使用临时文件 + `Sync` + `Rename` 保存状态。

**Tech Stack:** Go 1.26.3、`net/netip`、`encoding/json`、现有 gost-x service parser、libgost 的 `StartGost`/`StopGost` 生命周期、Go 标准库文件 I/O。

**Spec:** `docs/superpowers/specs/2026-09-13-fakeip-persistence-lifecycle-design.md`

## Global Constraints

- 不引入 SQLite、Redis 或其它新的持久化依赖。
- fakeip URL 移除 `persistence=true` 语义，只保留 `inet4` 和 `inet6`。
- gost-x 不打开、创建或写入 `fakeip-state.json`，也不在 `Create` 内部自动保存。
- libgost VPN 模式统一启用持久化；无 fakeip 节点时不创建状态文件。
- 缺失状态文件按空状态启动；损坏、版本不支持或地址池不匹配必须阻止启动。
- 不直接导入 gost-x 的 `internal` 包；跨模块能力只能通过公开 `resolver` API 使用。
- 不在 Git 中提交代码或设计文档；完成后只保留工作区修改并报告验证结果。

---

## Task 1: 重构 gost-x FakeIP Store 为公开的纯内存/状态 API

**Files:**
- Create: `../src/gost-x/resolver/fakeip_store.go` — 公开 allocator 接口、构造函数、fakeip URL 地址池解析和状态序列化实现。
- Modify: `../src/gost-x/resolver/fakeip.go` — 保留数据面只读 `FakeIPStore` 接口，增加 `GetFakeIPStore` 并复用新的 allocator 类型。
- Delete: `../src/gost-x/internal/util/resolver/fakeip.go` — 移除内部 fakeip Store、文件路径和自动保存实现；内部 resolver 包继续保留其它 Cache 文件。
- Delete: `../src/gost-x/internal/util/resolver/fakeip_test.go` — 将 Store 行为测试迁移到公开 resolver 测试，不保留已删除实现的测试文件。
- Create: `../src/gost-x/resolver/fakeip_store_test.go` — 覆盖 `Load/Store` 的状态恢复和校验。
- Modify: `../src/gost-x/resolver/fakeip_test.go` — 覆盖 `GetFakeIPStore`/`SetFakeIPStore` 注册和清理。

**Interfaces:**
- Produces:
  ```go
  type FakeIPAllocator interface {
      FakeIPStore
      Create(domain string, isIPv6 bool) (netip.Addr, error)
      Load(r io.Reader) error
      Store(w io.Writer) error
      Ranges() (inet4, inet6 netip.Prefix)
  }

  func NewFakeIPAllocator(inet4Range, inet6Range netip.Prefix) FakeIPAllocator
  func ParseFakeIPNode(addr string) (inet4, inet6 netip.Prefix, ok bool)
  func GetFakeIPStore() FakeIPStore
  func SetFakeIPStore(store FakeIPStore)
  ```
- `Load` 只消费 reader，不判断文件是否存在；`Store` 只写入 writer，不创建临时文件、不 rename。
- `Create` 只修改内存状态，不触发 `Load`/`Store` 或文件 I/O。
- 状态 JSON 保留 `version`、`inet4`、`inet6`、`current4`、`current6`、`entries` 字段。

- [ ] **Step 1: 写公开 allocator 的失败测试**

  在 `resolver/fakeip_store_test.go` 添加测试，先调用尚不存在的 `NewFakeIPAllocator`、`Store` 和 `Load`：

  ```go
  func TestFakeIPAllocatorRoundTrip(t *testing.T) {
      original := NewFakeIPAllocator(
          netip.MustParsePrefix("198.18.0.0/15"),
          netip.MustParsePrefix("fc00::/18"),
      )
      addr, err := original.Create("example.com", false)
      if err != nil {
          t.Fatal(err)
      }

      var buf bytes.Buffer
      if err := original.Store(&buf); err != nil {
          t.Fatal(err)
      }

      restored := NewFakeIPAllocator(
          netip.MustParsePrefix("198.18.0.0/15"),
          netip.MustParsePrefix("fc00::/18"),
      )
      if err := restored.Load(bytes.NewReader(buf.Bytes())); err != nil {
          t.Fatal(err)
      }
      if domain, ok := restored.Lookup(addr); !ok || domain != "example.com" {
          t.Fatalf("Lookup(%s) = (%q, %v)", addr, domain, ok)
      }
  }
  ```

- [ ] **Step 2: 运行失败测试确认失败原因**

  Run:

  ```bash
  cd /Users/kbliu/Workspace/src/gost-x
  go test ./resolver -run 'TestFakeIPAllocatorRoundTrip' -count=1
  ```

  Expected: 编译失败，因为公开 allocator、`Load` 和 `Store` 尚不存在。

- [ ] **Step 3: 实现公开 allocator 和纯 reader/writer 状态 API**

  将当前内部 Store 的内存映射和分配逻辑迁移到公开 resolver 实现，并将当前 DNS fakeip URL 解析逻辑迁移为公开的 `ParseFakeIPNode`，保证：

  - `Create` 在 IPv4/IPv6 地址族内保持 domain 稳定映射；
  - `Load` 校验版本、地址池、当前游标、重复 domain 和重复地址；
  - `Store` 在持有读锁或一致性快照下生成确定顺序的 JSON；
  - `Load`/`Store` 不接收路径，不调用 `os.ReadFile`、`os.Rename` 或 `os.CreateTemp`；
  - 缺失文件由调用方决定，不由 Store 处理；
  - 对 domain 统一执行去除末尾点和小写规范化，避免大小写导致重复分配；
  - `Ranges` 返回创建时的 IPv4/IPv6 prefix；
  - `GetFakeIPStore` 与 `SetFakeIPStore` 使用现有原子全局容器，保持 `FakeIPLookup`/`FakeIPContains` 线程安全。

- [ ] **Step 4: 运行公开 Store 测试确认通过**

  Run:

  ```bash
  cd /Users/kbliu/Workspace/src/gost-x
  go test ./resolver -run 'TestFakeIPAllocator|TestFakeIPGlobalAccessors' -count=1
  ```

  Expected: 新增 round-trip、校验和全局注册测试通过。

- [ ] **Step 5: 添加 Load/Store 错误和无文件 I/O 回归测试**

  覆盖：

  - 不支持的 `version`；
  - 配置 prefix 与状态 prefix 不一致；
  - 重复 address；
  - 同一地址族重复 domain；
  - IPv4/IPv6 地址越界；
  - `Store` 后恢复游标并继续分配新地址；
  - `Create` 不会生成或修改任何文件。

- [ ] **Step 6: 运行 resolver 全部测试**

  ```bash
  cd /Users/kbliu/Workspace/src/gost-x
  go test ./resolver ./internal/util/resolver -count=1
  ```

  Expected: `./resolver` 测试通过，`./internal/util/resolver` 中剩余的 Cache 测试通过；删除的 fakeip 测试不再被引用，且无旧自动持久化 API 残留。

---

## Task 2: 让 DNS handler 复用上层预加载 Store，并移除 URL 持久化参数

**Files:**
- Modify: `../src/gost-x/handler/dns/fakeip_node.go` — 删除 `persistence` 返回值，改用公开 resolver 的 fakeip URL 解析能力。
- Modify: `../src/gost-x/handler/dns/handler.go` — fakeip 节点初始化时优先复用已注册且 prefix 匹配的 allocator；否则创建并注册新的 allocator。
- Modify: `../src/gost-x/handler/dns/fakeip_node_test.go` — 更新 URL 解析断言，确认 `persistence=true` 不再具有配置语义。
- Modify: `../src/gost-x/handler/dns/handler_test.go` — 测试预加载 Store 被 DNS handler 复用，及 prefix 不匹配时创建新 Store。

**Interfaces:**
- Consumes: Task 1 的 `resolver.FakeIPAllocator`、`NewFakeIPAllocator`、`GetFakeIPStore`、`SetFakeIPStore`、`Ranges`。
- Produces: DNS handler 能够使用 libgost 在服务初始化前注册的 allocator，且 `fakeip://?...&persistence=true` 与不带该参数的 URL 行为一致。

- [ ] **Step 1: 写失败测试验证预加载 Store 会被复用**

  测试流程：

  1. 创建并注册一个 `resolver.FakeIPAllocator`；
  2. 为 domain 预分配一个 fake IP；
  3. 初始化带 `fakeip://` 节点的 DNS handler；
  4. 断言 exchanger 的 Store 能反查预分配的地址；
  5. 初始化第二个不同 prefix 的 fakeip handler，断言不会复用旧 Store。

- [ ] **Step 2: 运行 DNS 失败测试**

  ```bash
  cd /Users/kbliu/Workspace/src/gost-x
  go test ./handler/dns -run 'Test.*Preloaded.*FakeIP|TestParseFakeIPNode' -count=1
  ```

  Expected: 由于 DNS handler 当前总是创建内部 Store，预加载 Store 测试失败；旧的四返回值解析测试也应被更新为新的三返回值语义。

- [ ] **Step 3: 修改 fakeip URL 解析和 DNS 初始化**

  实现以下逻辑：

  ```go
  if inet4, inet6, ok := resolver.ParseFakeIPNode(addr); ok {
      if existing, ok := resolver.GetFakeIPStore().(resolver.FakeIPAllocator); ok {
          old4, old6 := existing.Ranges()
          if old4 == inet4 && old6 == inet6 {
              fakeStore = existing
          }
      }
      if fakeStore == nil {
          fakeStore = resolver.NewFakeIPAllocator(inet4, inet6)
      }
      resolver.SetFakeIPStore(fakeStore)
  }
  ```

  多个 fakeip 节点必须共享第一个匹配的 allocator；prefix 不一致时记录警告并继续使用首个 Store。删除 `fakeIPStateFile`、`persistence` 解析和 DNS handler 内部文件读写。

- [ ] **Step 4: 更新解析和 handler 测试**

  确认：

  - `fakeip://?inet4=...&inet6=...` 能解析；
  - `persistence=true` 不改变解析结果；
  - 预加载 Store 可以恢复旧 domain；
  - DNS 查询仍能为新 domain 分配 fake IP；
  - 未配置预加载 Store 时 DNS handler 仍能正常创建 Store。

- [ ] **Step 5: 运行 gost-x DNS 回归测试**

  ```bash
  cd /Users/kbliu/Workspace/src/gost-x
  go test ./handler/dns ./resolver -count=1
  ```

---

## Task 3: 在 libgost 增加 fakeip 状态文件生命周期协调器

**Files:**
- Create: `libgost/fakeip_persistence.go` — 发现 fakeip 配置、创建/加载 allocator、原子保存文件和清理全局 Store。
- Modify: `libgost/libgost.go:StartGost` — 在调用 `Start` 前准备并注册 fakeip allocator；启动失败时清理预置 Store。
- Modify: `libgost/libgost.go:StopGost` — 服务关闭并等待 goroutine 后保存 allocator，最后清理全局 Store。
- Modify: `libgost/libgost_test.go` — 增加启停恢复和错误场景测试。
- Create: `libgost/fakeip_persistence_test.go` — 独立测试配置发现、缺失文件、原子写入和错误状态。

**Interfaces:**
- Consumes: Task 1/2 的公开 resolver allocator 和 DNS Store 复用能力。
- Produces:
  ```go
  const fakeIPStateFile = "fakeip-state.json"

  type fakeIPPersistence struct {
      allocator resolver.FakeIPAllocator
      path      string
  }

  func prepareFakeIPPersistence(cfg *config.Config) (*fakeIPPersistence, error)
  func (p *fakeIPPersistence) save() error
  func (p *fakeIPPersistence) close() error
  ```
  `prepareFakeIPPersistence` 在没有 fakeip 节点时返回 nil；状态文件不存在时返回已创建但为空的 allocator。

- [ ] **Step 1: 写配置发现和生命周期失败测试**

  测试至少包含以下 fixture 和测试：

  ```go
  func configWithFakeIP() *config.Config {
      return &config.Config{
          Services: []*config.ServiceConfig{{
              Handler: &config.HandlerConfig{Type: "dns"},
              Forwarder: &config.ForwarderConfig{
                  Nodes: []*config.ForwardNodeConfig{{
                      Addr: "fakeip://?inet4=198.18.0.0/15&inet6=fc00::/18",
                  }},
              },
          }},
      }
  }

  func TestPrepareFakeIPPersistenceMissingFile(t *testing.T) {
      t.Chdir(t.TempDir())
      p, err := prepareFakeIPPersistence(configWithFakeIP())
      if err != nil {
          t.Fatal(err)
      }
      if p == nil || p.allocator == nil {
          t.Fatal("fakeip persistence should be prepared")
      }
      if _, err := os.Stat(fakeIPStateFile); !errors.Is(err, os.ErrNotExist) {
          t.Fatalf("state file should not be created during load: %v", err)
      }
  }
  ```

  另外添加：无 fakeip 配置返回 nil、坏 JSON 返回错误、prefix 不匹配返回错误。

- [ ] **Step 2: 运行失败测试确认 coordinator 尚不存在**

  ```bash
  cd /Users/kbliu/Workspace/project/GostX/libgost
  go test . -run 'TestPrepareFakeIP' -count=1
  ```

  Expected: 编译失败，因为 coordinator 和配置 fixture 尚不存在。

- [ ] **Step 3: 实现 fakeip 配置发现**

  遍历 `config.Config.Services`，只检查 `Handler.Type == "dns"` 的 service，并从其 `Forwarder.Nodes` 中识别 `fakeip://` 地址。使用 gost-x 公开的 fakeip URL parser 得到 IPv4/IPv6 prefix；多个 fakeip 节点要求 prefix 一致，否则返回配置错误。

  不修改用户 YAML，不添加 `persistence` 字段，不依赖 DNS handler 的私有类型。

- [ ] **Step 4: 实现启动加载**

  `prepareFakeIPPersistence` 执行：

  1. 创建 `resolver.NewFakeIPAllocator(inet4, inet6)`；
  2. `os.Open("fakeip-state.json")`；
  3. `os.IsNotExist` 时保留空 allocator；
  4. 其它打开错误返回错误；
  5. 调用 `allocator.Load(file)` 并关闭文件；
  6. 调用 `resolver.SetFakeIPStore(allocator)`；
  7. 返回包含 allocator 和路径的 coordinator。

- [ ] **Step 5: 实现原子保存和清理**

  `save` 执行：

  1. 在状态文件同目录创建临时文件；
  2. 设置 `0600` 权限；
  3. 调用 `allocator.Store(tmp)`；
  4. `Sync`、关闭临时文件；
  5. `Rename(tmp, fakeip-state.json)`；
  6. 失败时删除临时文件且保留旧状态文件。

  `close` 调用 `resolver.SetFakeIPStore(nil)`，并保证重复调用安全。

- [ ] **Step 6: 接入 `StartGost`**

  在过滤 tungo service、准备启动 Gost 服务之前调用 `prepareFakeIPPersistence`。如果加载失败，直接返回错误，不调用 `Start`。如果后续 `Start` 失败，清理已注册 Store。

- [ ] **Step 7: 接入 `StopGost`**

  在现有 service `Close` 和 `serveWg.Wait` 完成后调用 `save`，然后调用 `close`。保存错误通过 `StopGost` 返回，同时仍然完成 Store 清理和停止状态收尾。

  `StopTun` 不调用 coordinator 的保存逻辑。

- [ ] **Step 8: 运行 libgost coordinator 测试**

  ```bash
  cd /Users/kbliu/Workspace/project/GostX/libgost
  go test . -run 'TestPrepareFakeIP|TestFakeIPPersistence' -count=1
  ```

---

## Task 4: 添加跨模块启停恢复回归测试并更新依赖

**Files:**
- Modify: `libgost/libgost_test.go` — 在临时工作目录中启动包含 fakeip 的 VPN 配置，分配/保存后停止，再启动并验证旧 IP 恢复。
- Modify: `libgost/go.mod` — 更新 `github.com/go-gost/x` 到包含公开 allocator API 的版本或受控本地开发替换；正式依赖不得指向 `internal` 包。
- Modify: `libgost/go.sum` — 仅在依赖版本变化需要时更新。
- Modify: `docs/superpowers/specs/2026-09-13-fakeip-persistence-lifecycle-design.md` — 若实现中的公开 API 或路径语义与 spec 有经过批准的差异，同步记录。

**Interfaces:**
- Consumes: Task 3 的 `prepareFakeIPPersistence` 和 `StopGost` 保存行为。
- Produces: 可证明 `StopGost → StartGost` 之后旧 fake IP 仍能通过公开 `resolver.FakeIPLookup` 恢复 domain 的集成回归。

- [ ] **Step 1: 写跨启停失败测试**

  测试流程：

  1. `t.Chdir(t.TempDir())`；
  2. 启动含 fakeip DNS forwarder 的 VPN YAML；
  3. 通过已注册 allocator 创建 `example.com` 的 fake IP；
  4. 调用 `StopGost`；
  5. 断言 `fakeip-state.json` 存在；
  6. 再次启动同一 YAML；
  7. 通过 `resolver.FakeIPLookup(oldAddr)` 断言得到 `example.com`；
  8. `defer StopGost` 清理。

- [ ] **Step 2: 运行失败测试确认生命周期尚未完整接入**

  ```bash
  cd /Users/kbliu/Workspace/project/GostX/libgost
  go test . -run 'Test.*FakeIP.*Restart|Test.*FakeIP.*Persistence' -count=1
  ```

- [ ] **Step 3: 更新 x 依赖并完成跨模块编译**

  使用包含 Task 1/2 API 的 gost-x 版本；开发期间如需本地联调，使用明确的本地 `replace`，验证完成后恢复为可发布版本依赖。不得把 `../src/gost-x/internal/...` 加入 libgost import。

- [ ] **Step 4: 运行跨模块回归测试**

  ```bash
  cd /Users/kbliu/Workspace/project/GostX/libgost
  go test . -run 'Test.*FakeIP|TestStartStop|TestStopThenRestart' -count=1
  ```

---

## Task 5: 全量验证与文档检查

**Files:**
- Verify only: `../src/gost-x` and `libgost/`

- [ ] **Step 1: 验证 gost-x 构建、测试和静态检查**

  ```bash
  cd /Users/kbliu/Workspace/src/gost-x
  go test ./...
  go build ./...
  go vet ./...
  ```

- [ ] **Step 2: 验证 libgost 测试**

  ```bash
  cd /Users/kbliu/Workspace/project/GostX/libgost
  go test .
  ```

- [ ] **Step 3: 检查旧行为残留**

  ```bash
  cd /Users/kbliu/Workspace/project/GostX
  rg -n "persistence=true|NewPersistentFakeIPStore|fakeIPStateFile|saveLocked|internal/util/resolver" \
      libgost ../src/gost-x/handler ../src/gost-x/resolver ../src/gost-x/internal/util/resolver
  ```

  Expected: 不再存在 URL `persistence=true` 解析和 `Create` 内部文件保存；libgost 不直接导入 gost-x internal 包。

- [ ] **Step 4: 汇总已知环境失败**

  如果全量测试存在与本改动无关的既有失败，逐条记录命令、失败包和错误信息，不将部分通过表述为全量通过。

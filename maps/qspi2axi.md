# Qspi2Axi 功能与协议说明

## 1. 模块定位

`Qspi2Axi` 是一个 **QSPI target（从设备）到 AXI manager（主设备）** 的协议桥。外部 QSPI controller/host 提供低速 `SCK` 和 `CS_N`，模块接收一条自定义 4-4-4 QSPI 命令，将其转换成一个 32 位地址、128 位数据宽度的 AXI 访问。

预期连接方向为：

```text
外部 QSPI controller
        |
        | 4-bit QSPI target interface
        v
    Qspi2Axi
        |
        | AXI manager interface
        v
CoreAxi.axi_slave 或其他兼容 AXI subordinate
```

每条 QSPI 请求只转换成 **一个 AXI beat**。QSPI 链路不携带 AXI burst、LEN 或 ID；Qspi2Axi 固定生成单拍 INCR 事务。

源码和生成入口：

- `hdl/chisel/src/bus/QspiAxiProtocol.scala`
- `hdl/chisel/src/bus/Qspi2Axi.scala`
- Chisel 生成对象：`bus.EmitQspi2Axi`
- Bazel 目标：`//hdl/chisel/src/bus:qspi2axi_cc_library_emit_verilog`
- Verilator C++ 目标：`//hdl/chisel/src/bus:qspi2axi_cc_library_cc`
- 生成顶层：`Qspi2Axi`

## 2. 固定参数与 CoreAxi 适配

| 参数 | 值 | 说明 |
|---|---:|---|
| AXI 地址宽度 | 32 bit | 与当前 CoreAxi 地址接口一致 |
| AXI 数据宽度 | 128 bit | 16 个 byte lane；只可直接连接同样配置为 128 bit 的 CoreAxi |
| AXI strobe 宽度 | 16 bit | 每个 bit 对应一个 WDATA byte lane |
| AXI ID 宽度 | 来自 `Parameters` | 当前默认生成结果为 6 bit |
| QSPI 数据宽度 | 4 bit | DQ[3:0] 全阶段四线传输 |
| 最大 SIZE | 4 | 每拍名义传输宽度最大为 16 byte |
| AXI LEN | 固定 0 | 每个 QSPI 请求只生成一个 AXI beat |
| AXI BURST | 固定 INCR | 单拍事务中不发生后续地址推进 |
| AXI ID | 固定 0 | QSPI 协议不传输 AXI ID |
| AXI PROT | 固定 0 | 未从 QSPI 传输 |
| WLAST | 固定 1 | 单拍写事务 |

桥的 emitter 会将本地 `Parameters().lsuDataBits` 显式改为 128，因此生成的 Qspi2Axi 是固定 128-bit 数据宽度。CoreAxi 的原始 `Parameters()` 默认 `lsuDataBits=256`；只有同样以 `--lsuDataBits=128` 或等效参数生成的 CoreAxi 变体才能直接连接本桥。

`SIZE=0/1/2/3/4` 均允许。模块不会根据 `ADDR[3:0] + 2^SIZE` 再次拒绝名义上的跨 16 字节访问，而是原样保留地址、SIZE、WDATA 和 WSTRB。这是为了匹配 CoreAxi 的 `AxiSlave/SRAM` 语义：

- 地址高位选择 16 字节 SRAM line；
- WSTRB 决定该 line 中真正写入的 byte lane；
- 低地址位不用于旋转 WDATA、WSTRB 或 RDATA。

因此，调用方必须按 CoreAxi 的 lane 约定摆放数据和 strobe。桥本身不执行对齐、旋转或二次拆分。

## 3. 顶层端口

生成的 SystemVerilog 顶层端口以 `io_` 为前缀。

### 3.1 时钟和复位

| Chisel 端口 | 生成端口 | 方向 | 说明 |
|---|---|---|---|
| `io.aclk` | `io_aclk` | 输入 | AXI 高速时钟 |
| `io.aresetn` | `io_aresetn` | 输入 | 全局低有效复位；各域异步断言、同步释放 |
| `io.qspi.sck` | `io_qspi_sck` | 输入 | 外部 QSPI controller 提供的 SCK |

### 3.2 QSPI target 端口

| Chisel 端口 | 生成端口 | 方向 | 宽度 | 说明 |
|---|---|---|---:|---|
| `io.qspi.csN` | `io_qspi_csN` | 输入 | 1 | 低有效片选 |
| `io.qspi.dqI` | `io_qspi_dqI` | 输入 | 4 | 从 pad 输入的 DQ[3:0] |
| `io.qspi.dqO` | `io_qspi_dqO` | 输出 | 4 | 向 pad 输出的 DQ[3:0] |
| `io.qspi.dqOe` | `io_qspi_dqOe` | 输出 | 1 | DQ 输出使能，高有效 |

模块使用拆分的 `dqI/dqO/dqOe`，不直接在 Chisel 内创建 `inout`。集成时应在 pad 或顶层使用三态单元：

```text
DQ pad = dqOe ? dqO : Z
DQ input -> dqI
```

`CS_N=1` 或 QSPI 下降沿域仍处于复位时，`dqOe` 保证为 0。

### 3.3 AXI manager 端口

模块提供完整的 AW/W/B 和 AR/R ready-valid 通道，信号名由 `AxiMasterIO` 展开，包括：

- AW：`addr/prot/id/len/size/burst/lock/cache/qos/region/valid/ready`
- W：`data/strb/last/valid/ready`
- B：`id/resp/valid/ready`
- AR：与 AW 对应
- R：`data/id/resp/last/valid/ready`

当前默认生成结果的关键宽度为地址 32 bit、数据 128 bit、WSTRB 16 bit、ID 6 bit。

## 4. QSPI 电气与采样约定

该协议固定使用以下传输约定：

- SDR；
- Mode 0：SCK 空闲为低；
- target 在 SCK 上升沿采样 controller 输出；
- target 输出通过下降沿寄存器更新，供下一上升沿采样；
- DQ3 是每个 nibble 的最高位，DQ0 是最低位；
- 每个 byte 先发送高 nibble `[7:4]`，再发送低 nibble `[3:0]`；
- request 和 response 阶段均使用 4-bit 传输，不切换成单线 opcode/address 模式。

一次 byte 占两个 SCK 上升沿。除 Busy 等待外，协议字段长度固定。

## 5. 线协议

## 5.1 请求格式

### 读请求

| 字节偏移 | 字段 | 长度 | 线序 |
|---:|---|---:|---|
| 0 | Opcode | 1 byte | `0x01` |
| 1 | Control | 1 byte | `[2:0]=SIZE`，`[7:3]=0` |
| 2..5 | Address | 4 byte | 32 位地址，最高有效 byte 先发 |

总计 6 byte，即 12 个 nibble。

### 写请求

| 字节偏移 | 字段 | 长度 | 线序 |
|---:|---|---:|---|
| 0 | Opcode | 1 byte | `0x02` |
| 1 | Control | 1 byte | `[2:0]=SIZE`，`[7:3]=0` |
| 2..5 | Address | 4 byte | 最高有效 byte 先发 |
| 6..7 | WSTRB | 2 byte | `[15:8]` 先发，再发 `[7:0]` |
| 8..23 | WDATA | 16 byte | AXI byte lane 0 到 lane 15 |

总计 24 byte，即 48 个 nibble。

### 数据 lane 规则

WDATA 和 RDATA 均按 AXI byte lane 升序传输：

```text
第 1 个数据 byte = AXI DATA[7:0]
第 2 个数据 byte = AXI DATA[15:8]
...
第 16 个数据 byte = AXI DATA[127:120]
```

地址和 WSTRB 使用网络序/高 byte 优先；数据使用 AXI lane 升序。这两个顺序不能混淆。

## 5.2 总线周转

controller 发送完最后一个请求 nibble 后必须释放 DQ。协议保留一个完整 SCK 周期作为周转周期：

1. 最后一个请求 nibble 在上升沿被 target 采样；
2. 随后的下降沿，controller 关闭输出使能；
3. 下一个完整周期保持 DQ 无驱动；
4. target 在后续下降沿开始驱动 Busy/Ready 响应。

该周期避免 controller 和 target 同时驱动 DQ。

## 5.3 响应格式

响应依次由 Busy、Ready、Status 和可选读数据组成：

```text
BusyToken 重复 1 次或多次 -> ReadyToken -> Status -> 可选 RDATA
```

| 字段 | 值/长度 | 说明 |
|---|---|---|
| Busy | `0x00`，至少 1 byte，可重复 | AXI 请求尚未完成；当前 target 即使响应已就绪也会先发送一整 byte Busy |
| Ready | `0xFE`，1 byte | 下一 byte 为最终状态 |
| Status | 1 byte | AXI response 或本地错误 |
| RDATA | 16 byte，可选 | 仅成功读请求返回，lane 0 先发 |

写请求在 Status 后结束。读请求只有在 Status 为 OKAY 或 EXOKAY 时才继续发送 16 byte RDATA；错误读响应不发送数据字段。

controller 必须保持 `CS_N=0`，直到收到完整响应。target 完成响应后进入 Done 状态，等待 `CS_N` 拉高以开始下一帧。

## 6. 状态码

| 状态码 | 名称 | 来源/含义 |
|---:|---|---|
| `0x00` | OKAY | AXI `RESP=00` |
| `0x01` | EXOKAY | AXI `RESP=01` |
| `0x02` | SLVERR | AXI `RESP=10` |
| `0x03` | DECERR | AXI `RESP=11` |
| `0x80` | InvalidRequest | 非法 opcode 或 Control 保留位非 0 |
| `0x81` | InvalidSize | `SIZE>4` |
| `0x82` | CrossesBeat | 保留常量；当前实现不产生该状态 |
| `0x83` | Retry | 新帧开始时已有请求仍在执行 |
| `0x84` | FramingError | AXI 返回 ID/RLAST 与单拍约定不符 |
| `0x85` | Timeout | 由 Axi2Qspi controller 端产生；Qspi2Axi 本身无 AXI 超时计数器 |

`Retry` 在配对的 Axi2Qspi 中会映射成 AXI `SLVERR`，不会自动重试。

## 7. QSPI 域状态机

`Qspi2AxiTargetDomain` 的主要状态为：

```text
RxOpcode
  -> RxControl
  -> RxAddress
  -> [RxStrb -> RxWriteData]   // 写请求
  -> Turnaround
  -> TxBusy
  -> TxReady
  -> TxStatus
  -> [TxReadData]              // 成功读请求
  -> Done
```

主要行为：

- opcode、control、address、strobe 和 data 均在 SCK 上升沿组装；
- `CS_N` 作为帧状态的异步清除条件；
- 完整且合法的请求通过异步队列送到 `aclk` 域；
- 请求未完成时连续发送 Busy；
- response 到达后发送 Ready、Status 和可选数据。

模块只允许一个持久 outstanding QSPI 请求。异步队列深度为 2 是 CDC 实现参数，不代表协议允许两个并行 AXI 请求。

## 8. AXI 域状态机

`Qspi2AxiManagerDomain` 将一个 QSPI 请求展开为一个 AXI 事务。

### 8.1 写路径

```text
Idle -> WriteIssue -> WriteResp -> EmitResp -> Idle
```

- AW 和 W 独立握手；
- 在两个通道都完成握手后等待 B；
- `BID` 必须为 0，否则返回 `FramingError`；
- BRESP 的 2 bit 原样扩展为 QSPI 状态 `0x00..0x03`。

### 8.2 读路径

```text
Idle -> ReadAddr -> ReadData -> [DrainRead] -> EmitResp -> Idle
```

- AR 完成后等待 R；
- 正常返回要求 `RID=0` 且首个 R beat 同时 `RLAST=1`；
- 若首个 beat 未置 RLAST，则记录 framing error，并持续接收直到 RLAST，避免后续响应污染下一请求；
- 正常读数据完整保留 128 bit。

## 9. 时钟域跨越与复位

模块有三个相关时序域：

1. `aclk` 上升沿域：AXI manager 和 AXI 侧异步队列端点；
2. `qspi.sck` 上升沿域：请求接收、响应状态机、QSPI 侧异步队列端点；
3. `qspi.sck` 下降沿域：DQ 输出 nibble 和输出使能寄存器。

请求和响应分别通过安全异步队列跨域：

```text
SCK rise domain -- request AsyncQueue(depth=2, safe=true) --> ACLK domain
ACLK domain     -- response AsyncQueue(depth=2, safe=true) --> SCK rise domain
```

每个时钟域使用两级 `QspiResetSynchronizer`，实现异步断言、同步释放。

### 9.1 对外部 SCK 的复位要求

QSPI 上升沿和下降沿域的复位释放需要 SCK 边沿。全局复位撤销后，外部 controller 必须在 `CS_N=1`、DQ 不驱动的条件下提供至少两个完整 SCK 周期，再开始首个命令。

`aclk` 也必须持续运行，并在 `aresetn` 撤销后提供至少两个上升沿，AXI manager 和两个 CDC 队列的 ACLK 端才会退出复位。配套 `Axi2Qspi` 会自动生成默认两个启动 SCK 周期；其他外部 controller 也必须满足同样要求。

## 10. CS 中止与过期响应

`CS_N` 拉高会立即清除当前帧的接收/发送状态，但不会取消已经跨过 CDC 边界的 AXI 事务。

处理过程为：

1. 已发出的 AXI 请求继续完成；
2. QSPI 域保留 `inflight` 和 tag；
3. 如果原帧已被 CS 中止，则对应完成响应被正常取走但丢弃；
4. 在旧响应排空前，新帧会得到 `Retry`，不会错误接收旧数据。

这避免用异步 `CS_N` 直接复位 AXI 状态机或 CDC 队列。

## 11. CoreAxi 地址与 lane 语义

桥保持原始地址，不进行 16 字节对齐。例如一笔从 `0x204` 开始的 20 byte 写可能由 AXI 发起端拆成：

| beat | 地址 | SIZE | WSTRB | 有效 lane |
|---:|---:|---:|---:|---|
| 0 | `0x204` | 4 | `0xFFF0` | 4..15 |
| 1 | `0x214` | 4 | `0x00FF` | 0..7 |

每个 beat 经 QSPI 传输后，Qspi2Axi 仍以同一地址、SIZE、WDATA 和 WSTRB 发出单拍 AXI 请求。CoreAxi SRAM 使用地址高位选择 `0x200` 和 `0x210` 两条 line，再应用 strobe。

窄跨线写也采用同样规则。例如偏移 15、长度 2 的现有 cocotb 写流量会形成地址 `base+15` 和 `base+17` 两拍，WSTRB 分别为 `0x8000` 和 `0x0001`；桥不得拒绝或重排这两拍。

## 12. 集成约束

- 地址宽度必须为 32 bit，数据宽度必须为 128 bit；
- AXI ID 宽度至少为 1 bit；
- 外部 host 必须遵守 Mode 0、4-4-4、byte/nibble 顺序和一个完整周转周期；
- `CS_N` 必须覆盖整个请求和响应；
- `CS_N=0` 等待响应期间必须持续提供 SCK，包括周转、Busy、Ready、Status 和读数据阶段；不得在 Busy 等待期间停钟；
- target 一次只支持一个 outstanding 请求；
- 不支持取消已经发往 AXI 的事务；
- AXI 侧必须最终返回 B 或 R；Qspi2Axi 本身没有 AXI 响应超时；
- AXI ID 固定为 0，因此不能通过此桥传输 host 自定义 ID；
- `SIZE>4` 不支持；
- WDATA/WSTRB/RDATA 不按地址偏移旋转；
- pad 三态控制必须使用 `dqOe` 实现。

## 13. 构建与验证状态

已执行的静态验证包括：

- Chisel/Scala 编译和 CIRCT SystemVerilog 生成；
- Qspi2Axi Verilator C++ 目标构建；
- 多轮 Chisel 源码功能审查；
- 多轮生成 SystemVerilog 独立审查；
- 针对 CoreAxi 实际 AXI 流量和现有 cocotb 请求模式的周期级静态推演；
- CDC、CS 中止、AW/W 独立握手、R/B 背压、响应 framing、非对齐 lane 语义检查。

构建时使用 `--jobs=4`。截至本文档形成时，**未运行 Qspi2Axi 的动态 Chisel 或 cocotb 协议仿真**；本轮也未实现最终 `CoreQspi` 顶层。因此本文档描述的是已通过编译、生成和静态审查的当前 RTL 行为，不等同于动态仿真或硅验证结论。

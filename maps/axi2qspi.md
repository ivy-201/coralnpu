# Axi2Qspi 功能与协议说明

## 1. 模块定位

`Axi2Qspi` 是一个 **AXI subordinate（从设备）到 QSPI controller（主设备）** 的协议桥。模块从 AXI 接收读写请求，在 `aclk` 域保存 AXI burst 状态，并将每个 AXI beat 转换成一条 QSPI 微操作。QSPI SCK 由模块内部使用 AXI 高速时钟分频产生。

预期连接方向为：

```text
CoreAxi.axi_master 或其他 AXI manager
        |
        | AXI subordinate interface
        v
    Axi2Qspi
        |
        | generated 4-bit QSPI controller interface
        v
外部 QSPI target
```

AXI burst 不会作为一条 burst 直接编码到 QSPI。Axi2Qspi 在本地保存 AW/AR 的 ID、LEN、SIZE 和 BURST，并依次将每一拍转换为独立 QSPI 请求。读 burst 的每条 QSPI 微操作完成后，模块立即返回对应的 AXI R beat，并等待该 R beat 完成握手后再启动下一拍；写 burst 则在所有写 beat 处理完后返回一个聚合的 B response。

源码和生成入口：

- `hdl/chisel/src/bus/QspiAxiProtocol.scala`
- `hdl/chisel/src/bus/Axi2Qspi.scala`
- Chisel 生成对象：`bus.EmitAxi2Qspi`
- Bazel 目标：`//hdl/chisel/src/bus:axi2qspi_cc_library_emit_verilog`
- Verilator C++ 目标：`//hdl/chisel/src/bus:axi2qspi_cc_library_cc`
- 生成顶层：`Axi2Qspi`

## 2. 固定参数与默认配置

| 项目 | 默认值 | 说明 |
|---|---:|---|
| AXI 地址宽度 | 32 bit | 必须与共享 QSPI 协议一致 |
| AXI 数据宽度 | 128 bit | 16 个 byte lane；只可直接连接同样配置为 128 bit 的 CoreAxi |
| AXI WSTRB | 16 bit | 每 bit 对应一个 WDATA byte lane |
| AXI ID 宽度 | 来自 `Parameters` | 当前默认生成结果为 6 bit |
| QSPI 数据宽度 | 4 bit | 固定 4-4-4 |
| `sckHalfPeriodCycles` | 4 | 每个 SCK 半周期包含 4 个 `aclk` 周期 |
| `responseTimeoutCycles` | 65536 | 仅等待 Busy/Ready token 阶段计时 |
| `startupSckCycles` | 2 | 复位释放后、首个事务前输出的完整 SCK 周期数 |
| 最大 SIZE | 4 | 最大 16 byte/beat |

默认 SCK 频率为：

```text
fSCK = fACLK / (2 * sckHalfPeriodCycles)
     = fACLK / 8
```

桥的 emitter 会将本地 `Parameters().lsuDataBits` 显式改为 128，因此生成的 Axi2Qspi 是固定 128-bit 数据宽度。CoreAxi 的原始 `Parameters()` 默认 `lsuDataBits=256`；只有同样以 `--lsuDataBits=128` 或等效参数生成的 CoreAxi 变体才能直接连接本桥。

参数约束：

- `sckHalfPeriodCycles >= 1`；
- `responseTimeoutCycles >= 1`；
- `startupSckCycles >= 2`；
- AXI 地址宽度必须为 32 bit；
- AXI 数据宽度必须为 128 bit；
- AXI ID 宽度至少为 1 bit。

## 3. 顶层端口

生成的 SystemVerilog 顶层端口以 `io_` 为前缀。

### 3.1 时钟和复位

| Chisel 端口 | 生成端口 | 方向 | 说明 |
|---|---|---|---|
| `io.aclk` | `io_aclk` | 输入 | AXI 和内部状态机的高速时钟 |
| `io.aresetn` | `io_aresetn` | 输入 | 低有效复位；异步断言、同步释放 |

`aresetn` 拉低会异步置位复位同步器；`aresetn` 拉高后，`Axi2QspiCore` 的复位在两个 `aclk` 上升沿后同步释放。模块内部所有功能状态机均运行在 `aclk` 域。QSPI SCK 是输出信号，不作为内部状态机时钟；逻辑使用 `sckRise/sckFall` clock-enable 事件模拟 QSPI 边沿。

### 3.2 AXI subordinate 端口

AXI 方向相对于 `AxiMasterIO` 翻转：

- AW、W、AR：模块接收；
- B、R：模块返回。

接口包括完整的 AW/W/B 和 AR/R ready-valid 信号。当前默认生成结果的关键宽度为地址 32 bit、数据 128 bit、WSTRB 16 bit、ID 6 bit。

模块使用以下 AXI 字段：

| 通道 | 使用字段 |
|---|---|
| AW | ADDR、ID、LEN、SIZE、BURST |
| W | DATA、STRB、LAST |
| B | ID、RESP |
| AR | ADDR、ID、LEN、SIZE、BURST |
| R | DATA、ID、RESP、LAST |

AW/AR 的 PROT、LOCK、CACHE、QOS、REGION 由接口接收，但不编码进 QSPI 请求，也不影响当前转换逻辑。

### 3.3 QSPI controller 端口

| Chisel 端口 | 生成端口 | 方向 | 宽度 | 说明 |
|---|---|---|---:|---|
| `io.qspi.sck` | `io_qspi_sck` | 输出 | 1 | 内部分频产生的 SCK |
| `io.qspi.csN` | `io_qspi_csN` | 输出 | 1 | 低有效片选 |
| `io.qspi.dqI` | `io_qspi_dqI` | 输入 | 4 | 从 target/pad 采样的 DQ |
| `io.qspi.dqO` | `io_qspi_dqO` | 输出 | 4 | controller 输出的 DQ |
| `io.qspi.dqOe` | `io_qspi_dqOe` | 输出 | 1 | DQ 输出使能 |

集成时应在 pad 或上层使用 `dqOe` 实现三态。`CS_N=1` 时 `dqOe` 保证为 0。

## 4. 共享 QSPI 线协议

Axi2Qspi 和 Qspi2Axi 使用 `QspiAxiProtocol.scala` 定义的相同协议。

固定传输规则：

- SDR，Mode 0，SCK 空闲低；
- DQ3 是 nibble MSB；
- 每个 byte 高 nibble 先发；
- request 和 response 全部使用 4-bit 传输；
- 地址和 WSTRB 高 byte 先发；
- WDATA/RDATA 按 AXI byte lane 0 到 lane 15 的顺序传输；
- 一个 QSPI 请求对应一个 AXI beat。

## 4.1 读请求

```text
Byte 0      : Opcode = 0x01
Byte 1      : Control[2:0] = SIZE, Control[7:3] = 0
Byte 2..5   : 32-bit address, MSB byte first
```

总计 6 byte / 12 nibble。

## 4.2 写请求

```text
Byte 0      : Opcode = 0x02
Byte 1      : Control[2:0] = SIZE, Control[7:3] = 0
Byte 2..5   : 32-bit address, MSB byte first
Byte 6..7   : WSTRB[15:8], WSTRB[7:0]
Byte 8..23  : WDATA byte lane 0 ... lane 15
```

总计 24 byte / 48 nibble。

## 4.3 周转和响应

controller 在最后一个请求 nibble 被 target 采样后关闭 `dqOe`，并保留一个完整 SCK 周期作为总线周转。随后采样：

```text
1 个或多个 BusyToken(0x00)
-> ReadyToken(0xFE)
-> Status(1 byte)
-> 成功读请求的 RDATA(16 byte)
```

任何既不是 `0x00` 也不是 `0xFE` 的 token 都被视为 framing error。

## 5. AXI 输入缓冲和仲裁

Axi2Qspi 在 `aclk` 域使用三个同步队列：

| 队列 | 深度 | 内容 |
|---|---:|---|
| AR queue | 2 | 读地址描述符 |
| AW queue | 2 | 写地址描述符 |
| W queue | 3 | 写数据 beat |

读写事务在串行 QSPI 引擎处仍然是串行执行的，同一时刻只处理一条 AXI burst 中的一拍。队列只用于解耦 AXI ready-valid，不增加 QSPI 并行度。

当读写地址同时等待时，`preferWrite` 在成功选择事务后翻转，形成读写方向上的简单交替优先策略：

- 写被选中后，下次倾向读；
- 读被选中后，下次倾向写。

性能不是当前设计目标。AR、AW 和 W 各自保持 FIFO 顺序；某条 burst 一旦被选中，就会运行到该 burst 完成。读、写两个方向之间不存在统一的全局接收顺序，而是在 burst 边界由 `preferWrite` 仲裁。

## 6. AXI burst 分解

### 6.1 通用规则

Axi2Qspi 保存：

- 初始地址；
- AXI ID；
- LEN；
- SIZE；
- BURST；
- beat index；
- 写响应聚合状态。

每个 beat 按当前地址发出一条 QSPI 微操作。QSPI 请求中不包含 AXI ID、LEN、BURST 或 WLAST。

支持的 BURST 编码：

| AXI BURST | 支持状态 | 地址推进 |
|---|---|---|
| FIXED | 支持 | 地址不变 |
| INCR | 支持 | `addr + 2^SIZE` |
| WRAP | 支持项目语义 | 见下节 |
| `3`/保留编码 | 不支持 | 返回 SLVERR，不启动 QSPI |

`SIZE>4` 同样返回 SLVERR，不启动 QSPI。

### 6.2 项目特定 WRAP 语义

在当前 128-bit 数据宽度配置中，CoreAxi `AxiSlave` 使用 `p.axi2DataBits/8 = 16` 字节窗口，现有 cocotb 测试也按这一项目行为检查地址结果，而不是采用标准 AXI 由 `(LEN+1) * 2^SIZE` 决定的窗口。为保持直接替换顶层时的行为一致，Axi2Qspi 硬编码采用 16 字节窗口。

在接收 AW/AR 时捕获：

```text
wrapBase = floor(initialAddr / 2^SIZE) * 2^SIZE
```

每拍计算：

```text
incremented = currentAddr + 2^SIZE
nextAddr = (incremented >= wrapBase + 16) ? wrapBase : incremented
```

wrapBase 在整条 burst 中保持不变。

这是一项 **CoreAxi 项目兼容行为**，不是通用标准 AXI WRAP 实现。当前 CoreAxi 自身作为 AXI manager 只产生单拍 INCR，因此产品输出路径不依赖 WRAP；保留该行为主要用于复用现有 cocotb 测试。

### 6.3 原始地址和 lane 保留

对所有合法 `SIZE<=4`，模块不因名义访问范围跨越 16 字节边界而拒绝 beat。每拍保持：

- 当前逻辑地址；
- 原始 SIZE；
- 原始 128-bit WDATA；
- 原始 16-bit WSTRB。

模块不对数据执行对齐或旋转。

例如，从 line 偏移 15 写 2 byte 的现有 cocotb 流量会生成：

| beat | 地址 | SIZE | WSTRB |
|---:|---:|---:|---:|
| 0 | `base+15` | 1 | `0x8000` |
| 1 | `base+17` | 1 | `0x0001` |

Axi2Qspi 将两拍都原样发送。配对 Qspi2Axi/CoreAxi 根据两个地址选择相邻 line，再分别应用 lane 15 和 lane 0 的 strobe。

## 7. 事务状态机

## 7.1 顶层事务状态机

`Axi2QspiTransactionPhase` 包含：

```text
Idle
ReadStart -> ReadWait -> ReadResponse
WriteData -> WriteStart -> WriteWait -> WriteResponse
```

### 读路径

1. 从 AR queue 取得地址描述符；
2. 检查 SIZE 和 BURST；
3. 为当前 beat 启动 QSPI 读微操作；
4. 等待串行引擎完成；
5. 返回一个 AXI R beat，恢复原始 ID；
6. 根据 beat index 产生 RLAST；
7. 若还有 beat，计算下一地址并重复。

无效读 burst 仍返回 `ARLEN+1` 个 SLVERR R beat，最后一拍置 RLAST，以保持 AXI 返回拍数完整。

### 写路径

1. 从 AW queue 取得描述符；
2. 依次从 W queue 取得 WDATA/WSTRB/WLAST；
3. 每个合法 beat 启动一条 QSPI 写微操作；
4. 聚合每拍响应；
5. 在最后一拍完成后返回一个 B response，并恢复 AWID。

AW 和 W 可以独立进入队列，符合 AXI 两通道独立握手要求。

## 7.2 串行状态机

`Axi2QspiSerialPhase` 包含：

```text
Idle
-> TxRequest
-> TxLastHold
-> Turnaround
-> RxToken
-> RxStatus
-> [RxReadData]
-> Finish
-> Idle
```

行为说明：

- 在 `Idle` 接收到 `serialStart` 时拉低 CS、打开 DQ 输出，并立即放置 opcode 高 nibble；
- SCK 下降沿更新后续输出 nibble；
- SCK 上升沿表示 target 采样 controller 数据，或 controller 采样 target 数据；
- 最后一个请求 nibble 的上升沿后进入 `TxLastHold`；
- 下一下降沿关闭 `dqOe`；
- 一个完整周转周期后开始读取 token；
- `Finish` 在 SCK 回到低电平后拉高 CS 并产生 `serialDone`。

## 8. SCK 分频与启动序列

### 8.1 分频器

只在以下条件之一成立时运行 SCK：

- 启动时钟序列尚未完成；
- 串行状态机不在 Idle。

每经过 `sckHalfPeriodCycles` 个 `aclk` 周期，SCK 翻转一次。事务结束后 SCK 被强制回到 0，符合 Mode 0 空闲电平。

### 8.2 启动时钟

配对 Qspi2Axi 的 SCK 上升沿域和下降沿域使用同步释放复位，因此全局复位撤销后需要 SCK 边沿。Axi2Qspi 在任何 AXI 事务被选择前自动输出默认两个完整 SCK 周期：

- `CS_N` 保持高；
- `dqOe` 保持低；
- 不传输 opcode；
- 完成后才允许事务状态机从 AR/AW queue 取请求。

这保证首个实际 opcode 不会在 target 的 SCK 域仍处于复位时丢失。

## 9. AXI 响应映射

## 9.1 QSPI 状态到 AXI

只有 `0x00..0x03` 被视为可直接映射的 AXI response：

| QSPI 状态 | AXI RESP |
|---:|---:|
| `0x00` OKAY | `00` |
| `0x01` EXOKAY | `01` |
| `0x02` SLVERR | `10` |
| `0x03` DECERR | `11` |

除 `0x00..0x03` 之外的所有 8-bit status 值均映射为 AXI SLVERR；这包括当前定义的本地状态 `0x80..0x85`，也包括任何其他未定义值。

## 9.2 读响应

- 每个 QSPI beat 生成一个 AXI R beat；
- `RID` 使用原始 ARID；
- `RLAST` 在 `beatIndex == ARLEN` 时置位；
- 非成功 status 时，RDATA 的值不应被软件当作有效数据；
- RVALID 和完整 payload 在 RREADY 为低时保持稳定。

## 9.3 写响应聚合

一条 AXI write burst 只返回一个 B response。聚合规则为：

1. SLVERR/DECERR 一旦出现即保持为错误；
2. 新错误可以覆盖此前的 OKAY/EXOKAY；
3. 如果所有 beat 均成功，EXOKAY 可以保留；
4. 任意 WLAST 不一致都会触发断言；关闭断言时，提前 WLAST 会立即返回 SLVERR 且该拍不发出 QSPI 请求，AWLEN 所指最后一拍缺少 WLAST 时则仍执行该拍 QSPI 请求，完成后返回 SLVERR；
5. BID 使用原始 AWID；
6. BVALID 和 payload 在 BREADY 为低时保持稳定。

## 10. WLAST 输入约束

实现包含以下协议断言：

```text
WLAST == (beatIndex == AWLEN)
```

即 AXI manager 必须严格发送 `AWLEN+1` 个 W beat，并且仅在最后一拍置 WLAST。

这是当前实现的输入协议约束，而不是一个可以无歧义恢复的错误场景。原因是 AXI4 W 通道没有 WID，若上游缺少、提前或额外发送 W beat，桥无法可靠判断后续 W beat 属于旧 AW 还是新 AW。

CoreAxi DBus2Axi 和现有 cocotb AXI 生成器都满足该约束。

## 11. 超时与 framing error

超时计数器只在 `RxToken` 阶段运行，即仅计算等待 Busy/Ready 的 `aclk` 周期；请求序列化、Status 和 RDATA 接收不计入超时。

默认阈值为 65536 个 `aclk` 周期。到期时：

- 串行状态置 `StatusTimeout`；
- 结束当前 QSPI frame；
- 对 AXI 返回 SLVERR。

注意：超时单位是处于 `RxToken` 状态的 `aclk` 周期，不是 SCK 周期或已接收 token 的数量。如果 `timeoutExpired` 已成立，而 Ready byte 恰好在同一个 `aclk` 周期完成接收，超时赋值具有最终优先级，该事务仍按 Timeout 结束。系统集成时应让超时阈值明显大于预期 QSPI target 响应时间。

以下失败最终都会映射为 AXI SLVERR，但只有其中一部分属于 framing error：

- token 既不是 Busy 也不是 Ready：Axi2Qspi 本地 `FramingError`；
- target 返回 InvalidRequest、InvalidSize、Retry、Timeout 等本地错误：保留其独立 QSPI 状态含义，再统一映射为 SLVERR；
- 配对 Qspi2Axi 检测到 AXI ID/RLAST 不符合单拍微操作：Qspi2Axi 返回 `FramingError`。

## 12. CoreAxi 实际流量适配

### 12.1 CoreAxi AXI master

当前 CoreAxi 的外部 AXI master 路径包括：

- DBus2Axi：ID 0，读写，LEN 0，INCR，WLAST 1；
- IBus2Axi：ID 1，只读，LEN 0，INCR，SIZE 4，16-byte 对齐。

因此产品路径中，Axi2Qspi 主要处理单拍事务并保留 ID。burst 分解能力主要用于保持现有 cocotb AXI subordinate-side 测试兼容。

### 12.2 LSU 与 lane 数据

CoreAxi LSU 可能将不适合窄传输表示的非对齐访问提升为 SIZE 4 的 line 操作，同时把有效数据放到对应 WDATA lane，并产生对应 WSTRB。Axi2Qspi 必须原样传输这些字段，不能根据地址低位再次移位。

### 12.3 现有 cocotb 兼容语义

现有 AXI 测试可以产生：

- 多拍 FIXED/INCR/WRAP；
- 非对齐 SIZE 4 line-lane 访问；
- 窄 SIZE 的跨 line 分片写；
- lane 定位的 WDATA/WSTRB。

当前地址推进、固定 16-byte WRAP 和原始 lane 保留均以尽量只替换测试顶层、复用现有 cocotb 代码为目标。

## 13. 设计约束与不支持项

- 只支持 32-bit 地址和 128-bit AXI 数据；
- 支持 `SIZE=0..4`，`SIZE>4` 返回 SLVERR；
- 支持 FIXED、INCR 和项目特定 WRAP；保留 burst 编码返回 SLVERR；
- AXI exclusive/LOCK、CACHE、QOS、REGION、PROT 不传过 QSPI；
- 不并行执行多个 QSPI 请求；
- AXI burst 内的 beat 串行转换，因此吞吐量受 QSPI 帧长度和 Busy 时间限制；
- 不旋转或重排 AXI data/strobe；
- WLAST 必须与 AWLEN 严格一致；
- QSPI target 必须遵守 Busy/Ready/Status 协议和一个完整周转周期；
- QSPI target 必须在系统设定的超时前最终返回 Ready；
- 项目特定 WRAP 不是标准通用 AXI WRAP；
- 除 `SIZE<=4` 和 BURST 编码非保留值外，桥不会验证标准 AXI burst 合法性，包括 4-KiB 边界、标准 WRAP 长度/对齐、传输地址对齐，以及 WSTRB 与地址/SIZE 的一致性；
- pad 三态必须由上层使用 `dqOe` 实现。

## 14. 构建与验证状态

已执行的静态验证包括：

- Chisel/Scala 编译和 CIRCT SystemVerilog 生成；
- Axi2Qspi Verilator C++ 目标构建；
- 多轮 Chisel 源码功能审查；
- 多轮生成 SystemVerilog 独立审查；
- 针对 CoreAxi 实际 DBus/IBus 流量和现有 cocotb 流量的周期级静态推演；
- startup SCK、nibble/byte 顺序、总线周转、burst 分解、ID/LAST、R/B 背压、响应聚合和非对齐 lane 语义检查。

构建时使用 `--jobs=4`。截至本文档形成时，**未运行 Axi2Qspi 的动态 Chisel 或 cocotb 协议仿真**；本轮也未实现最终 `CoreQspi` 顶层。因此本文档描述的是已通过编译、生成和静态审查的当前 RTL 行为，不等同于动态仿真、形式验证或硅验证结论。

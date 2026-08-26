// Copyright 2026
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package coralnpu

import chisel3._

import bus._

/** CoreAxi with its subordinate and manager AXI ports converted to QSPI. */
class CoreQspi(p: Parameters, coreModuleName: String) extends RawModule {
  override val desiredName = coreModuleName + "Qspi"

  val io = IO(new Bundle {
    val aclk = Input(Clock())
    val aresetn = Input(AsyncReset())

    // External controller accesses CoreAxi through this QSPI target port.
    val qspi_target = new QspiTargetIO
    // CoreAxi accesses external devices through this QSPI controller port.
    val qspi_controller = new QspiControllerIO

    // Core status and interrupts.
    val halted = Output(Bool())
    val fault = Output(Bool())
    val wfi = Output(Bool())
    val irq = Input(Bool())
    val boot_addr = Input(UInt(p.fetchAddrBits.W))
    val timer_irq = Input(Bool())
    val software_irq = Input(Bool())

    val debug = Option.when(p.shouldExposeDebugPorts)(new DebugIO(p))
    val dm = new DebugModuleIO(p)
    val te = Input(Bool())
  })
  dontTouch(io)

  val coreAxi = Module(new CoreAxi(p, coreModuleName))
  val targetBridge = Module(new Qspi2Axi(p))
  val controllerBridge = Module(new Axi2Qspi(p))

  coreAxi.io.aclk := io.aclk
  coreAxi.io.aresetn := io.aresetn
  targetBridge.io.aclk := io.aclk
  targetBridge.io.aresetn := io.aresetn
  controllerBridge.io.aclk := io.aclk
  controllerBridge.io.aresetn := io.aresetn

  targetBridge.io.axi <> coreAxi.io.axi_slave
  controllerBridge.io.axi <> coreAxi.io.axi_master

  targetBridge.io.qspi.sck := io.qspi_target.sck
  targetBridge.io.qspi.csN := io.qspi_target.csN
  targetBridge.io.qspi.dqI := io.qspi_target.dqI
  io.qspi_target.dqO := targetBridge.io.qspi.dqO
  io.qspi_target.dqOe := targetBridge.io.qspi.dqOe

  io.qspi_controller.sck := controllerBridge.io.qspi.sck
  io.qspi_controller.csN := controllerBridge.io.qspi.csN
  controllerBridge.io.qspi.dqI := io.qspi_controller.dqI
  io.qspi_controller.dqO := controllerBridge.io.qspi.dqO
  io.qspi_controller.dqOe := controllerBridge.io.qspi.dqOe

  coreAxi.io.te := io.te
  coreAxi.io.irq := io.irq
  coreAxi.io.boot_addr := io.boot_addr
  coreAxi.io.timer_irq := io.timer_irq
  coreAxi.io.software_irq := io.software_irq
  io.halted := coreAxi.io.halted
  io.fault := coreAxi.io.fault
  io.wfi := coreAxi.io.wfi

  require(io.debug.isDefined == coreAxi.io.debug.isDefined,
    "Debug port presence mismatch between CoreQspi and CoreAxi")
  io.debug.zip(coreAxi.io.debug).foreach { case (ioDebug, coreDebug) =>
    ioDebug <> coreDebug
  }
  io.dm <> coreAxi.io.dm
}

/** Simulation-only wrapper that restores the CoreAxi AXI boundary around
  * CoreQspi, allowing the existing CoreAxi cocotb BFMs to drive the design.
  */
class CoreQspiTest(p: Parameters, coreModuleName: String) extends RawModule {
  override val desiredName = coreModuleName + "QspiTest"

  val io = IO(new Bundle {
    val aclk = Input(Clock())
    val aresetn = Input(AsyncReset())

    val axi_slave = Flipped(new AxiMasterIO(
      p.axi2AddrBits, p.axi2DataBits, p.axi2IdBits))
    val axi_master = new AxiMasterIO(
      p.axi2AddrBits, p.axi2DataBits, p.axi2IdBits)

    // Core status and interrupts.
    val halted = Output(Bool())
    val fault = Output(Bool())
    val wfi = Output(Bool())
    val irq = Input(Bool())
    val boot_addr = Input(UInt(p.fetchAddrBits.W))
    val timer_irq = Input(Bool())
    val software_irq = Input(Bool())

    val debug = Option.when(p.shouldExposeDebugPorts)(new DebugIO(p))
    val dm = new DebugModuleIO(p)
    val te = Input(Bool())
  })
  dontTouch(io)

  val coreQspi = Module(new CoreQspi(p, coreModuleName))
  val targetPeer = Module(new Axi2Qspi(p))
  val controllerPeer = Module(new Qspi2Axi(p))

  coreQspi.io.aclk := io.aclk
  coreQspi.io.aresetn := io.aresetn
  targetPeer.io.aclk := io.aclk
  targetPeer.io.aresetn := io.aresetn
  controllerPeer.io.aclk := io.aclk
  controllerPeer.io.aresetn := io.aresetn

  targetPeer.io.axi <> io.axi_slave
  io.axi_master <> controllerPeer.io.axi

  // AXI test manager -> Axi2Qspi -> CoreQspi target port.
  coreQspi.io.qspi_target.sck := targetPeer.io.qspi.sck
  coreQspi.io.qspi_target.csN := targetPeer.io.qspi.csN
  coreQspi.io.qspi_target.dqI := Mux(
    targetPeer.io.qspi.dqOe, targetPeer.io.qspi.dqO, 0.U)
  targetPeer.io.qspi.dqI := Mux(
    coreQspi.io.qspi_target.dqOe, coreQspi.io.qspi_target.dqO, 0.U)

  // CoreQspi controller port -> Qspi2Axi -> AXI test subordinate.
  controllerPeer.io.qspi.sck := coreQspi.io.qspi_controller.sck
  controllerPeer.io.qspi.csN := coreQspi.io.qspi_controller.csN
  controllerPeer.io.qspi.dqI := Mux(
    coreQspi.io.qspi_controller.dqOe,
    coreQspi.io.qspi_controller.dqO,
    0.U)
  coreQspi.io.qspi_controller.dqI := Mux(
    controllerPeer.io.qspi.dqOe, controllerPeer.io.qspi.dqO, 0.U)

  withClockAndReset(io.aclk, (!io.aresetn.asBool).asAsyncReset) {
    assert(!(
      targetPeer.io.qspi.dqOe && coreQspi.io.qspi_target.dqOe))
    assert(!(
      coreQspi.io.qspi_controller.dqOe && controllerPeer.io.qspi.dqOe))
  }

  coreQspi.io.te := io.te
  coreQspi.io.irq := io.irq
  coreQspi.io.boot_addr := io.boot_addr
  coreQspi.io.timer_irq := io.timer_irq
  coreQspi.io.software_irq := io.software_irq
  io.halted := coreQspi.io.halted
  io.fault := coreQspi.io.fault
  io.wfi := coreQspi.io.wfi

  require(io.debug.isDefined == coreQspi.io.debug.isDefined,
    "Debug port presence mismatch between CoreQspiTest and CoreQspi")
  io.debug.zip(coreQspi.io.debug).foreach { case (ioDebug, coreDebug) =>
    ioDebug <> coreDebug
  }
  io.dm <> coreQspi.io.dm
}

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

package bus

import chisel3._

/** Shared wire-format constants for the QSPI-to-AXI bridges.
  *
  * The protocol is fixed to a 32-bit address and a 128-bit AXI data beat. QSPI
  * symbols use all four DQ lanes, with DQ3 as the most-significant bit. Bytes
  * are transferred high nibble first. Multi-byte addresses and strobes are
  * transferred most-significant byte first, while AXI data is transferred in
  * ascending byte-lane order.
  */
object QspiAxiProtocol {
  val AddrBits = 32
  val DataBits = 128
  val StrbBits = DataBits / 8
  val MaxSize = 4

  val ReadOpcode = 0x01
  val WriteOpcode = 0x02

  val BusyToken = 0x00
  val ReadyToken = 0xfe

  val StatusOkay = 0x00
  val StatusExOkay = 0x01
  val StatusSlvErr = 0x02
  val StatusDecErr = 0x03

  val StatusInvalidRequest = 0x80
  val StatusInvalidSize = 0x81
  val StatusCrossesBeat = 0x82
  val StatusRetry = 0x83
  val StatusFramingError = 0x84
  val StatusTimeout = 0x85

  def isAxiStatus(status: UInt): Bool = status <= StatusDecErr.U
  def isSuccessfulStatus(status: UInt): Bool =
    status === StatusOkay.U || status === StatusExOkay.U
}

/** Split QSPI target pins. Pad-level tri-state cells are instantiated outside
  * the bridge, using dqO and dqOe.
  */
class QspiTargetIO extends Bundle {
  val sck = Input(Clock())
  val csN = Input(Bool())
  val dqI = Input(UInt(4.W))
  val dqO = Output(UInt(4.W))
  val dqOe = Output(Bool())
}

/** Split QSPI controller pins. The controller generates SCK and CS. */
class QspiControllerIO extends Bundle {
  val sck = Output(Clock())
  val csN = Output(Bool())
  val dqI = Input(UInt(4.W))
  val dqO = Output(UInt(4.W))
  val dqOe = Output(Bool())
}

/** One QSPI micro-operation. Each request maps to exactly one AXI beat. */
class QspiAxiRequest extends Bundle {
  val tag = Bool()
  val write = Bool()
  val addr = UInt(QspiAxiProtocol.AddrBits.W)
  val size = UInt(3.W)
  val strb = UInt(QspiAxiProtocol.StrbBits.W)
  val data = UInt(QspiAxiProtocol.DataBits.W)
}

/** Completion of one QSPI micro-operation. */
class QspiAxiResponse extends Bundle {
  val tag = Bool()
  val status = UInt(8.W)
  val data = UInt(QspiAxiProtocol.DataBits.W)
}

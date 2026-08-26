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
import chisel3.util._
import coralnpu.Parameters
import freechips.rocketchip.util.{AsyncQueue, AsyncQueueParams}

class QspiResetSynchronizer extends RawModule {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val asyncAssert = Input(Bool())
    val resetOut = Output(Bool())
  })

  val stages = withClockAndReset(io.clock, io.asyncAssert.asAsyncReset) {
    val value = RegInit("b11".U(2.W))
    value := Cat(value(0), false.B)
    value
  }
  io.resetOut := stages(1)
}

object QspiTargetPhase extends ChiselEnum {
  val RxOpcode, RxControl, RxAddress, RxStrb, RxWriteData, Turnaround,
      TxBusy, TxReady, TxStatus, TxReadData, Done = Value
}

/** QSPI-clocked target protocol engine.
  *
  * Frame-local state is asynchronously cleared by CS, while transaction state
  * that has crossed the CDC boundary is reset only by the global reset. This
  * lets an AXI transaction finish safely after its QSPI frame is aborted.
  */
class Qspi2AxiTargetDomain extends Module {
  val io = IO(new Bundle {
    val csN = Input(Bool())
    val dqI = Input(UInt(4.W))

    val request = Decoupled(new QspiAxiRequest)
    val response = Flipped(Decoupled(new QspiAxiResponse))

    // Registered on the falling edge by the top-level output stage.
    val txNibble = Output(UInt(4.W))
    val txEnable = Output(Bool())
  })

  val frameReset = (reset.asBool || io.csN).asAsyncReset

  // Persistent transaction bookkeeping. CS must not clear these registers.
  val inflight = RegInit(false.B)
  val nextTag = RegInit(false.B)
  val expectedTag = RegInit(false.B)

  val phase = withReset(frameReset) { RegInit(QspiTargetPhase.RxOpcode) }
  val highNibble = withReset(frameReset) { RegInit(0.U(4.W)) }
  val haveHighNibble = withReset(frameReset) { RegInit(false.B) }
  val write = withReset(frameReset) { RegInit(false.B) }
  val size = withReset(frameReset) { RegInit(0.U(3.W)) }
  val addr = withReset(frameReset) { RegInit(0.U(32.W)) }
  val strb = withReset(frameReset) { RegInit(0.U(16.W)) }
  val writeData = withReset(frameReset) { RegInit(0.U(128.W)) }
  val byteIndex = withReset(frameReset) { RegInit(0.U(5.W)) }
  val requestPending = withReset(frameReset) { RegInit(false.B) }
  val frameBusy = withReset(frameReset) { RegInit(false.B) }
  val frameOwnsRequest = withReset(frameReset) { RegInit(false.B) }
  val localError = withReset(frameReset) { RegInit(false.B) }
  val localStatus = withReset(frameReset) { RegInit(QspiAxiProtocol.StatusOkay.U(8.W)) }
  val responseValid = withReset(frameReset) { RegInit(false.B) }
  val responseStatus = withReset(frameReset) { RegInit(0.U(8.W)) }
  val responseData = withReset(frameReset) { RegInit(0.U(128.W)) }
  val txLowNibble = withReset(frameReset) { RegInit(false.B) }
  val txDataIndex = withReset(frameReset) { RegInit(0.U(5.W)) }

  val assembledByte = Cat(highNibble, io.dqI)
  val byteReceived = !haveHighNibble

  val requestBits = Wire(new QspiAxiRequest)
  requestBits.tag := nextTag
  requestBits.write := write
  requestBits.addr := addr
  requestBits.size := size
  requestBits.strb := strb
  requestBits.data := writeData

  io.request.valid := requestPending && !frameBusy && !localError && !inflight
  io.request.bits := requestBits

  when(io.request.fire) {
    requestPending := false.B
    inflight := true.B
    expectedTag := io.request.bits.tag
    nextTag := !nextTag
    frameOwnsRequest := true.B
  }

  // Always drain a completion for the one persistent outstanding request. If
  // CS aborted its frame, frameOwnsRequest is clear and the response is dropped.
  io.response.ready := inflight
  when(io.response.fire) {
    inflight := false.B
    when(frameOwnsRequest && io.response.bits.tag === expectedTag) {
      responseValid := true.B
      responseStatus := io.response.bits.status
      responseData := io.response.bits.data
    }
  }

  def finishRequest(finalAddr: UInt): Unit = {
    val invalidSize = size > QspiAxiProtocol.MaxSize.U

    addr := finalAddr
    when(frameBusy) {
      localError := true.B
      localStatus := QspiAxiProtocol.StatusRetry.U
    }.elsewhen(invalidSize) {
      localError := true.B
      localStatus := QspiAxiProtocol.StatusInvalidSize.U
    }.otherwise {
      requestPending := true.B
    }
    phase := QspiTargetPhase.Turnaround
    haveHighNibble := false.B
  }

  val receiving = phase === QspiTargetPhase.RxOpcode ||
    phase === QspiTargetPhase.RxControl ||
    phase === QspiTargetPhase.RxAddress ||
    phase === QspiTargetPhase.RxStrb ||
    phase === QspiTargetPhase.RxWriteData

  when(receiving) {
    when(!haveHighNibble) {
      highNibble := io.dqI
      haveHighNibble := true.B
      when(phase === QspiTargetPhase.RxOpcode) {
        frameBusy := inflight
      }
    }.otherwise {
      haveHighNibble := false.B
      switch(phase) {
        is(QspiTargetPhase.RxOpcode) {
          writeData := 0.U
          addr := 0.U
          strb := 0.U
          byteIndex := 0.U
          when(assembledByte === QspiAxiProtocol.ReadOpcode.U) {
            write := false.B
            phase := QspiTargetPhase.RxControl
          }.elsewhen(assembledByte === QspiAxiProtocol.WriteOpcode.U) {
            write := true.B
            phase := QspiTargetPhase.RxControl
          }.otherwise {
            localError := true.B
            localStatus := QspiAxiProtocol.StatusInvalidRequest.U
            phase := QspiTargetPhase.Turnaround
          }
        }
        is(QspiTargetPhase.RxControl) {
          size := assembledByte(2, 0)
          byteIndex := 0.U
          when(assembledByte(7, 3).orR) {
            localError := true.B
            localStatus := QspiAxiProtocol.StatusInvalidRequest.U
          }
          phase := QspiTargetPhase.RxAddress
        }
        is(QspiTargetPhase.RxAddress) {
          val nextAddr = Cat(addr(23, 0), assembledByte)
          addr := nextAddr
          when(byteIndex === 3.U) {
            byteIndex := 0.U
            when(write) {
              phase := QspiTargetPhase.RxStrb
            }.otherwise {
              finishRequest(nextAddr)
            }
          }.otherwise {
            byteIndex := byteIndex + 1.U
          }
        }
        is(QspiTargetPhase.RxStrb) {
          strb := Cat(strb(7, 0), assembledByte)
          when(byteIndex === 1.U) {
            byteIndex := 0.U
            phase := QspiTargetPhase.RxWriteData
          }.otherwise {
            byteIndex := byteIndex + 1.U
          }
        }
        is(QspiTargetPhase.RxWriteData) {
          writeData := writeData | (assembledByte << (byteIndex << 3.U))
          when(byteIndex === 15.U) {
            byteIndex := 0.U
            finishRequest(addr)
          }.otherwise {
            byteIndex := byteIndex + 1.U
          }
        }
      }
    }
  }

  // One complete SCK cycle is left undriven after the final request nibble.
  when(phase === QspiTargetPhase.Turnaround) {
    txLowNibble := false.B
    phase := QspiTargetPhase.TxBusy
  }

  when(phase === QspiTargetPhase.TxBusy) {
    txLowNibble := !txLowNibble
    when(txLowNibble && (localError || responseValid)) {
      txLowNibble := false.B
      phase := QspiTargetPhase.TxReady
    }
  }

  when(phase === QspiTargetPhase.TxReady) {
    txLowNibble := !txLowNibble
    when(txLowNibble) {
      txLowNibble := false.B
      phase := QspiTargetPhase.TxStatus
    }
  }

  when(phase === QspiTargetPhase.TxStatus) {
    txLowNibble := !txLowNibble
    when(txLowNibble) {
      txLowNibble := false.B
      when(!write && !localError && QspiAxiProtocol.isSuccessfulStatus(responseStatus)) {
        txDataIndex := 0.U
        phase := QspiTargetPhase.TxReadData
      }.otherwise {
        phase := QspiTargetPhase.Done
      }
    }
  }

  when(phase === QspiTargetPhase.TxReadData) {
    txLowNibble := !txLowNibble
    when(txLowNibble) {
      txLowNibble := false.B
      when(txDataIndex === 15.U) {
        phase := QspiTargetPhase.Done
      }.otherwise {
        txDataIndex := txDataIndex + 1.U
      }
    }
  }

  val selectedStatus = Mux(localError, localStatus, responseStatus)
  val selectedDataByte = (responseData >> (txDataIndex << 3.U))(7, 0)
  val selectedTxByte = MuxLookup(phase, QspiAxiProtocol.BusyToken.U(8.W))(
    Seq(
      QspiTargetPhase.TxBusy -> QspiAxiProtocol.BusyToken.U(8.W),
      QspiTargetPhase.TxReady -> QspiAxiProtocol.ReadyToken.U(8.W),
      QspiTargetPhase.TxStatus -> selectedStatus,
      QspiTargetPhase.TxReadData -> selectedDataByte
    )
  )

  io.txNibble := Mux(txLowNibble, selectedTxByte(3, 0), selectedTxByte(7, 4))
  io.txEnable := phase === QspiTargetPhase.TxBusy ||
    phase === QspiTargetPhase.TxReady ||
    phase === QspiTargetPhase.TxStatus ||
    phase === QspiTargetPhase.TxReadData

  assert(!io.txEnable || !receiving)
  assert(!io.request.fire || !inflight)
}

object QspiAxiManagerPhase extends ChiselEnum {
  val Idle, WriteIssue, WriteResp, ReadAddr, ReadData, DrainRead, EmitResp = Value
}

/** System-clocked single-outstanding AXI manager. */
class Qspi2AxiManagerDomain(p: Parameters) extends Module {
  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new QspiAxiRequest))
    val response = Decoupled(new QspiAxiResponse)
    val axi = new AxiMasterIO(p.axi2AddrBits, p.axi2DataBits, p.axi2IdBits)
  })

  val phase = RegInit(QspiAxiManagerPhase.Idle)
  val request = Reg(new QspiAxiRequest)
  val response = RegInit(0.U.asTypeOf(new QspiAxiResponse))
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val readProtocolError = RegInit(false.B)

  io.request.ready := phase === QspiAxiManagerPhase.Idle
  io.response.valid := phase === QspiAxiManagerPhase.EmitResp
  io.response.bits := response

  io.axi.defaults()
  io.axi.write.addr.bits.addr := request.addr
  io.axi.write.addr.bits.id := 0.U
  io.axi.write.addr.bits.len := 0.U
  io.axi.write.addr.bits.size := request.size
  io.axi.write.addr.bits.burst := AxiBurstType.INCR.asUInt
  io.axi.write.addr.bits.prot := 0.U

  io.axi.write.data.bits.data := request.data
  io.axi.write.data.bits.strb := request.strb
  io.axi.write.data.bits.last := true.B

  io.axi.read.addr.bits.addr := request.addr
  io.axi.read.addr.bits.id := 0.U
  io.axi.read.addr.bits.len := 0.U
  io.axi.read.addr.bits.size := request.size
  io.axi.read.addr.bits.burst := AxiBurstType.INCR.asUInt
  io.axi.read.addr.bits.prot := 0.U

  when(io.request.fire) {
    request := io.request.bits
    response.tag := io.request.bits.tag
    response.data := 0.U
    response.status := QspiAxiProtocol.StatusOkay.U
    awDone := false.B
    wDone := false.B
    readProtocolError := false.B
    phase := Mux(io.request.bits.write,
      QspiAxiManagerPhase.WriteIssue, QspiAxiManagerPhase.ReadAddr)
  }

  when(phase === QspiAxiManagerPhase.WriteIssue) {
    io.axi.write.addr.valid := !awDone
    io.axi.write.data.valid := !wDone

    when(io.axi.write.addr.fire) { awDone := true.B }
    when(io.axi.write.data.fire) { wDone := true.B }

    when((awDone || io.axi.write.addr.fire) &&
         (wDone || io.axi.write.data.fire)) {
      phase := QspiAxiManagerPhase.WriteResp
    }
  }

  when(phase === QspiAxiManagerPhase.WriteResp) {
    io.axi.write.resp.ready := true.B
    when(io.axi.write.resp.fire) {
      response.status := Mux(
        io.axi.write.resp.bits.id === 0.U,
        io.axi.write.resp.bits.resp,
        QspiAxiProtocol.StatusFramingError.U)
      phase := QspiAxiManagerPhase.EmitResp
    }
  }

  when(phase === QspiAxiManagerPhase.ReadAddr) {
    io.axi.read.addr.valid := true.B
    when(io.axi.read.addr.fire) {
      phase := QspiAxiManagerPhase.ReadData
    }
  }

  when(phase === QspiAxiManagerPhase.ReadData) {
    io.axi.read.data.ready := true.B
    when(io.axi.read.data.fire) {
      response.data := io.axi.read.data.bits.data
      when(io.axi.read.data.bits.id =/= 0.U || !io.axi.read.data.bits.last) {
        response.status := QspiAxiProtocol.StatusFramingError.U
        readProtocolError := true.B
      }.otherwise {
        response.status := io.axi.read.data.bits.resp
      }
      phase := Mux(io.axi.read.data.bits.last,
        QspiAxiManagerPhase.EmitResp, QspiAxiManagerPhase.DrainRead)
    }
  }

  when(phase === QspiAxiManagerPhase.DrainRead) {
    io.axi.read.data.ready := true.B
    when(io.axi.read.data.fire && io.axi.read.data.bits.last) {
      response.status := QspiAxiProtocol.StatusFramingError.U
      phase := QspiAxiManagerPhase.EmitResp
    }
  }

  when(io.response.fire) {
    phase := QspiAxiManagerPhase.Idle
  }

  assert(!(io.axi.write.addr.valid && phase =/= QspiAxiManagerPhase.WriteIssue))
  assert(!(io.axi.write.data.valid && phase =/= QspiAxiManagerPhase.WriteIssue))
  assert(!(io.axi.read.addr.valid && phase =/= QspiAxiManagerPhase.ReadAddr))
}

/** Converts a 4-4-4 QSPI target transaction directly into one AXI beat. */
class Qspi2Axi(p: Parameters) extends RawModule {
  require(p.axi2AddrBits == QspiAxiProtocol.AddrBits)
  require(p.axi2DataBits == QspiAxiProtocol.DataBits)
  require(p.axi2IdBits >= 1)

  val io = IO(new Bundle {
    val aclk = Input(Clock())
    val aresetn = Input(AsyncReset())
    val qspi = new QspiTargetIO
    val axi = new AxiMasterIO(p.axi2AddrBits, p.axi2DataBits, p.axi2IdBits)
  })

  val invertedSck = (!io.qspi.sck.asBool).asClock
  val asyncReset = !io.aresetn.asBool

  val aresetSync = Module(new QspiResetSynchronizer)
  aresetSync.io.clock := io.aclk
  aresetSync.io.asyncAssert := asyncReset

  val qspiRiseResetSync = Module(new QspiResetSynchronizer)
  qspiRiseResetSync.io.clock := io.qspi.sck
  qspiRiseResetSync.io.asyncAssert := asyncReset

  val qspiFallResetSync = Module(new QspiResetSynchronizer)
  qspiFallResetSync.io.clock := invertedSck
  qspiFallResetSync.io.asyncAssert := asyncReset

  val requestCdc = Module(new AsyncQueue(
    new QspiAxiRequest, AsyncQueueParams(depth = 2, safe = true)))
  requestCdc.io.enq_clock := io.qspi.sck
  requestCdc.io.enq_reset := qspiRiseResetSync.io.resetOut
  requestCdc.io.deq_clock := io.aclk
  requestCdc.io.deq_reset := aresetSync.io.resetOut

  val responseCdc = Module(new AsyncQueue(
    new QspiAxiResponse, AsyncQueueParams(depth = 2, safe = true)))
  responseCdc.io.enq_clock := io.aclk
  responseCdc.io.enq_reset := aresetSync.io.resetOut
  responseCdc.io.deq_clock := io.qspi.sck
  responseCdc.io.deq_reset := qspiRiseResetSync.io.resetOut

  val target = withClockAndReset(io.qspi.sck, qspiRiseResetSync.io.resetOut.asAsyncReset) {
    Module(new Qspi2AxiTargetDomain)
  }
  target.io.csN := io.qspi.csN
  target.io.dqI := io.qspi.dqI
  target.io.request <> requestCdc.io.enq
  target.io.response <> responseCdc.io.deq

  val manager = withClockAndReset(io.aclk, aresetSync.io.resetOut.asAsyncReset) {
    Module(new Qspi2AxiManagerDomain(p))
  }
  manager.io.request <> requestCdc.io.deq
  manager.io.response <> responseCdc.io.enq
  io.axi <> manager.io.axi

  val txOutput = withClockAndReset(invertedSck, qspiFallResetSync.io.resetOut.asAsyncReset) {
    val nibble = RegInit(0.U(4.W))
    val enable = RegInit(false.B)
    nibble := target.io.txNibble
    enable := target.io.txEnable
    (nibble, enable)
  }

  io.qspi.dqO := txOutput._1
  io.qspi.dqOe := txOutput._2 && !io.qspi.csN && !qspiFallResetSync.io.resetOut
}

import _root_.circt.stage.{ChiselStage, FirtoolOption}
import chisel3.stage.ChiselGeneratorAnnotation
import scala.annotation.nowarn

@nowarn
object EmitQspi2Axi extends App {
  val p = Parameters()
  p.lsuDataBits = QspiAxiProtocol.DataBits
  (new ChiselStage).execute(
    Array("--target", "systemverilog") ++ args,
    Seq(ChiselGeneratorAnnotation(() => new Qspi2Axi(p))) ++
      Seq(FirtoolOption("-enable-layers=Verification"))
  )
}

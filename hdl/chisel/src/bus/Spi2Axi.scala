//个人添加，目的是实现spi协议转axi
package bus

import chisel3._
import coralnpu.Parameters

/** SPI -> TL-UL -> AXI 顶层：把 SPI 从机口直接接到 AXI 主机口 */
class Spi2Axi(p: Parameters) extends Module {
  val io = IO(new Bundle {
    val spi = new Bundle {
      val clk  = Input(Clock())
      val csb  = Input(Bool())
      val mosi = Input(Bool())
      val miso = Output(Bool())
    }
    val axi = new AxiMasterIO(p.axi2AddrBits, p.axi2DataBits, p.axi2IdBits)
  })

  val spi2tlul = Module(new Spi2TLUL(p))
  val tlul2axi = Module(new TLUL2Axi(p, p, () => new OpenTitanTileLink_A_User, () => new OpenTitanTileLink_D_User))

  // SPI 引脚（方向相反，逐个直连最稳妥）
  spi2tlul.io.spi.clk  := io.spi.clk
  spi2tlul.io.spi.csb  := io.spi.csb
  spi2tlul.io.spi.mosi := io.spi.mosi
  io.spi.miso          := spi2tlul.io.spi.miso

  // TL-UL host -> device：Spi2TLUL 是 host(a 出/d 入)，TLUL2Axi 是 device(a 入/d 出)
  tlul2axi.io.tl_a <> spi2tlul.io.tl.a
  tlul2axi.io.tl_d <> spi2tlul.io.tl.d

  // AXI 主机口，与 CoreAxi 里 `io.axi_master.write <> ebus2axi.io.axi.write` 同款写法
  io.axi.write <> tlul2axi.io.axi.write
  io.axi.read  <> tlul2axi.io.axi.read
}

import _root_.circt.stage.{ChiselStage, FirtoolOption}
import chisel3.stage.ChiselGeneratorAnnotation
import scala.annotation.nowarn

@nowarn
object EmitSpi2Axi extends App {
  val p = Parameters()
  p.lsuDataBits = 128 // Spi2TLULV2 硬性断言：assert(p.lsuDataBits == 128)
  (new ChiselStage).execute(
    Array("--target", "systemverilog") ++ args,
    Seq(ChiselGeneratorAnnotation(() => new Spi2Axi(p))) ++ Seq(FirtoolOption("-enable-layers=Verification"))
  )
}
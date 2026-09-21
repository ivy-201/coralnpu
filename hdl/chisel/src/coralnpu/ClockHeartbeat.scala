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
import chisel3.util.Cat

/** Bring-up clock heartbeat monitor.
  *
  * Three free-running counters, each in its own clock domain. As long as the
  * corresponding clock toggles, its counter increments and the tapped high
  * bit blinks an LED at ~3 Hz at 100 MHz. There is deliberately no CDC: each
  * LED is a slow, human-observed signal local to its own domain. All counters
  * share the raw async reset, so the gated-core-clock channel is not held in
  * reset by the core reset / CSR state it is meant to diagnose.
  *
  * LED mapping:
  *   [0] clk_fast - raw external/PLL clock (io.aclk)
  *   [1] clk_sys  - system clock after RstSync
  *   [2] clk_core - core clock after the core ClockGate
  */
class ClockHeartbeat(
    counterWidth: Int = 25,
    fastTap: Int = 24,
    sysTap: Int = 24,
    coreTap: Int = 24
) extends RawModule {
  require(counterWidth > 0)
  require(Seq(fastTap, sysTap, coreTap).forall(t => t >= 0 && t < counterWidth))

  val io = IO(new Bundle {
    val clk_fast = Input(Clock())
    val clk_sys  = Input(Clock())
    val clk_core = Input(Clock())
    val aresetn  = Input(AsyncReset())
    val led      = Output(UInt(3.W))
  })

  private def heartbeat(clk: Clock, tap: Int): Bool =
    withClockAndReset(clk, (!io.aresetn.asBool).asAsyncReset) {
      val cnt = RegInit(0.U(counterWidth.W))
      cnt := cnt + 1.U
      cnt(tap)
    }

  val ledFast = heartbeat(io.clk_fast, fastTap)
  val ledSys  = heartbeat(io.clk_sys, sysTap)
  val ledCore = heartbeat(io.clk_core, coreTap)

  io.led := Cat(ledCore, ledSys, ledFast)
}

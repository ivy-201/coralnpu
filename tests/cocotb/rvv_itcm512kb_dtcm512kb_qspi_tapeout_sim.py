# Copyright 2026
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Tapeout loopback QSPI tests.

Fork of `rvv_itcm512kb_dtcm512kb_sim.py` for the tapeout `CoreQspiTest` variant
(`RvvHtbt_ITCM512KB_DTCM512KBQspiTest`):

  * io.dm is closed off at the `CoreQspi` boundary and left tied-off inside the
    `CoreQspiTest` loopback wrapper, so the BFMs still drive the AXI ports
    around the design (AXI -> QSPI -> AXI). No test touches io.dm.
  * The DUT additionally emits the bring-up clock heartbeat
    (`--enableClockHeartbeat=True`); `core_mini_heartbeat_qspi_tapeout` asserts
    on `io_heartbeat_led` that all three clocks (aclk, post-RstSync sys, gated
    core) are alive. The remaining three cases are the exact loopback tests
    from the base suite, kept unchanged to prove the io.dm tie-off introduces
    no regression over the AXI path.
"""

import cocotb
import numpy as np
import tqdm

from cocotb.triggers import Timer
from coralnpu_test_utils.core_mini_axi_interface import CoreMiniAxiInterface
from coralnpu_test_utils.sim_test_fixture import Fixture
from bazel_tools.tools.python.runfiles import runfiles


@cocotb.test()
async def core_mini_heartbeat_qspi_tapeout(dut):
    """Bring-up clock heartbeat: all three LED channels must advance.

    ClockHeartbeat runs a 25-bit free-running counter in each of three clock
    domains (fast=aclk, sys=post-RstSync, core=post-ClockGate), all sharing the
    raw async reset, and exports bit 24 of each as one LED. After reset all
    counters are 0; once a clock runs for 2**24 cycles its bit-24 LED asserts.
    """
    core_mini_axi = CoreMiniAxiInterface(dut, csr_base_addr=0x200000)
    await core_mini_axi.init()
    # Clock must run before reset so the QSPI bridges' reset synchronizers see
    # the deassertion synchronously (see base test note).
    cocotb.start_soon(core_mini_axi.clock.start())
    await core_mini_axi.reset()

    # Force the core ClockGate open so the core clock is guaranteed to run
    # regardless of CSR/wfi state; this makes the clk_core heartbeat channel
    # deterministic alongside clk_fast and clk_sys.
    dut.io_te.value = 1
    await Timer(3, units="ns")

    # Counters are cleared by the async reset, so all three bits must be 0 here.
    led0 = int(dut.io_heartbeat_led.value)
    assert led0 == 0, f"htbt not cleared by reset: {led0:#06b}"

    # Each counter taps bit 24 (asserts after 2**24 cycles of its own clock).
    # All three clocks here descend from aclk and start together once reset
    # releases, so waiting past 2**24 aclk cycles makes all three assert.
    window_cycles = (1 << 24) + (1 << 16)
    await Timer(int(window_cycles * core_mini_axi.clock_ns), units="ns")

    led = int(dut.io_heartbeat_led.value)
    assert led & 0x1, f"clk_fast (led[0]) stuck low: {led:#06b}"
    assert led & 0x2, f"clk_sys  (led[1]) stuck low: {led:#06b}"
    assert led & 0x4, f"clk_core (led[2]) stuck low: {led:#06b}"


@cocotb.test()
async def core_mini_axi_run_wfi_in_all_slots_itcm512kb_dtcm512kb_qspi(dut):
    """Tests the WFI instruction in each of the 4 issue slots with custom memory."""
    fixture = await Fixture.Create(dut, csr_base_addr=0x200000)
    core_mini_axi = fixture.core_mini_axi
    r = runfiles.Create()

    for slot in range(0, 4):
      with open(r.Rlocation(f"coralnpu_hw/tests/cocotb/wfi_slot_{slot}_itcm512kb_dtcm512kb.elf"), "rb") as f:
        await core_mini_axi.reset()
        entry_point = await core_mini_axi.load_elf(f)
        await core_mini_axi.execute_from(entry_point)

        await core_mini_axi.wait_for_wfi()
        await core_mini_axi.raise_irq()
        await core_mini_axi.wait_for_halted()


@cocotb.test()
async def core_mini_rvv_matmul_qspi_tapeout(dut):
    """Testbench to test matmul with rvv intrinsics using custom memory.

    This test performs matmul in M1 4x64 M2 64x4 matrices.
    Compares results with native numpy matmul.
    """

    LHS_ROWS = 4
    RHS_COLS = 4
    INNER = 64

    fixture = await Fixture.Create(dut, csr_base_addr=0x200000)
    r = runfiles.Create()
    elf_files = ['rvv_matmul_itcm512kb_dtcm512kb.elf', 'rvv_matmul_assembly_itcm512kb_dtcm512kb.elf']
    for elf_file in elf_files:

        await fixture.load_elf_and_lookup_symbols(
            r.Rlocation('coralnpu_hw/tests/cocotb/rvv/ml_ops/' + elf_file),
            ['lhs_input', 'rhs_input', 'result_output', 'lhs_rows', 'rhs_cols', 'inner'])
        await fixture.write_word('lhs_rows', LHS_ROWS)
        await fixture.write_word('rhs_cols', RHS_COLS)
        await fixture.write_word('inner', INNER)
        np_type = np.int8
        min_value = np.iinfo(np_type).min
        max_value = np.iinfo(np_type).max + 1  # One above.
        lhs_data = np.random.randint(min_value,
                                     max_value, [LHS_ROWS, INNER],
                                     dtype=np_type)
        rhs_data = np.random.randint(min_value,
                                     max_value, [INNER, RHS_COLS],
                                     dtype=np_type)
        result_data = np.matmul(lhs_data.astype(np.int32),
                                rhs_data.astype(np.int32))

        await fixture.write('lhs_input', lhs_data.flatten())
        await fixture.write('rhs_input', rhs_data.transpose().flatten())
        await fixture.run_to_halt(timeout_cycles=1000000)
        output_matmul_result = (await fixture.read(
            'result_output', LHS_ROWS * RHS_COLS *
            4)).view(dtype=np.int32).reshape([LHS_ROWS, RHS_COLS])

        assert ((result_data == output_matmul_result).all())


@cocotb.test()
async def core_mini_axi_basic_write_read_memory_qspi_tapeout(dut):
    """Basic test to check if TCM memory can be written and read back."""
    core_mini_axi = CoreMiniAxiInterface(dut, csr_base_addr=0x200000)
    await core_mini_axi.init()
    # Clock must run before reset so the QSPI bridges' reset synchronizers see
    # the deassertion synchronously; otherwise the first transaction can make
    # the simulator oscillate at t=0.
    cocotb.start_soon(core_mini_axi.clock.start())
    await core_mini_axi.reset()

    # Test reading/writing words
    await core_mini_axi.write_word(0x100, 0x42)
    await core_mini_axi.write_word(0x104, 0x43)
    rdata = (await core_mini_axi.read(0x100, 16)).view(np.uint32)
    assert (rdata[0:2] == np.array([0x42, 0x43])).all()

    # Three write/read data burst
    wdata = np.arange(48, dtype=np.uint8)
    await core_mini_axi.write(0x0, wdata)

    # Unaligned read, taking two bursts
    rdata = await core_mini_axi.read(0x8, 16)
    assert (np.arange(8, 24, dtype=np.uint8) == rdata).all()

    # Unaligned write, taking two bursts
    wdata = np.arange(20, dtype=np.uint8)
    await core_mini_axi.write(0x204, wdata)
    rdata = await core_mini_axi.read(0x200, 32)
    assert (wdata == rdata[4:24]).all()

    # NOTE: The following loop iterates over the top 1KB and bottom 1KB of ITCM and DTCM.
    # ITCM and DTCM starting addresses are configured in `Parameters.scala`.
    for size in range(11):
      txn_bytes = 2 ** size
      wdata = np.random.randint(0, 255, txn_bytes, dtype=np.uint8)

      # ITCM Bottom 1KB
      for i in tqdm.tqdm(range(1024 // txn_bytes)):
        await core_mini_axi.write(i * txn_bytes, wdata)
      # ITCM Top 1KB (512KB = 0x80000)
      for i in tqdm.tqdm(range(1024 // txn_bytes)):
        await core_mini_axi.write(0x80000 - 1024 + (i * txn_bytes), wdata)

      # DTCM Bottom 1KB (Starts at 0x100000)
      for i in tqdm.tqdm(range(1024 // txn_bytes)):
        await core_mini_axi.write(0x100000 + (i * txn_bytes), wdata)
      # DTCM Top 1KB (512KB = 0x80000; End = 0x100000 + 0x80000 = 0x180000)
      for i in tqdm.tqdm(range(1024 // txn_bytes)):
        await core_mini_axi.write(0x180000 - 1024 + (i * txn_bytes), wdata)

      # Readback Verification
      # ITCM Bottom 1KB
      for i in tqdm.tqdm(range(1024 // txn_bytes)):
        rdata = await core_mini_axi.read(i * txn_bytes, txn_bytes)
        assert(rdata == wdata).all()
      # ITCM Top 1KB
      for i in tqdm.tqdm(range(1024 // txn_bytes)):
        rdata = await core_mini_axi.read(0x80000 - 1024 + (i * txn_bytes), txn_bytes)
        assert(rdata == wdata).all()

      # DTCM Bottom 1KB
      for i in tqdm.tqdm(range(1024 // txn_bytes)):
        rdata = await core_mini_axi.read(0x100000 + (i * txn_bytes), txn_bytes)
        assert(rdata == wdata).all()
      # DTCM Top 1KB
      for i in tqdm.tqdm(range(1024 // txn_bytes)):
        rdata = await core_mini_axi.read(0x180000 - 1024 + (i * txn_bytes), txn_bytes)
        assert(rdata == wdata).all()
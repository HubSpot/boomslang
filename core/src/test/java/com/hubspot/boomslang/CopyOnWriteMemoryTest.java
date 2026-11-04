package com.hubspot.boomslang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.types.MemoryLimits;

public class CopyOnWriteMemoryTest {

  private static final int GOLDEN_PAGES = 2;
  private static final int MAX_PAGES = 8;

  @Test
  public void itReadsFromTheGoldenSnapshotUntilWritten() {
    byte[] golden = golden();
    CopyOnWriteMemory memory = memory(golden);

    assertThat(memory.read(70_000)).isEqualTo(golden[70_000]);
    assertThat(memory.readInt(4_000))
      .isEqualTo(
        (golden[4_000] & 0xFF) |
        ((golden[4_001] & 0xFF) << 8) |
        ((golden[4_002] & 0xFF) << 16) |
        ((golden[4_003] & 0xFF) << 24)
      );
    assertThat(memory.readBytes(65_530, 12)).containsExactly(slice(golden, 65_530, 12));
    assertThat(memory.getCopiedPageCount()).isZero();
  }

  @Test
  public void itCopiesOnlyTheWrittenPageAndLeavesTheGoldenSnapshotUntouched() {
    byte[] golden = golden();
    byte[] original = golden.clone();
    CopyOnWriteMemory memory = memory(golden);

    memory.writeI32(8_200, 0x12345678);

    assertThat(memory.readInt(8_200)).isEqualTo(0x12345678);
    assertThat(memory.read(8_199)).isEqualTo(original[8_199]);
    assertThat(golden).isEqualTo(original);
    assertThat(memory.getCopiedPageCount()).isEqualTo(1);
  }

  @Test
  public void itHandlesAccessesThatStraddleCowPages() {
    CopyOnWriteMemory memory = memory(golden());

    memory.writeLong(4_092, 0x0102030405060708L);
    memory.write(8_190, new byte[] { 9, 10, 11, 12 }, 0, 4);

    assertThat(memory.readLong(4_092)).isEqualTo(0x0102030405060708L);
    assertThat(memory.readBytes(8_190, 4)).containsExactly(9, 10, 11, 12);
    assertThat(memory.getCopiedPageCount()).isEqualTo(3);
  }

  @Test
  public void itServesZeroesAndNewPagesAfterGrowing() {
    CopyOnWriteMemory memory = memory(golden());
    int firstNewAddr = GOLDEN_PAGES * CopyOnWriteMemory.WASM_PAGE_SIZE;

    assertThat(memory.grow(3)).isEqualTo(GOLDEN_PAGES);
    int lastAddr = (GOLDEN_PAGES + 3) * CopyOnWriteMemory.WASM_PAGE_SIZE - 4;

    assertThat(memory.readInt(firstNewAddr)).isZero();
    memory.writeI32(lastAddr, 42);

    assertThat(memory.readInt(lastAddr)).isEqualTo(42);
    assertThat(memory.getNewPageCount()).isEqualTo(1);
  }

  @Test
  public void itRejectsAccessesOutsideTheCurrentMemory() {
    CopyOnWriteMemory memory = memory(golden());
    int end = GOLDEN_PAGES * CopyOnWriteMemory.WASM_PAGE_SIZE;

    assertThatThrownBy(() -> memory.readInt(end - 2))
      .isInstanceOf(WasmRuntimeException.class);
    assertThatThrownBy(() -> memory.writeByte(end, (byte) 1))
      .isInstanceOf(WasmRuntimeException.class);
    assertThatThrownBy(() -> memory.read(-1)).isInstanceOf(WasmRuntimeException.class);
    assertThat(memory.readBytes(end, 0)).isEmpty();
  }

  @Test
  public void itRefusesToGrowPastTheMaximum() {
    CopyOnWriteMemory memory = memory(golden());

    assertThat(memory.grow(MAX_PAGES - GOLDEN_PAGES + 1)).isEqualTo(-1);
    assertThat(memory.pages()).isEqualTo(GOLDEN_PAGES);
  }

  @Test
  public void itRestoresTheSnapshotViewOnReset() {
    byte[] golden = golden();
    CopyOnWriteMemory memory = memory(golden);
    memory.writeByte(100, (byte) 7);
    memory.grow(2);

    memory.reset();

    assertThat(memory.read(100)).isEqualTo(golden[100]);
    assertThat(memory.pages()).isEqualTo(GOLDEN_PAGES);
    assertThat(memory.getPrivateMemoryBytes()).isZero();
    assertThatThrownBy(() -> memory.read(GOLDEN_PAGES * CopyOnWriteMemory.WASM_PAGE_SIZE))
      .isInstanceOf(WasmRuntimeException.class);
  }

  @Test
  public void itStartsWithTheProvidedPrivatePages() {
    byte[] page = new byte[CopyOnWriteMemory.COW_PAGE_SIZE];
    page[5] = 99;
    CopyOnWriteMemory memory = new CopyOnWriteMemory(
      golden(),
      new MemoryLimits(GOLDEN_PAGES, MAX_PAGES),
      -1,
      Map.of(17, page)
    );

    assertThat(memory.read(17 * CopyOnWriteMemory.COW_PAGE_SIZE + 5))
      .isEqualTo((byte) 99);
    assertThat(memory.getNewPageCount()).isEqualTo(1);
  }

  private static CopyOnWriteMemory memory(byte[] golden) {
    return new CopyOnWriteMemory(golden, new MemoryLimits(GOLDEN_PAGES, MAX_PAGES));
  }

  private static byte[] golden() {
    byte[] golden = new byte[GOLDEN_PAGES * CopyOnWriteMemory.WASM_PAGE_SIZE];
    for (int i = 0; i < golden.length; i++) {
      golden[i] = (byte) (i * 31 + 7);
    }
    return golden;
  }

  private static byte[] slice(byte[] bytes, int from, int len) {
    byte[] out = new byte[len];
    System.arraycopy(bytes, from, out, 0, len);
    return out;
  }
}

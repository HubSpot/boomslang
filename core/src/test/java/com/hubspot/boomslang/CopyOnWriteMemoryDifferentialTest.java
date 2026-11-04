package com.hubspot.boomslang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.types.DataSegment;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.PassiveDataSegment;

class CopyOnWriteMemoryDifferentialTest {

  private static final int WASM_PAGE = 65536;
  private static final int PRIVATE_PAGE = 4096;
  private static final int INITIAL_PAGES = 3;
  private static final int MAX_PAGES = 5;
  private static final int OPERATIONS_PER_SEED = 5000;
  private static final long[] SEEDS = { 0L, 1L, 0x5EEDC0DEL, 0x7FFFFFFF12345678L };
  private static final int[] WIDTHS = { 1, 2, 4, 8, 4, 8 };

  @Test
  void itMatchesSeededOperationsWithAnEmptyGoldenSnapshot() {
    runSeeds(new byte[0], "empty");
  }

  @Test
  void itMatchesSeededOperationsWithZeroGoldenPages() {
    runSeeds(new byte[2 * WASM_PAGE], "zero");
  }

  @Test
  void itMatchesSeededOperationsWithSparseGoldenPages() {
    runSeeds(sparseGolden(), "sparse");
  }

  @Test
  void itMatchesSeededOperationsWithDenseGoldenPages() {
    byte[] golden = new byte[2 * WASM_PAGE];
    new Random(0xD3E5EL).nextBytes(golden);
    runSeeds(golden, "dense");
  }

  @Test
  void itMatchesSeededOperationsWithAPartialFinalGoldenSubpage() {
    byte[] golden = Arrays.copyOf(sparseGolden(), WASM_PAGE + PRIVATE_PAGE + 7);
    golden[golden.length - 1] = (byte) 0xFE;
    runSeeds(golden, "partial");
  }

  @Test
  void itReadsAcrossBoundariesWithOnlyOneSidePrivate() {
    Fixture fixture = new Fixture(sparseGolden());
    for (int boundary : new int[] { PRIVATE_PAGE, WASM_PAGE, 2 * WASM_PAGE }) {
      for (int side : new int[] { -1, 0 }) {
        fixture.reset();
        fixture.context = "mixed boundary=" + boundary + " private side=" + side;
        int touched = boundary + side;
        fixture.write(touched, 1, memory -> memory.writeByte(touched, (byte) 0xA9));
        fixture.assertScalars(boundary - 1);
        fixture.assertScalars(boundary - 3);
        fixture.assertScalars(boundary - 7);
        fixture.assertContents();
        fixture.write(boundary - 3, 8, memory -> memory.writeLong(boundary - 3, -1));
        fixture.assertScalars(boundary - 3);
        fixture.assertContents();
      }
    }
  }

  @Test
  void itReadsAcrossThePartialGoldenCutoffBeforeAndAfterMaterializationAndReset() {
    byte[] golden = Arrays.copyOf(sparseGolden(), WASM_PAGE + PRIVATE_PAGE + 7);
    golden[golden.length - 1] = (byte) 0xFE;
    Fixture fixture = new Fixture(golden);
    for (int phase = 0; phase < 3; phase++) {
      fixture.context = "partial golden cutoff phase=" + phase;
      for (int offset = -8; offset <= 1; offset++) {
        fixture.assertScalars(golden.length + offset);
      }
      if (phase == 0) {
        fixture.write(
          golden.length - 1,
          1,
          memory -> memory.writeByte(golden.length - 1, golden[golden.length - 1])
        );
        assertThat(fixture.cow.getCopiedPageCount()).isZero();
        assertThat(fixture.cow.getNewPageCount()).isEqualTo(1);
      } else {
        fixture.reset();
      }
      fixture.assertContents();
    }
  }

  @Test
  void itStartsAndResetsAtTheGoldenSizeWhenItExceedsDeclaredInitialPages() {
    byte[] golden = Arrays.copyOf(sparseGolden(), 4 * WASM_PAGE);
    golden[golden.length - 1] = (byte) 0x81;
    Fixture fixture = new Fixture(golden);
    assertThat(fixture.cow.initialPages()).isEqualTo(INITIAL_PAGES);
    assertThat(fixture.cow.pages()).isEqualTo(4);
    assertThat(fixture.cow.grow(1)).isEqualTo(fixture.reference.grow(1)).isEqualTo(4);
    fixture.write(
      golden.length - 1,
      8,
      memory -> memory.writeLong(golden.length - 1, -1)
    );
    fixture.assertContents();
    fixture.reset();
    assertThat(fixture.cow.pages()).isEqualTo(4);
    fixture.assertContents();
  }

  private static void runSeeds(byte[] golden, String scenario) {
    for (long seed : SEEDS) {
      Random random = new Random(seed);
      Fixture fixture = new Fixture(golden);
      for (int step = 0; step < OPERATIONS_PER_SEED; step++) {
        int operation = random.nextInt(18);
        fixture.context =
          "scenario=" +
          scenario +
          " seed=" +
          seed +
          " step=" +
          step +
          " operation=" +
          operation;
        try {
          if (step % 503 == 502) {
            fixture.reset();
          } else {
            runOperation(fixture, random, operation);
          }
          fixture.assertCounts();
          int probe = address(random, fixture.size(), 8);
          fixture.assertScalars(probe);
          if (step % 128 == 0) {
            fixture.assertContents();
          }
        } catch (AssertionError | RuntimeException failure) {
          throw new AssertionError(fixture.context, failure);
        }
      }
      fixture.assertContents();
      fixture.reset();
      fixture.assertContents();
      assertThat(golden)
        .as(fixture.context + " immutable golden")
        .isEqualTo(fixture.golden);
    }
  }

  private static void runOperation(Fixture fixture, Random random, int operation) {
    if (operation < WIDTHS.length) {
      int addr = address(random, fixture.size(), WIDTHS[operation]);
      long value = random.nextLong();
      fixture.write(
        addr,
        WIDTHS[operation],
        memory -> writeScalar(memory, operation, addr, value)
      );
      fixture.assertScalars(Math.min(addr, fixture.size() - 8));
      return;
    }
    int length = random.nextInt(2 * PRIVATE_PAGE + 18);
    int addr = address(random, fixture.size(), length);
    switch (operation) {
      case 6 -> {
        int offset = random.nextInt(17);
        byte[] data = new byte[offset + length + 11];
        random.nextBytes(data);
        fixture.write(addr, length, memory -> memory.write(addr, data, offset, length));
      }
      case 7 -> {
        byte[] data = new byte[length];
        random.nextBytes(data);
        fixture.write(addr, length, memory -> memory.write(addr, data));
      }
      case 8 -> {
        byte value = (byte) random.nextInt();
        fixture.write(addr, length, memory -> memory.fill(value, addr, addr + length));
      }
      case 9, 10, 11 -> {
        int source = address(random, fixture.size(), length);
        int destination =
          switch (operation) {
            case 9 -> Math.min(fixture.size() - length, source + Math.max(1, length / 2));
            case 10 -> Math.max(0, source - Math.max(1, length / 2));
            default -> source;
          };
        fixture.write(
          destination,
          length,
          memory -> memory.copy(destination, source, length)
        );
      }
      case 12 -> {
        int offset = random.nextInt(fixture.passive.length - length + 1);
        fixture.write(
          addr,
          length,
          memory -> memory.initPassiveSegment(0, addr, offset, length)
        );
      }
      case 13 -> assertThat(fixture.cow.readBytes(addr, length))
        .as(fixture.context + " bulk read addr=" + addr + " length=" + length)
        .isEqualTo(fixture.reference.readBytes(addr, length));
      case 14 -> {
        int[] deltas = { -1, 0, 1, 2, MAX_PAGES, Integer.MIN_VALUE, Integer.MAX_VALUE };
        int delta = deltas[random.nextInt(deltas.length)];
        assertThat(fixture.cow.grow(delta))
          .as(fixture.context + " grow=" + delta)
          .isEqualTo(fixture.reference.grow(delta));
        assertThat(fixture.cow.pages())
          .as(fixture.context)
          .isEqualTo(fixture.reference.pages());
      }
      case 15 -> {
        int bad = random.nextBoolean() ? -1 : fixture.size();
        fixture.assertTrap(memory -> memory.writeByte(bad, (byte) 7));
      }
      case 16 -> {
        if (random.nextInt(32) == 0) {
          fixture.write(0, fixture.size(), Memory::zero);
        } else {
          fixture.write(addr, 0, memory -> memory.fill((byte) 1, addr, addr));
        }
      }
      case 17 -> fixture.assertScalars(address(random, fixture.size(), 8));
      default -> throw new AssertionError(operation);
    }
  }

  @Test
  void itMatchesEveryScalarWidthAcrossBoundariesAndPrivatePageStates() {
    Fixture fixture = new Fixture(sparseGolden());
    assertThat(fixture.cow.grow(1)).isEqualTo(fixture.reference.grow(1));
    for (int boundary : new int[] {
      PRIVATE_PAGE,
      WASM_PAGE,
      2 * WASM_PAGE,
      3 * WASM_PAGE,
    }) {
      for (int offset = -9; offset <= 9; offset++) {
        int addr = boundary + offset;
        for (int kind = 0; kind < WIDTHS.length; kind++) {
          fixture.reset();
          assertThat(fixture.cow.grow(1)).isEqualTo(fixture.reference.grow(1));
          fixture.context =
            "boundary=" + boundary + " offset=" + offset + " kind=" + kind;
          fixture.assertScalars(addr);
          int scalarKind = kind;
          long[] values = {
            0L,
            -1L,
            0x7FL,
            0x80L,
            0x7FFFL,
            0x8000L,
            0x7FFFFFFFL,
            0x80000000L,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            0x80FF7F01020304FEL,
          };
          for (long value : values) {
            fixture.write(
              addr,
              WIDTHS[kind],
              memory -> writeScalar(memory, scalarKind, addr, value)
            );
            fixture.assertScalars(addr);
          }
          fixture.assertContents();
        }
      }
    }
  }

  @Test
  void itMatchesFloatingPointSpecialValuesAndRawIntegerPayloadReads() {
    Fixture fixture = new Fixture(sparseGolden());
    int[] floatBits = {
      0,
      0x80000000,
      1,
      0x007FFFFF,
      0x00800000,
      0x7F7FFFFF,
      0x7F800000,
      0xFF800000,
      0x7FC00000,
    };
    long[] doubleBits = {
      0L,
      Long.MIN_VALUE,
      1L,
      0x000FFFFFFFFFFFFFL,
      0x0010000000000000L,
      0x7FEFFFFFFFFFFFFFL,
      0x7FF0000000000000L,
      0xFFF0000000000000L,
      0x7FF8000000000000L,
    };
    for (int addr : new int[] { 1, PRIVATE_PAGE - 3, WASM_PAGE - 7, WASM_PAGE - 1 }) {
      fixture.context = "float special addr=" + addr;
      for (int bits : floatBits) {
        fixture.write(
          addr,
          4,
          memory -> memory.writeF32(addr, Float.intBitsToFloat(bits))
        );
        fixture.assertScalars(addr);
      }
      for (long bits : doubleBits) {
        fixture.write(
          addr,
          8,
          memory -> memory.writeF64(addr, Double.longBitsToDouble(bits))
        );
        fixture.assertScalars(addr);
      }
      fixture.write(addr, 4, memory -> memory.writeI32(addr, 0xFFC12345));
      fixture.assertScalars(addr);
      fixture.write(addr, 8, memory -> memory.writeLong(addr, 0xFFF8123456789ABCL));
      fixture.assertScalars(addr);
    }
  }

  @Test
  void itMatchesBulkOperationsIncludingOverlapEmptyRangesAndPassiveDrop() {
    Fixture fixture = new Fixture(sparseGolden());
    byte[] data = new byte[WASM_PAGE + 41];
    new Random(1234).nextBytes(data);
    for (int addr : new int[] { 0, 1, PRIVATE_PAGE - 1, WASM_PAGE - 7 }) {
      for (int length : new int[] { 0, 1, PRIVATE_PAGE + 3, WASM_PAGE + 17 }) {
        fixture.context = "bulk addr=" + addr + " length=" + length;
        fixture.write(addr, length, memory -> memory.write(addr, data, 7, length));
        assertThat(fixture.cow.readBytes(addr, length))
          .as(fixture.context)
          .isEqualTo(fixture.reference.readBytes(addr, length));
        fixture.write(addr + 1, length, memory -> memory.copy(addr + 1, addr, length));
        fixture.write(addr, length, memory -> memory.copy(addr, addr + 1, length));
        fixture.write(addr, length, memory -> memory.copy(addr, addr, length));
        fixture.write(
          addr,
          length,
          memory -> memory.fill((byte) 0xA5, addr, addr + length)
        );
        fixture.assertContents();
      }
    }
    int end = fixture.size();
    fixture.write(end, 0, memory -> memory.write(end, new byte[0]));
    fixture.write(end, 0, memory -> memory.copy(end, end, 0));
    fixture.write(end, 0, memory -> memory.fill((byte) 1, end, end));
    fixture.write(
      end,
      0,
      memory -> memory.initPassiveSegment(0, end, fixture.passive.length, 0)
    );
    fixture.cow.drop(0);
    fixture.reference.drop(0);
    fixture.write(end, 0, memory -> memory.initPassiveSegment(0, end, 0, 0));
    fixture.reset();
    fixture.write(
      PRIVATE_PAGE - 3,
      19,
      memory -> memory.initPassiveSegment(0, PRIVATE_PAGE - 3, 7, 19)
    );
    fixture.write(0, fixture.size(), Memory::zero);
    fixture.assertContents();
  }

  @Test
  void itMatchesGrowthLimitsAndRebuildsTheReferenceOnReset() {
    Fixture fixture = new Fixture(sparseGolden());
    assertThat(fixture.cow.initialPages()).isEqualTo(INITIAL_PAGES);
    assertThat(fixture.cow.maximumPages()).isEqualTo(MAX_PAGES);
    assertThat(fixture.cow.shared()).isFalse();
    for (int delta : new int[] { 0, 1, 1, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
      fixture.context = "growth delta=" + delta;
      assertThat(fixture.cow.grow(delta))
        .as(fixture.context)
        .isEqualTo(fixture.reference.grow(delta));
      fixture.assertContents();
      fixture.assertCounts();
    }
    fixture.write(
      4 * WASM_PAGE - 1,
      8,
      memory -> memory.writeLong(4 * WASM_PAGE - 1, -1L)
    );
    fixture.reset();
    fixture.assertTrap(memory -> memory.read(3 * WASM_PAGE));
    fixture.assertContents();
    fixture.write(1, 2, memory -> memory.fill((byte) 9, 1, 3));
    fixture.assertContents();
  }

  @Test
  void itMatchesAnInitiallyZeroSizedMemoryAndReturnsToZeroOnReset() {
    CopyOnWriteMemory cow = new CopyOnWriteMemory(
      CopyOnWriteMemory.Golden.capture(new byte[0]),
      new MemoryLimits(0, 2)
    );
    ByteArrayMemory reference = new ByteArrayMemory(new MemoryLimits(0, 2));
    cow.initialize(null, new DataSegment[0]);
    reference.initialize(null, new DataSegment[0]);
    assertThat(cow.pages()).isEqualTo(reference.pages()).isZero();
    assertThat(cow.readBytes(0, 0)).isEqualTo(reference.readBytes(0, 0));
    cow.write(0, new byte[0]);
    reference.write(0, new byte[0]);
    cow.copy(0, 0, 0);
    reference.copy(0, 0, 0);
    cow.zero();
    reference.zero();
    assertThat(cow.getPrivateMemoryBytes()).isZero();
    for (int kind = 0; kind < WIDTHS.length; kind++) {
      int scalarKind = kind;
      assertThat(catchThrowable(() -> readScalar(cow, scalarKind, 0)))
        .isExactlyInstanceOf(WasmRuntimeException.class);
      assertThat(catchThrowable(() -> readScalar(reference, scalarKind, 0)))
        .isExactlyInstanceOf(WasmRuntimeException.class);
    }
    assertThat(cow.grow(1)).isEqualTo(reference.grow(1)).isZero();
    assertThat(cow.readBytes(0, WASM_PAGE)).isEqualTo(reference.readBytes(0, WASM_PAGE));
    cow.writeLong(1, -1L);
    reference.writeLong(1, -1L);
    assertThat(cow.readBytes(0, WASM_PAGE)).isEqualTo(reference.readBytes(0, WASM_PAGE));
    assertThat(cow.getCopiedPageCount()).isZero();
    assertThat(cow.getNewPageCount()).isEqualTo(1);
    cow.reset();
    assertThat(cow.pages()).isZero();
    assertThat(cow.getPrivateMemoryBytes()).isZero();
    assertThat(cow.grow(1)).isZero();
    assertThat(cow.readBytes(0, WASM_PAGE)).isEqualTo(new byte[WASM_PAGE]);
  }

  @Test
  void itPreservesScalarAccessAtTheRuntimeAddressCeiling() {
    CopyOnWriteMemory cow = new CopyOnWriteMemory(
      CopyOnWriteMemory.Golden.capture(new byte[0]),
      new MemoryLimits(0, Memory.RUNTIME_MAX_PAGES)
    );
    ByteArrayMemory reference = new ByteArrayMemory(new MemoryLimits(1, 1));
    int end = Memory.bytes(cow.maximumPages());
    long value = 0x12345678ABCDEF01L;
    for (int kind = 0; kind < WIDTHS.length; kind++) {
      int scalarKind = kind;
      int width = WIDTHS[kind];
      writeScalar(reference, kind, 0, value);
      byte[] expected = reference.readBytes(0, width);
      for (int addr : new int[] {
        end - width,
        end - width - 1,
        end - PRIVATE_PAGE - width + 1,
        end - WASM_PAGE - width + 1,
      }) {
        String context = "runtime ceiling kind=" + kind + " addr=" + addr;
        cow.reset();
        assertThat(cow.grow(Memory.RUNTIME_MAX_PAGES)).as(context).isZero();
        assertThat(readScalar(cow, kind, addr)).as(context).isZero();
        assertThat(cow.getPrivateMemoryBytes()).as(context).isZero();
        writeScalar(cow, kind, addr, value);
        assertThat(readScalar(cow, kind, addr))
          .as(context)
          .isEqualTo(readScalar(reference, kind, 0));
        assertThat(cow.readBytes(addr, width)).as(context).isEqualTo(expected);
        int touchedPages = (addr + width - 1) / PRIVATE_PAGE - addr / PRIVATE_PAGE + 1;
        assertThat(cow.getCopiedPageCount()).as(context).isZero();
        assertThat(cow.getNewPageCount()).as(context).isEqualTo(touchedPages);
        assertThat(cow.getPrivateMemoryBytes())
          .as(context)
          .isEqualTo((long) touchedPages * PRIVATE_PAGE);
        assertThat(cow.grow(1)).as(context).isEqualTo(-1);
        int invalid = end - width + 1;
        assertThat(catchThrowable(() -> readScalar(cow, scalarKind, invalid)))
          .as(context)
          .isExactlyInstanceOf(WasmRuntimeException.class);
        assertThat(catchThrowable(() -> writeScalar(cow, scalarKind, invalid, value)))
          .as(context)
          .isExactlyInstanceOf(WasmRuntimeException.class);
        assertThat(cow.readBytes(addr, width)).as(context).isEqualTo(expected);
        assertThat(cow.getNewPageCount()).as(context).isEqualTo(touchedPages);
      }
    }
  }

  @Test
  void itMatchesOutOfBoundsExceptionTypesWithoutChangingBytesOrCounts() {
    Fixture fixture = new Fixture(sparseGolden());
    for (int kind = 0; kind < WIDTHS.length; kind++) {
      int scalarKind = kind;
      int width = WIDTHS[kind];
      for (int addr : new int[] {
        Integer.MIN_VALUE,
        -WASM_PAGE,
        -8,
        -1,
        fixture.size() - width + 1,
        fixture.size(),
        fixture.size() + 1,
        Integer.MAX_VALUE - width + 1,
        Integer.MAX_VALUE,
      }) {
        fixture.context = "bounds kind=" + kind + " addr=" + addr;
        fixture.assertTrap(memory -> writeScalar(memory, scalarKind, addr, -1L));
        fixture.assertTrap(memory -> readScalar(memory, scalarKind, addr));
      }
    }
    for (int addr : new int[] {
      Integer.MIN_VALUE,
      -1,
      fixture.size() + 1,
      Integer.MAX_VALUE,
    }) {
      fixture.context = "bulk bounds addr=" + addr;
      fixture.assertTrap(memory -> memory.readBytes(addr, 0));
      fixture.assertTrap(memory -> memory.write(addr, new byte[0]));
      fixture.assertTrap(memory -> memory.copy(addr, 0, 0));
      fixture.assertTrap(memory -> memory.copy(0, addr, 0));
      fixture.assertTrap(memory -> memory.fill((byte) 1, addr, addr));
      fixture.assertTrap(memory -> memory.initPassiveSegment(0, addr, 0, 0));
    }
    fixture.assertTrap(memory -> memory.readBytes(1, -1));
    fixture.assertTrap(memory -> memory.write(1, new byte[8], 0, -1));
    fixture.assertTrap(memory -> memory.copy(0, 0, -1));
    fixture.assertTrap(memory -> memory.fill((byte) 1, 8, 7));
    fixture.assertTrap(memory -> memory.readBytes(fixture.size() - 1, 2));
    fixture.assertTrap(memory -> memory.write(fixture.size() - 1, new byte[2]));
    fixture.assertTrap(memory -> memory.copy(0, fixture.size() - 1, 2));
    fixture.assertTrap(memory -> memory.copy(fixture.size() - 1, 0, 2));
    fixture.assertTrap(memory -> memory.fill((byte) 1, 0, fixture.size() + 1));
    fixture.assertTrap(memory -> memory.initPassiveSegment(0, fixture.size() - 1, 0, 2));
  }

  @Test
  void itMatchesRawNanPayloadsInPlainMemory() {
    Fixture fixture = new Fixture(sparseGolden());
    for (int addr : new int[] { 1, PRIVATE_PAGE - 1, WASM_PAGE - 1 }) {
      float f32 = Float.intBitsToFloat(0xFFC12345);
      double f64 = Double.longBitsToDouble(0xFFF8123456789ABCL);
      fixture.cow.writeF32(addr, f32);
      fixture.reference.writeF32(addr, f32);
      assertThat(fixture.cow.readInt(addr)).isEqualTo(Float.floatToRawIntBits(f32));
      assertThat(fixture.reference.readInt(addr)).isEqualTo(Float.floatToRawIntBits(f32));
      assertThat(fixture.cow.readInt(addr)).isEqualTo(fixture.reference.readInt(addr));
      fixture.cow.writeF64(addr, f64);
      fixture.reference.writeF64(addr, f64);
      assertThat(fixture.cow.readLong(addr)).isEqualTo(Double.doubleToRawLongBits(f64));
      assertThat(fixture.reference.readLong(addr))
        .isEqualTo(Double.doubleToRawLongBits(f64));
      assertThat(fixture.cow.readLong(addr)).isEqualTo(fixture.reference.readLong(addr));
    }
  }

  @Test
  void itPreservesThePreexistingUninitializedFillAndZeroDifference() {
    byte[] golden = sparseGolden();
    CopyOnWriteMemory cow = new CopyOnWriteMemory(
      CopyOnWriteMemory.Golden.capture(golden),
      new MemoryLimits(2, MAX_PAGES)
    );
    ByteArrayMemory reference = new ByteArrayMemory(new MemoryLimits(2, MAX_PAGES));
    reference.write(0, golden);
    cow.fill((byte) 7, 0, 8);
    reference.fill((byte) 7, 0, 8);
    assertThat(cow.readBytes(0, 8)).isEqualTo(Arrays.copyOf(golden, 8));
    assertThat(cow.readBytes(0, 8)).isNotEqualTo(reference.readBytes(0, 8));
    cow.zero();
    reference.zero();
    assertThat(cow.readBytes(0, golden.length)).isEqualTo(golden);
    assertThat(reference.readBytes(0, golden.length)).isEqualTo(new byte[golden.length]);
    assertThat(cow.getPrivateMemoryBytes()).isZero();
    assertThat(catchThrowable(() -> cow.fill((byte) 1, -1, 1))).isNull();
    assertThat(catchThrowable(() -> reference.fill((byte) 1, -1, 1)))
      .isExactlyInstanceOf(WasmRuntimeException.class);
  }

  @Test
  void itPreservesInvalidHostSourceDifferencesButTrapsInvalidInitAndDestRanges() {
    for (int offset : new int[] { -1, 8 }) {
      Fixture fixture = new Fixture(sparseGolden());
      assertThat(catchThrowable(() -> fixture.cow.write(0, new byte[8], offset, 1)))
        .isExactlyInstanceOf(ArrayIndexOutOfBoundsException.class);
      assertThat(catchThrowable(() -> fixture.reference.write(0, new byte[8], offset, 1)))
        .isExactlyInstanceOf(WasmRuntimeException.class);
      assertThat(fixture.cow.getCopiedPageCount()).isEqualTo(1);
      assertThat(fixture.cow.readBytes(0, fixture.size()))
        .isEqualTo(fixture.reference.readBytes(0, fixture.size()));
    }
    Fixture fixture = new Fixture(sparseGolden());
    assertThat(catchThrowable(() -> fixture.cow.write(0, new byte[0], -1, 0))).isNull();
    assertThat(catchThrowable(() -> fixture.reference.write(0, new byte[0], -1, 0)))
      .isExactlyInstanceOf(WasmRuntimeException.class);
    fixture.cow.drop(0);
    fixture.reference.drop(0);
    assertThat(catchThrowable(() -> fixture.cow.initPassiveSegment(0, 0, 0, 1)))
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(catchThrowable(() -> fixture.reference.initPassiveSegment(0, 0, 0, 1)))
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(catchThrowable(() -> fixture.cow.fill((byte) 1, 8, Integer.MAX_VALUE)))
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(
      catchThrowable(() -> fixture.reference.fill((byte) 1, 8, Integer.MAX_VALUE))
    )
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(
      catchThrowable(() -> fixture.cow.write(8, new byte[0], 0, Integer.MAX_VALUE))
    )
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(
      catchThrowable(() -> fixture.reference.write(8, new byte[0], 0, Integer.MAX_VALUE))
    )
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(fixture.cow.readBytes(0, fixture.size()))
      .isEqualTo(fixture.reference.readBytes(0, fixture.size()));
    assertThat(fixture.cow.getCopiedPageCount()).isZero();
    assertThat(fixture.cow.getNewPageCount()).isZero();
  }

  @Test
  void itPreservesPartialWritesFromAnInvalidSourceSlice() {
    Fixture fixture = new Fixture(sparseGolden());
    byte[] source = new byte[PRIVATE_PAGE];
    Arrays.fill(source, (byte) 0xA7);
    assertThat(catchThrowable(() -> fixture.cow.write(0, source, 0, PRIVATE_PAGE + 1)))
      .isExactlyInstanceOf(ArrayIndexOutOfBoundsException.class);
    assertThat(
      catchThrowable(() -> fixture.reference.write(0, source, 0, PRIVATE_PAGE + 1))
    )
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(fixture.cow.readBytes(0, PRIVATE_PAGE)).isEqualTo(source);
    assertThat(fixture.reference.readBytes(0, fixture.golden.length))
      .isEqualTo(fixture.golden);
    fixture.reference.write(0, source);
    assertThat(fixture.cow.readBytes(0, fixture.size()))
      .isEqualTo(fixture.reference.readBytes(0, fixture.size()));
    assertThat(fixture.cow.getCopiedPageCount()).isEqualTo(2);
    assertThat(fixture.cow.getNewPageCount()).isZero();
  }

  @Test
  void itKeepsInitializationAndDroppedSegmentsAcrossReset() {
    Fixture fixture = new Fixture(sparseGolden());
    fixture.cow.drop(0);
    fixture.cow.grow(1);
    fixture.cow.reset();
    assertThat(fixture.cow.pages()).isEqualTo(INITIAL_PAGES);
    assertThat(fixture.cow.getPrivateMemoryBytes()).isZero();
    fixture.cow.fill((byte) 9, PRIVATE_PAGE - 1, PRIVATE_PAGE + 1);
    assertThat(fixture.cow.readBytes(PRIVATE_PAGE - 1, 2))
      .containsExactly((byte) 9, (byte) 9);
    assertThat(fixture.cow.getCopiedPageCount()).isEqualTo(2);
    byte[] before = fixture.cow.readBytes(0, fixture.size());
    assertThat(catchThrowable(() -> fixture.cow.initPassiveSegment(0, 0, 0, 1)))
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(fixture.cow.readBytes(0, fixture.size())).isEqualTo(before);
    assertThat(fixture.cow.getCopiedPageCount()).isEqualTo(2);
    assertThat(
      catchThrowable(() -> fixture.cow.initPassiveSegment(0, 2 * PRIVATE_PAGE, 0, 1))
    )
      .isExactlyInstanceOf(WasmRuntimeException.class);
    assertThat(fixture.cow.readBytes(0, fixture.size())).isEqualTo(before);
    assertThat(fixture.cow.getCopiedPageCount()).isEqualTo(2);
    assertThat(catchThrowable(() -> fixture.cow.initPassiveSegment(0, 0, 0, 0))).isNull();
    assertThat(fixture.cow.getCopiedPageCount()).isEqualTo(2);
  }

  @Test
  void itRestoresClonedPrivatePagesAndPreservesExistingCounterClassification() {
    byte[] golden = sparseGolden();
    byte[] restoredGolden = new byte[PRIVATE_PAGE];
    byte[] restoredNew = new byte[PRIVATE_PAGE];
    Arrays.fill(restoredGolden, (byte) 0x81);
    Arrays.fill(restoredNew, (byte) 0x42);
    Fixture fixture = new Fixture(golden);
    CopyOnWriteMemory cow = new CopyOnWriteMemory(
      fixture.goldenSnapshot,
      new MemoryLimits(3, 6),
      MAX_PAGES,
      Map.of(1, restoredGolden, 33, restoredNew)
    );
    fixture.cow = cow;
    fixture.reference.write(PRIVATE_PAGE, restoredGolden);
    fixture.reference.write(33 * PRIVATE_PAGE, restoredNew);
    fixture.privatePages.set(1);
    fixture.privatePages.set(33);
    fixture.restoredPages.set(1);
    fixture.restoredPages.set(33);
    Arrays.fill(restoredGolden, (byte) 0);
    Arrays.fill(restoredNew, (byte) 0);
    fixture.assertContents();
    fixture.assertCounts();
    assertThat(cow.getCopiedPageCount()).isZero();
    assertThat(cow.getNewPageCount()).isEqualTo(2);
    fixture.write(PRIVATE_PAGE + 1, 8, memory -> memory.writeLong(PRIVATE_PAGE + 1, -1));
    fixture.write(
      2 * PRIVATE_PAGE - 1,
      4,
      memory -> memory.writeI32(2 * PRIVATE_PAGE - 1, 123)
    );
    fixture.write(
      34 * PRIVATE_PAGE,
      1,
      memory -> memory.writeByte(34 * PRIVATE_PAGE, (byte) 9)
    );
    fixture.assertContents();
    fixture.reset();
    fixture.assertContents();
    fixture.write(PRIVATE_PAGE, 1, memory -> memory.writeByte(PRIVATE_PAGE, (byte) 8));
    assertThat(cow.getCopiedPageCount()).isEqualTo(1);
    assertThat(cow.getNewPageCount()).isZero();
  }

  private static void writeScalar(Memory memory, int kind, int addr, long value) {
    switch (kind) {
      case 0 -> memory.writeByte(addr, (byte) value);
      case 1 -> memory.writeShort(addr, (short) value);
      case 2 -> memory.writeI32(addr, (int) value);
      case 3 -> memory.writeLong(addr, value);
      case 4 -> {
        float data = Float.intBitsToFloat((int) value);
        memory.writeF32(addr, Float.isNaN(data) ? Float.NaN : data);
      }
      case 5 -> {
        double data = Double.longBitsToDouble(value);
        memory.writeF64(addr, Double.isNaN(data) ? Double.NaN : data);
      }
      default -> throw new AssertionError(kind);
    }
  }

  private static long readScalar(Memory memory, int kind, int addr) {
    return switch (kind) {
      case 0 -> memory.read(addr);
      case 1 -> memory.readShort(addr);
      case 2 -> memory.readInt(addr);
      case 3 -> memory.readLong(addr);
      case 4 -> Float.floatToRawIntBits(memory.readFloat(addr));
      case 5 -> Double.doubleToRawLongBits(memory.readDouble(addr));
      default -> throw new AssertionError(kind);
    };
  }

  private static int address(Random random, int size, int width) {
    int[] boundaries = { 0, PRIVATE_PAGE, WASM_PAGE, 2 * WASM_PAGE, 3 * WASM_PAGE, size };
    if (random.nextBoolean()) {
      int near = boundaries[random.nextInt(boundaries.length)] + random.nextInt(19) - 9;
      return Math.max(0, Math.min(size - width, near));
    }
    return random.nextInt(size - width + 1);
  }

  private static byte[] sparseGolden() {
    byte[] golden = new byte[2 * WASM_PAGE];
    for (int addr : new int[] {
      0,
      1,
      PRIVATE_PAGE - 1,
      PRIVATE_PAGE,
      WASM_PAGE - 1,
      WASM_PAGE,
      2 * WASM_PAGE - 1,
    }) {
      golden[addr] = (byte) (addr * 31 + 0xA5);
    }
    return golden;
  }

  private static final class Fixture {

    private final byte[] golden;
    private final CopyOnWriteMemory.Golden goldenSnapshot;
    private final byte[] passive = new byte[3 * PRIVATE_PAGE];
    private final BitSet privatePages = new BitSet();
    private final BitSet restoredPages = new BitSet();
    private CopyOnWriteMemory cow;
    private ByteArrayMemory reference;
    private String context = "initial";

    private Fixture(byte[] golden) {
      this.golden = golden.clone();
      this.goldenSnapshot = CopyOnWriteMemory.Golden.capture(this.golden);
      new Random(0xDA7A).nextBytes(passive);
      cow =
        new CopyOnWriteMemory(
          goldenSnapshot,
          new MemoryLimits(INITIAL_PAGES, 6),
          MAX_PAGES
        );
      cow.initialize(null, segments());
      reference = reference();
      assertContents();
      assertCounts();
    }

    private DataSegment[] segments() {
      return new DataSegment[] { new PassiveDataSegment(passive.clone()) };
    }

    private ByteArrayMemory reference() {
      ByteArrayMemory memory = new ByteArrayMemory(
        new MemoryLimits(INITIAL_PAGES, MAX_PAGES)
      );
      memory.initialize(null, segments());
      int extraPages = golden.length / WASM_PAGE - INITIAL_PAGES;
      if (extraPages > 0) {
        assertThat(memory.grow(extraPages)).isEqualTo(INITIAL_PAGES);
      }
      memory.write(0, golden);
      return memory;
    }

    private int size() {
      return reference.pages() * WASM_PAGE;
    }

    private void write(int addr, int length, Consumer<Memory> operation) {
      context += " addr=" + addr + " length=" + length;
      operation.accept(reference);
      operation.accept(cow);
      if (length > 0) {
        privatePages.set(addr / PRIVATE_PAGE, (addr + length - 1) / PRIVATE_PAGE + 1);
      }
      assertThat(cow.readBytes(addr, length))
        .as(context)
        .isEqualTo(reference.readBytes(addr, length));
      assertCounts();
    }

    private void assertScalars(int addr) {
      long[] expected = scalarValues(reference, addr);
      assertThat(scalarValues(cow, addr))
        .as(context + " scalar probe=" + addr)
        .isEqualTo(expected);
      assertCounts();
    }

    private static long[] scalarValues(Memory memory, int addr) {
      return new long[] {
        memory.read(addr),
        memory.readI8(addr),
        memory.readU8(addr),
        memory.readShort(addr),
        memory.readI16(addr),
        memory.readU16(addr),
        memory.readInt(addr),
        memory.readI32(addr),
        memory.readU32(addr),
        memory.readLong(addr),
        memory.readI64(addr),
        memory.readF32(addr),
        memory.readF64(addr),
        Float.floatToRawIntBits(memory.readFloat(addr)),
        Double.doubleToRawLongBits(memory.readDouble(addr)),
      };
    }

    private void assertContents() {
      assertThat(cow.pages()).as(context + " pages").isEqualTo(reference.pages());
      assertThat(cow.readBytes(0, size()))
        .as(context + " all bytes")
        .isEqualTo(reference.readBytes(0, size()));
      assertCounts();
    }

    private void assertCounts() {
      int goldenSubPages = (golden.length / WASM_PAGE) * (WASM_PAGE / PRIVATE_PAGE);
      int copied = 0;
      for (
        int page = privatePages.nextSetBit(0);
        page >= 0;
        page = privatePages.nextSetBit(page + 1)
      ) {
        if (page < goldenSubPages && !restoredPages.get(page)) {
          copied++;
        }
      }
      assertThat(cow.getCopiedPageCount())
        .as(context + " copied pages")
        .isEqualTo(copied);
      assertThat(cow.getNewPageCount())
        .as(context + " new pages")
        .isEqualTo(privatePages.cardinality() - copied);
      assertThat(cow.getPrivateMemoryBytes())
        .as(context + " private bytes")
        .isEqualTo((long) privatePages.cardinality() * PRIVATE_PAGE);
    }

    private void assertTrap(Consumer<Memory> operation) {
      byte[] before = reference.readBytes(0, size());
      assertThat(catchThrowable(() -> operation.accept(reference)))
        .as(context + " reference exception")
        .isExactlyInstanceOf(WasmRuntimeException.class);
      assertThat(catchThrowable(() -> operation.accept(cow)))
        .as(context + " cow exception")
        .isExactlyInstanceOf(WasmRuntimeException.class);
      assertThat(reference.readBytes(0, size()))
        .as(context + " reference unchanged")
        .isEqualTo(before);
      assertThat(cow.readBytes(0, size()))
        .as(context + " cow unchanged")
        .isEqualTo(before);
      assertCounts();
    }

    private void reset() {
      cow.reset();
      privatePages.clear();
      restoredPages.clear();
      cow.initialize(null, segments());
      reference = reference();
      assertContents();
    }
  }
}

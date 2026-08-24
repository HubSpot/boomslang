package com.hubspot.boomslang;

import static java.lang.Math.min;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.types.DataSegment;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.PassiveDataSegment;

/**
 * Copy-on-write WASM linear memory backed by the {@link RuntimeImage} golden snapshot. Reads are
 * served from the shared golden pages until written; a write materializes a private 4 KiB copy of
 * the touched region within its 64 KiB WASM page, so many {@link PythonInstance}s can share one
 * snapshot while only paying for the pages they actually modify. {@code reset()} drops all private
 * pages, restoring the pristine snapshot view.
 */
public final class CopyOnWriteMemory implements Memory {

  public static final int WASM_PAGE_SIZE = 65536;
  public static final int COW_PAGE_SIZE = 4096;
  private static final int COW_PAGE_SHIFT = 12;
  private static final int COW_PAGE_MASK = 0xFFF;
  private static final int COW_PAGES_PER_WASM_PAGE = WASM_PAGE_SIZE / COW_PAGE_SIZE;

  private boolean initialized = false;

  private static final VarHandle SHORT_HANDLE = MethodHandles.byteArrayViewVarHandle(
    short[].class,
    ByteOrder.LITTLE_ENDIAN
  );
  private static final VarHandle INT_HANDLE = MethodHandles.byteArrayViewVarHandle(
    int[].class,
    ByteOrder.LITTLE_ENDIAN
  );
  private static final VarHandle FLOAT_HANDLE = MethodHandles.byteArrayViewVarHandle(
    float[].class,
    ByteOrder.LITTLE_ENDIAN
  );
  private static final VarHandle LONG_HANDLE = MethodHandles.byteArrayViewVarHandle(
    long[].class,
    ByteOrder.LITTLE_ENDIAN
  );
  private static final VarHandle DOUBLE_HANDLE = MethodHandles.byteArrayViewVarHandle(
    double[].class,
    ByteOrder.LITTLE_ENDIAN
  );

  private final byte[] goldenSnapshot;
  private final int goldenPages;
  private final MemoryLimits limits;
  private final int callerMaxPages;

  private final byte[][][] privatePages;
  private int totalPages;
  private DataSegment[] dataSegments;

  private int newPageCount = 0;
  private int copiedPageCount = 0;

  public CopyOnWriteMemory(byte[] goldenSnapshot, MemoryLimits limits) {
    this(goldenSnapshot, limits, -1, null);
  }

  public CopyOnWriteMemory(
    byte[] goldenSnapshot,
    MemoryLimits limits,
    int callerMaxPages
  ) {
    this(goldenSnapshot, limits, callerMaxPages, null);
  }

  public CopyOnWriteMemory(
    byte[] goldenSnapshot,
    MemoryLimits limits,
    int callerMaxPages,
    Map<Integer, byte[]> initialPrivatePages
  ) {
    this.goldenSnapshot = goldenSnapshot;
    this.goldenPages = goldenSnapshot.length / WASM_PAGE_SIZE;
    this.limits = limits;
    this.callerMaxPages = callerMaxPages;
    this.totalPages = Math.max(goldenPages, limits.initialPages());

    int maxWasmPages = maximumPages();
    this.privatePages = new byte[maxWasmPages][][];

    if (initialPrivatePages == null || initialPrivatePages.isEmpty()) {
      return;
    }

    validateInitialPrivatePages(initialPrivatePages, maxWasmPages);
    initialPrivatePages.forEach((globalCowIndex, pageData) -> {
      int wasmPageIndex = globalCowIndex / COW_PAGES_PER_WASM_PAGE;
      int cowSubPageIndex = globalCowIndex % COW_PAGES_PER_WASM_PAGE;

      if (wasmPageIndex < maxWasmPages) {
        if (this.privatePages[wasmPageIndex] == null) {
          this.privatePages[wasmPageIndex] = new byte[COW_PAGES_PER_WASM_PAGE][];
        }
        this.privatePages[wasmPageIndex][cowSubPageIndex] = pageData.clone();
        this.newPageCount++;
      }
    });
  }

  @Override
  public int pages() {
    return totalPages;
  }

  @Override
  public int grow(int delta) {
    int currentPages = totalPages;
    int newPages = currentPages + delta;

    if (newPages > maximumPages() || newPages < currentPages) {
      return -1;
    }

    totalPages = newPages;
    return currentPages;
  }

  @Override
  public int initialPages() {
    return limits.initialPages();
  }

  @Override
  public int maximumPages() {
    int moduleMax = min(limits.maximumPages(), RUNTIME_MAX_PAGES);
    return callerMaxPages >= 0 ? min(moduleMax, callerMaxPages) : moduleMax;
  }

  @Override
  public void initialize(Instance instance, DataSegment[] dataSegments) {
    this.dataSegments = dataSegments;
    if (dataSegments == null) {
      return;
    }
    initialized = true;
  }

  @Override
  public void zero() {
    if (!initialized) {
      return;
    }
    fill((byte) 0, 0, sizeInBytes());
  }

  @Override
  public void fill(byte value, int fromIndex, int toIndex) {
    if (!initialized) {
      return;
    }
    checkBounds(fromIndex, toIndex - fromIndex);

    int currentAddr = fromIndex;
    while (currentAddr < toIndex) {
      int wasmPageIndex = currentAddr / WASM_PAGE_SIZE;
      int cowSubPageIndex = (currentAddr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;
      int offsetInCowPage = currentAddr & COW_PAGE_MASK;
      int bytesInCowPage = Math.min(
        COW_PAGE_SIZE - offsetInCowPage,
        toIndex - currentAddr
      );

      byte[] page = ensurePrivateSubPage(wasmPageIndex, cowSubPageIndex);
      Arrays.fill(page, offsetInCowPage, offsetInCowPage + bytesInCowPage, value);

      currentAddr += bytesInCowPage;
    }
  }

  @Override
  public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
    DataSegment segment = dataSegments[segmentId];
    write(dest, segment.data(), offset, size);
  }

  @Override
  public byte read(int addr) {
    checkBounds(addr, 1);

    int wasmPageIndex = addr / WASM_PAGE_SIZE;
    int cowSubPageIndex = (addr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;
    int offsetInCowPage = addr & COW_PAGE_MASK;

    if (
      privatePages[wasmPageIndex] != null &&
      privatePages[wasmPageIndex][cowSubPageIndex] != null
    ) {
      return privatePages[wasmPageIndex][cowSubPageIndex][offsetInCowPage];
    }

    if (addr < goldenSnapshot.length) {
      return goldenSnapshot[addr];
    }

    return 0;
  }

  @Override
  public byte[] readBytes(int addr, int len) {
    checkBounds(addr, len);

    byte[] result = new byte[len];
    int currentAddr = addr;
    int resultOffset = 0;
    int remaining = len;

    while (remaining > 0) {
      int wasmPageIndex = currentAddr / WASM_PAGE_SIZE;
      int cowSubPageIndex = (currentAddr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;
      int offsetInCowPage = currentAddr & COW_PAGE_MASK;
      int bytesInCowPage = Math.min(COW_PAGE_SIZE - offsetInCowPage, remaining);

      if (
        privatePages[wasmPageIndex] != null &&
        privatePages[wasmPageIndex][cowSubPageIndex] != null
      ) {
        byte[] page = privatePages[wasmPageIndex][cowSubPageIndex];
        System.arraycopy(page, offsetInCowPage, result, resultOffset, bytesInCowPage);
      } else if (currentAddr < goldenSnapshot.length) {
        int bytesToCopy = Math.min(bytesInCowPage, goldenSnapshot.length - currentAddr);
        System.arraycopy(goldenSnapshot, currentAddr, result, resultOffset, bytesToCopy);
      }

      currentAddr += bytesInCowPage;
      resultOffset += bytesInCowPage;
      remaining -= bytesInCowPage;
    }

    return result;
  }

  @Override
  public void writeByte(int addr, byte data) {
    checkBounds(addr, 1);

    int wasmPageIndex = addr / WASM_PAGE_SIZE;
    int cowSubPageIndex = (addr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;
    int offsetInCowPage = addr & COW_PAGE_MASK;

    byte[] page = ensurePrivateSubPage(wasmPageIndex, cowSubPageIndex);
    page[offsetInCowPage] = data;
  }

  @Override
  public void write(int addr, byte[] data, int offset, int size) {
    checkBounds(addr, size);

    int currentAddr = addr;
    int dataOffset = offset;
    int remaining = size;

    while (remaining > 0) {
      int wasmPageIndex = currentAddr / WASM_PAGE_SIZE;
      int cowSubPageIndex = (currentAddr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;
      int offsetInCowPage = currentAddr & COW_PAGE_MASK;
      int bytesInCowPage = Math.min(COW_PAGE_SIZE - offsetInCowPage, remaining);

      byte[] page = ensurePrivateSubPage(wasmPageIndex, cowSubPageIndex);
      System.arraycopy(data, dataOffset, page, offsetInCowPage, bytesInCowPage);

      currentAddr += bytesInCowPage;
      dataOffset += bytesInCowPage;
      remaining -= bytesInCowPage;
    }
  }

  private byte[] getPageForMultiByteRead(int addr) {
    int wasmPageIndex = addr / WASM_PAGE_SIZE;
    int cowSubPageIndex = (addr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;

    if (
      privatePages[wasmPageIndex] != null &&
      privatePages[wasmPageIndex][cowSubPageIndex] != null
    ) {
      return privatePages[wasmPageIndex][cowSubPageIndex];
    }

    return null;
  }

  @Override
  public int readInt(int addr) {
    checkBounds(addr, 4);

    if ((addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 4) {
      byte[] page = getPageForMultiByteRead(addr);
      if (page != null) {
        return (int) INT_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr + 4 <= goldenSnapshot.length) {
        return (int) INT_HANDLE.get(goldenSnapshot, addr);
      }
    }

    return (
      (read(addr) & 0xFF) |
      ((read(addr + 1) & 0xFF) << 8) |
      ((read(addr + 2) & 0xFF) << 16) |
      ((read(addr + 3) & 0xFF) << 24)
    );
  }

  @Override
  public void writeI32(int addr, int data) {
    checkBounds(addr, 4);

    if ((addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 4) {
      int wasmPageIndex = addr / WASM_PAGE_SIZE;
      int cowSubPageIndex = (addr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;
      byte[] page = ensurePrivateSubPage(wasmPageIndex, cowSubPageIndex);
      INT_HANDLE.set(page, addr & COW_PAGE_MASK, data);
    } else {
      writeByte(addr, (byte) (data & 0xFF));
      writeByte(addr + 1, (byte) ((data >> 8) & 0xFF));
      writeByte(addr + 2, (byte) ((data >> 16) & 0xFF));
      writeByte(addr + 3, (byte) ((data >> 24) & 0xFF));
    }
  }

  @Override
  public long readLong(int addr) {
    checkBounds(addr, 8);

    if ((addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 8) {
      byte[] page = getPageForMultiByteRead(addr);
      if (page != null) {
        return (long) LONG_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr + 8 <= goldenSnapshot.length) {
        return (long) LONG_HANDLE.get(goldenSnapshot, addr);
      }
    }

    return (
      ((long) readInt(addr) & 0xFFFFFFFFL) |
      (((long) readInt(addr + 4) & 0xFFFFFFFFL) << 32)
    );
  }

  @Override
  public void writeLong(int addr, long data) {
    checkBounds(addr, 8);

    if ((addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 8) {
      int wasmPageIndex = addr / WASM_PAGE_SIZE;
      int cowSubPageIndex = (addr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;
      byte[] page = ensurePrivateSubPage(wasmPageIndex, cowSubPageIndex);
      LONG_HANDLE.set(page, addr & COW_PAGE_MASK, data);
    } else {
      writeI32(addr, (int) (data & 0xFFFFFFFFL));
      writeI32(addr + 4, (int) ((data >>> 32) & 0xFFFFFFFFL));
    }
  }

  @Override
  public short readShort(int addr) {
    checkBounds(addr, 2);

    if ((addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 2) {
      byte[] page = getPageForMultiByteRead(addr);
      if (page != null) {
        return (short) SHORT_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr + 2 <= goldenSnapshot.length) {
        return (short) SHORT_HANDLE.get(goldenSnapshot, addr);
      }
    }

    return (short) ((read(addr) & 0xFF) | ((read(addr + 1) & 0xFF) << 8));
  }

  @Override
  public void writeShort(int addr, short data) {
    checkBounds(addr, 2);

    if ((addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 2) {
      int wasmPageIndex = addr / WASM_PAGE_SIZE;
      int cowSubPageIndex = (addr % WASM_PAGE_SIZE) / COW_PAGE_SIZE;
      byte[] page = ensurePrivateSubPage(wasmPageIndex, cowSubPageIndex);
      SHORT_HANDLE.set(page, addr & COW_PAGE_MASK, data);
    } else {
      writeByte(addr, (byte) (data & 0xFF));
      writeByte(addr + 1, (byte) ((data >> 8) & 0xFF));
    }
  }

  @Override
  public float readFloat(int addr) {
    checkBounds(addr, 4);

    if ((addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 4) {
      byte[] page = getPageForMultiByteRead(addr);
      if (page != null) {
        return (float) FLOAT_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr + 4 <= goldenSnapshot.length) {
        return (float) FLOAT_HANDLE.get(goldenSnapshot, addr);
      }
    }
    return Float.intBitsToFloat(readInt(addr));
  }

  @Override
  public double readDouble(int addr) {
    checkBounds(addr, 8);

    if ((addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 8) {
      byte[] page = getPageForMultiByteRead(addr);
      if (page != null) {
        return (double) DOUBLE_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr + 8 <= goldenSnapshot.length) {
        return (double) DOUBLE_HANDLE.get(goldenSnapshot, addr);
      }
    }

    return Double.longBitsToDouble(readLong(addr));
  }

  @Override
  public int notify(int addr, int numThreads) {
    throw new UnsupportedOperationException("not a multi threaded impl");
  }

  @Override
  public int waitOn(int addr, int expectedValue, long timeoutMillis) {
    throw new UnsupportedOperationException("not a multi threaded impl");
  }

  @Override
  public int waitOn(int addr, long expectedValue, long timeoutMillis) {
    throw new UnsupportedOperationException("not a multi threaded impl");
  }

  @Override
  public Object lock(int addr) {
    throw new UnsupportedOperationException("not a multi threaded impl");
  }

  @Override
  public boolean shared() {
    return false;
  }

  private byte[] ensurePrivateSubPage(int wasmPageIndex, int cowSubPageIndex) {
    if (privatePages[wasmPageIndex] == null) {
      privatePages[wasmPageIndex] = new byte[COW_PAGES_PER_WASM_PAGE][];
    }

    byte[] cowPage = privatePages[wasmPageIndex][cowSubPageIndex];
    if (cowPage != null) {
      return cowPage;
    }

    cowPage = new byte[COW_PAGE_SIZE];
    int cowPageStartAddr =
      (wasmPageIndex * WASM_PAGE_SIZE) + (cowSubPageIndex * COW_PAGE_SIZE);

    if (cowPageStartAddr < goldenSnapshot.length) {
      int bytesToCopy = Math.min(COW_PAGE_SIZE, goldenSnapshot.length - cowPageStartAddr);
      System.arraycopy(goldenSnapshot, cowPageStartAddr, cowPage, 0, bytesToCopy);
    }

    if (wasmPageIndex < this.goldenPages) {
      this.copiedPageCount++;
    } else {
      this.newPageCount++;
    }

    privatePages[wasmPageIndex][cowSubPageIndex] = cowPage;
    return cowPage;
  }

  private void checkBounds(int addr, int size) {
    if (
      addr < 0 ||
      size < 0 ||
      addr > sizeInBytes() ||
      (size > 0 && (addr + size) > sizeInBytes())
    ) {
      throw new WasmRuntimeException(
        "out of bounds memory access: attempted to access address: " +
        addr +
        " with size: " +
        size +
        " but memory size is: " +
        sizeInBytes()
      );
    }
  }

  private int sizeInBytes() {
    return totalPages * WASM_PAGE_SIZE;
  }

  private void validateInitialPrivatePages(
    Map<Integer, byte[]> initialPrivatePages,
    int maxWasmPages
  ) {
    for (Map.Entry<Integer, byte[]> entry : initialPrivatePages.entrySet()) {
      int globalCowIndex = entry.getKey();
      byte[] pageData = entry.getValue();
      int wasmPageIndex = globalCowIndex / COW_PAGES_PER_WASM_PAGE;

      if (globalCowIndex < 0) {
        throw new IllegalArgumentException(
          "Invalid snapshot: Private page map contains a negative index: " +
          globalCowIndex
        );
      }
      if (pageData == null || pageData.length != COW_PAGE_SIZE) {
        throw new IllegalArgumentException(
          String.format(
            "Invalid snapshot: Page %d has incorrect size. Expected %d, got %d.",
            globalCowIndex,
            COW_PAGE_SIZE,
            pageData != null ? pageData.length : -1
          )
        );
      }
      if (wasmPageIndex >= maxWasmPages) {
        throw new IllegalArgumentException(
          String.format(
            "Invalid snapshot: Page %d is out of bounds for the memory limit of %d WASM pages.",
            globalCowIndex,
            maxWasmPages
          )
        );
      }
    }
  }

  public int getCopiedPageCount() {
    return copiedPageCount;
  }

  public int getNewPageCount() {
    return newPageCount;
  }

  public long getPrivateMemoryBytes() {
    return (long) (copiedPageCount + newPageCount) * COW_PAGE_SIZE;
  }

  public void reset() {
    Arrays.fill(privatePages, null);
    totalPages = Math.max(goldenPages, limits.initialPages());
    newPageCount = 0;
    copiedPageCount = 0;
  }

  /**
   * A captured warm memory baseline: the private-page delta over the shared golden snapshot (each
   * value a 4 KiB COW page, keyed by its global COW-page index) plus the total WASM page count at
   * capture time. Reverting an instance to this with {@link #rebaseline(Snapshot)} restores a warm
   * interpreter (e.g. one with a heavy package already imported) instead of the cold golden state.
   * The captured pages are copies, so a single {@code Snapshot} is safe to share as a per-pod
   * baseline that many instances rebaseline from concurrently.
   */
  public record Snapshot(Map<Integer, byte[]> privatePages, int totalPages) {}

  /**
   * Captures the current private-page delta over the golden snapshot as a reusable warm baseline.
   * Only the pages this instance has actually written are copied (typically a small fraction of the
   * heap), so the snapshot is far smaller than full linear memory.
   */
  public Snapshot snapshotPrivatePages() {
    Map<Integer, byte[]> delta = new HashMap<>();
    for (int wasmPageIndex = 0; wasmPageIndex < privatePages.length; wasmPageIndex++) {
      byte[][] subPages = privatePages[wasmPageIndex];
      if (subPages == null) {
        continue;
      }
      for (
        int cowSubPageIndex = 0;
        cowSubPageIndex < COW_PAGES_PER_WASM_PAGE;
        cowSubPageIndex++
      ) {
        byte[] page = subPages[cowSubPageIndex];
        if (page != null) {
          int globalCowIndex =
            (wasmPageIndex * COW_PAGES_PER_WASM_PAGE) + cowSubPageIndex;
          delta.put(globalCowIndex, page.clone());
        }
      }
    }
    return new Snapshot(delta, totalPages);
  }

  /**
   * Reverts this memory to a captured warm baseline: like {@link #reset()}, but restores the
   * snapshot's private pages over golden instead of dropping to the cold golden view. Pages absent
   * from the snapshot read through to golden (or to zero beyond it), exactly as they did in the
   * instance the snapshot was captured from. The snapshot's pages are copied, leaving it unmodified
   * for reuse across instances.
   */
  public void rebaseline(Snapshot snapshot) {
    Map<Integer, byte[]> warmPrivatePages = snapshot.privatePages();
    int maxWasmPages = maximumPages();
    validateInitialPrivatePages(warmPrivatePages, maxWasmPages);

    Arrays.fill(privatePages, null);
    newPageCount = 0;
    copiedPageCount = 0;

    warmPrivatePages.forEach((globalCowIndex, pageData) -> {
      int wasmPageIndex = globalCowIndex / COW_PAGES_PER_WASM_PAGE;
      int cowSubPageIndex = globalCowIndex % COW_PAGES_PER_WASM_PAGE;
      if (wasmPageIndex < maxWasmPages) {
        if (privatePages[wasmPageIndex] == null) {
          privatePages[wasmPageIndex] = new byte[COW_PAGES_PER_WASM_PAGE][];
        }
        privatePages[wasmPageIndex][cowSubPageIndex] = pageData.clone();
        if (wasmPageIndex < goldenPages) {
          copiedPageCount++;
        } else {
          newPageCount++;
        }
      }
    });

    int floor = Math.max(goldenPages, limits.initialPages());
    this.totalPages = Math.min(Math.max(snapshot.totalPages(), floor), maxWasmPages);
  }

  @Override
  public void write(int addr, byte[] data) {
    Memory.super.write(addr, data);
  }

  @Override
  public long readU16(int addr) {
    return readShort(addr) & 0xFFFF;
  }

  @Override
  public void writeF32(int addr, float data) {
    writeI32(addr, Float.floatToIntBits(data));
  }

  @Override
  public long readF32(int addr) {
    return readInt(addr);
  }

  @Override
  public void writeF64(int addr, double data) {
    writeLong(addr, Double.doubleToLongBits(data));
  }

  @Override
  public long readF64(int addr) {
    return readLong(addr);
  }

  @Override
  public void drop(int segment) {
    dataSegments[segment] = PassiveDataSegment.EMPTY;
  }
}

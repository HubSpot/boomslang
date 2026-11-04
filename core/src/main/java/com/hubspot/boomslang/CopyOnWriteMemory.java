package com.hubspot.boomslang;

import static java.lang.Math.min;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
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
  private static final long WASM_PAGE_SIZE_LONG = WASM_PAGE_SIZE;

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

  private final int maxCowPages;
  private byte[][] privatePages;
  private int totalPages;
  private long sizeBytes;
  private int cowPageLimit;
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
    this.sizeBytes = totalPages * WASM_PAGE_SIZE_LONG;
    this.cowPageLimit = totalPages * COW_PAGES_PER_WASM_PAGE;

    int maxWasmPages = maximumPages();
    this.maxCowPages = maxWasmPages * COW_PAGES_PER_WASM_PAGE;
    this.privatePages = new byte[cowPageLimit][];

    if (initialPrivatePages == null || initialPrivatePages.isEmpty()) {
      return;
    }

    validateInitialPrivatePages(initialPrivatePages, maxWasmPages);
    initialPrivatePages.forEach((globalCowIndex, pageData) -> {
      ensurePageTableCapacity(globalCowIndex + 1);
      this.privatePages[globalCowIndex] = pageData.clone();
      this.newPageCount++;
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
    sizeBytes = newPages * WASM_PAGE_SIZE_LONG;
    cowPageLimit = newPages * COW_PAGES_PER_WASM_PAGE;
    ensurePageTableCapacity(cowPageLimit);
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
      int cowPageIndex = currentAddr >>> COW_PAGE_SHIFT;
      int offsetInCowPage = currentAddr & COW_PAGE_MASK;
      int bytesInCowPage = Math.min(
        COW_PAGE_SIZE - offsetInCowPage,
        toIndex - currentAddr
      );

      byte[] page = ensurePrivatePage(cowPageIndex);
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
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex >= cowPageLimit) {
      checkBounds(addr, 1);
    }

    byte[] page = privatePages[cowPageIndex];
    if (page != null) {
      return page[addr & COW_PAGE_MASK];
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
      int cowPageIndex = currentAddr >>> COW_PAGE_SHIFT;
      int offsetInCowPage = currentAddr & COW_PAGE_MASK;
      int bytesInCowPage = Math.min(COW_PAGE_SIZE - offsetInCowPage, remaining);

      byte[] page = privatePages[cowPageIndex];
      if (page != null) {
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
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex >= cowPageLimit) {
      checkBounds(addr, 1);
    }

    byte[] page = ensurePrivatePage(cowPageIndex);
    page[addr & COW_PAGE_MASK] = data;
  }

  @Override
  public void write(int addr, byte[] data, int offset, int size) {
    checkBounds(addr, size);

    int currentAddr = addr;
    int dataOffset = offset;
    int remaining = size;

    while (remaining > 0) {
      int cowPageIndex = currentAddr >>> COW_PAGE_SHIFT;
      int offsetInCowPage = currentAddr & COW_PAGE_MASK;
      int bytesInCowPage = Math.min(COW_PAGE_SIZE - offsetInCowPage, remaining);

      byte[] page = ensurePrivatePage(cowPageIndex);
      System.arraycopy(data, dataOffset, page, offsetInCowPage, bytesInCowPage);

      currentAddr += bytesInCowPage;
      dataOffset += bytesInCowPage;
      remaining -= bytesInCowPage;
    }
  }

  @Override
  public int readInt(int addr) {
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex < cowPageLimit && (addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 4) {
      byte[] page = privatePages[cowPageIndex];
      if (page != null) {
        return (int) INT_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr <= goldenSnapshot.length - 4) {
        return (int) INT_HANDLE.get(goldenSnapshot, addr);
      }
    }

    checkBounds(addr, 4);

    return (
      (read(addr) & 0xFF) |
      ((read(addr + 1) & 0xFF) << 8) |
      ((read(addr + 2) & 0xFF) << 16) |
      ((read(addr + 3) & 0xFF) << 24)
    );
  }

  @Override
  public void writeI32(int addr, int data) {
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex < cowPageLimit && (addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 4) {
      byte[] page = ensurePrivatePage(cowPageIndex);
      INT_HANDLE.set(page, addr & COW_PAGE_MASK, data);
    } else {
      checkBounds(addr, 4);
      writeByte(addr, (byte) (data & 0xFF));
      writeByte(addr + 1, (byte) ((data >> 8) & 0xFF));
      writeByte(addr + 2, (byte) ((data >> 16) & 0xFF));
      writeByte(addr + 3, (byte) ((data >> 24) & 0xFF));
    }
  }

  @Override
  public long readLong(int addr) {
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex < cowPageLimit && (addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 8) {
      byte[] page = privatePages[cowPageIndex];
      if (page != null) {
        return (long) LONG_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr <= goldenSnapshot.length - 8) {
        return (long) LONG_HANDLE.get(goldenSnapshot, addr);
      }
    }

    checkBounds(addr, 8);

    return (
      ((long) readInt(addr) & 0xFFFFFFFFL) |
      (((long) readInt(addr + 4) & 0xFFFFFFFFL) << 32)
    );
  }

  @Override
  public void writeLong(int addr, long data) {
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex < cowPageLimit && (addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 8) {
      byte[] page = ensurePrivatePage(cowPageIndex);
      LONG_HANDLE.set(page, addr & COW_PAGE_MASK, data);
    } else {
      checkBounds(addr, 8);
      writeI32(addr, (int) (data & 0xFFFFFFFFL));
      writeI32(addr + 4, (int) ((data >>> 32) & 0xFFFFFFFFL));
    }
  }

  @Override
  public short readShort(int addr) {
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex < cowPageLimit && (addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 2) {
      byte[] page = privatePages[cowPageIndex];
      if (page != null) {
        return (short) SHORT_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr <= goldenSnapshot.length - 2) {
        return (short) SHORT_HANDLE.get(goldenSnapshot, addr);
      }
    }

    checkBounds(addr, 2);

    return (short) ((read(addr) & 0xFF) | ((read(addr + 1) & 0xFF) << 8));
  }

  @Override
  public void writeShort(int addr, short data) {
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex < cowPageLimit && (addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 2) {
      byte[] page = ensurePrivatePage(cowPageIndex);
      SHORT_HANDLE.set(page, addr & COW_PAGE_MASK, data);
    } else {
      checkBounds(addr, 2);
      writeByte(addr, (byte) (data & 0xFF));
      writeByte(addr + 1, (byte) ((data >> 8) & 0xFF));
    }
  }

  @Override
  public float readFloat(int addr) {
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex < cowPageLimit && (addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 4) {
      byte[] page = privatePages[cowPageIndex];
      if (page != null) {
        return (float) FLOAT_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr <= goldenSnapshot.length - 4) {
        return (float) FLOAT_HANDLE.get(goldenSnapshot, addr);
      }
    }

    checkBounds(addr, 4);
    return Float.intBitsToFloat(readInt(addr));
  }

  @Override
  public double readDouble(int addr) {
    int cowPageIndex = addr >>> COW_PAGE_SHIFT;
    if (cowPageIndex < cowPageLimit && (addr & COW_PAGE_MASK) <= COW_PAGE_SIZE - 8) {
      byte[] page = privatePages[cowPageIndex];
      if (page != null) {
        return (double) DOUBLE_HANDLE.get(page, addr & COW_PAGE_MASK);
      } else if (addr <= goldenSnapshot.length - 8) {
        return (double) DOUBLE_HANDLE.get(goldenSnapshot, addr);
      }
    }

    checkBounds(addr, 8);

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

  private byte[] ensurePrivatePage(int cowPageIndex) {
    byte[] cowPage = privatePages[cowPageIndex];
    if (cowPage != null) {
      return cowPage;
    }
    return materializePrivatePage(cowPageIndex);
  }

  private byte[] materializePrivatePage(int cowPageIndex) {
    byte[] cowPage = new byte[COW_PAGE_SIZE];
    int cowPageStartAddr = cowPageIndex << COW_PAGE_SHIFT;

    if (cowPageStartAddr < goldenSnapshot.length) {
      int bytesToCopy = Math.min(COW_PAGE_SIZE, goldenSnapshot.length - cowPageStartAddr);
      System.arraycopy(goldenSnapshot, cowPageStartAddr, cowPage, 0, bytesToCopy);
    }

    if (cowPageIndex < goldenPages * COW_PAGES_PER_WASM_PAGE) {
      this.copiedPageCount++;
    } else {
      this.newPageCount++;
    }

    privatePages[cowPageIndex] = cowPage;
    return cowPage;
  }

  private void ensurePageTableCapacity(int cowPages) {
    if (cowPages <= privatePages.length) {
      return;
    }
    int doubled = Math.min(Math.max(privatePages.length * 2, 1), maxCowPages);
    privatePages = Arrays.copyOf(privatePages, Math.max(cowPages, doubled));
  }

  private void checkBounds(int addr, int size) {
    if (addr < 0 || size < 0 || addr > sizeBytes - size) {
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
    sizeBytes = totalPages * WASM_PAGE_SIZE_LONG;
    cowPageLimit = totalPages * COW_PAGES_PER_WASM_PAGE;
    newPageCount = 0;
    copiedPageCount = 0;
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

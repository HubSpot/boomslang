package com.hubspot.boomslang;

import static java.lang.Math.min;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import run.endive.runtime.Instance;
import run.endive.runtime.Memory;
import run.endive.runtime.WasmRuntimeException;
import run.endive.wasm.types.DataSegment;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasm.types.PassiveDataSegment;

/**
 * Copy-on-write WASM linear memory backed by a shared {@link Golden} snapshot. The golden snapshot
 * is a set of immutable 4 KiB chunks plus a flat read table (entry {@code i} backs bytes {@code
 * [i * 4096, (i + 1) * 4096)}); all-zero chunks share one zero chunk. An instance reads through the
 * shared table until its first write, which clones the table and materializes a private copy of the
 * touched chunk; an ownership bitmap records which chunks are already private. {@code reset()}
 * drops all private state and points back at the shared table.
 */
public final class CopyOnWriteMemory implements Memory {

  public static final int WASM_PAGE_SIZE = 65536;
  public static final int COW_PAGE_SIZE = 4096;
  private static final int COW_PAGE_SHIFT = 12;
  private static final int COW_PAGE_MASK = COW_PAGE_SIZE - 1;
  private static final int COW_PAGES_PER_WASM_PAGE = WASM_PAGE_SIZE / COW_PAGE_SIZE;
  private static final long[] ZERO_OWNERSHIP = new long[ownershipWords(
    RUNTIME_MAX_PAGES * COW_PAGES_PER_WASM_PAGE
  )];

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

  /**
   * Immutable golden memory snapshot shared by every {@link CopyOnWriteMemory} created from it.
   * Capture it once (for example in {@link RuntimeImage}) and reuse it.
   */
  public static final class Golden {

    private final Map<Integer, byte[]> chunks;
    private final byte[] zero = new byte[COW_PAGE_SIZE];
    private final int byteLength;
    private final Map<Integer, byte[][]> views = new ConcurrentHashMap<>();

    private Golden(Map<Integer, byte[]> chunks, int byteLength) {
      this.chunks = Map.copyOf(chunks);
      this.byteLength = byteLength;
    }

    /** Captures a snapshot from raw bytes. The array is copied; it is not retained. */
    public static Golden capture(byte[] bytes) {
      Objects.requireNonNull(bytes);
      Map<Integer, byte[]> chunks = new HashMap<>();
      for (int address = 0; address < bytes.length;) {
        int length = Math.min(COW_PAGE_SIZE, bytes.length - address);
        if (!isZero(bytes, address, length)) {
          byte[] chunk = new byte[COW_PAGE_SIZE];
          System.arraycopy(bytes, address, chunk, 0, length);
          chunks.put(address >>> COW_PAGE_SHIFT, chunk);
        }
        address += length;
      }
      return new Golden(chunks, bytes.length);
    }

    /** Captures the first {@code pages} WASM pages of {@code memory}, 4 KiB at a time. */
    static Golden capture(Memory memory, int pages) {
      int byteLength = Math.multiplyExact(pages, WASM_PAGE_SIZE);
      Map<Integer, byte[]> chunks = new HashMap<>();
      for (int address = 0; address < byteLength; address += COW_PAGE_SIZE) {
        byte[] chunk = memory.readBytes(address, COW_PAGE_SIZE);
        if (!isZero(chunk, 0, chunk.length)) {
          chunks.put(address >>> COW_PAGE_SHIFT, chunk);
        }
      }
      return new Golden(chunks, byteLength);
    }

    /** Returns the snapshot size in bytes. */
    public int sizeInBytes() {
      return byteLength;
    }

    /** Materializes the snapshot as a new byte array. */
    public byte[] toBytes() {
      byte[] bytes = new byte[byteLength];
      chunks.forEach((index, chunk) -> {
        int address = index << COW_PAGE_SHIFT;
        System.arraycopy(
          chunk,
          0,
          bytes,
          address,
          Math.min(COW_PAGE_SIZE, byteLength - address)
        );
      });
      return bytes;
    }

    private static boolean isZero(byte[] bytes, int offset, int length) {
      for (int index = offset; index < offset + length; index++) {
        if (bytes[index] != 0) {
          return false;
        }
      }
      return true;
    }

    private byte[] chunk(int index) {
      return chunks.getOrDefault(index, zero);
    }

    private byte[][] sharedReads(int pages) {
      return views.computeIfAbsent(pages, this::createSharedReads);
    }

    private byte[][] createSharedReads(int pages) {
      int capacity = pages * COW_PAGES_PER_WASM_PAGE;
      byte[][] reads = new byte[capacity][];
      for (int index = 0; index < capacity; index++) {
        reads[index] = chunk(index);
      }
      return reads;
    }
  }

  private final Golden golden;
  private final byte[][] pristine;
  private final int goldenPages;
  private final MemoryLimits limits;
  private final int callerMaxPages;
  private byte[][] reads;
  private long[] ownership = ZERO_OWNERSHIP;
  private boolean ownsOwnership;
  private Map<Integer, byte[]> hiddenRestores = Map.of();
  private boolean privateReads;
  private int totalPages;
  private boolean initialized;
  private DataSegment[] dataSegments;
  private int newPageCount;
  private int copiedPageCount;

  public CopyOnWriteMemory(byte[] goldenSnapshot, MemoryLimits limits) {
    this(Golden.capture(goldenSnapshot), limits, -1, null);
  }

  public CopyOnWriteMemory(
    byte[] goldenSnapshot,
    MemoryLimits limits,
    int callerMaxPages
  ) {
    this(Golden.capture(goldenSnapshot), limits, callerMaxPages, null);
  }

  public CopyOnWriteMemory(
    byte[] goldenSnapshot,
    MemoryLimits limits,
    int callerMaxPages,
    Map<Integer, byte[]> initialPrivatePages
  ) {
    this(Golden.capture(goldenSnapshot), limits, callerMaxPages, initialPrivatePages);
  }

  public CopyOnWriteMemory(Golden golden, MemoryLimits limits) {
    this(golden, limits, -1, null);
  }

  public CopyOnWriteMemory(Golden golden, MemoryLimits limits, int callerMaxPages) {
    this(golden, limits, callerMaxPages, null);
  }

  public CopyOnWriteMemory(
    Golden golden,
    MemoryLimits limits,
    int callerMaxPages,
    Map<Integer, byte[]> initialPrivatePages
  ) {
    this.golden = Objects.requireNonNull(golden);
    this.goldenPages = golden.byteLength / WASM_PAGE_SIZE;
    this.limits = limits;
    this.callerMaxPages = callerMaxPages;
    this.totalPages = Math.max(goldenPages, limits.initialPages());
    this.pristine = golden.sharedReads(totalPages);
    this.reads = pristine;

    if (initialPrivatePages == null || initialPrivatePages.isEmpty()) {
      return;
    }

    validateInitialPrivatePages(initialPrivatePages, maximumPages());
    initialPrivatePages.forEach((index, bytes) -> {
      byte[] page = bytes.clone();
      if (index < visibleChunkCount()) {
        ensurePrivateReads();
        reads[index] = page;
        setOwned(index);
      } else {
        if (hiddenRestores.isEmpty()) {
          hiddenRestores = new HashMap<>();
        }
        hiddenRestores.put(index, page);
      }
      newPageCount++;
    });
  }

  @Override
  public int pages() {
    return totalPages;
  }

  @Override
  public int grow(int delta) {
    if (delta == 0) {
      return totalPages > maximumPages() ? -1 : totalPages;
    }
    long next = (long) totalPages + delta;
    if (delta < 0 || next > maximumPages()) {
      return -1;
    }
    int previous = totalPages;
    int required = chunkCount((int) next);
    ensureCapacity(required);
    initializeReads(chunkCount(previous), required);
    totalPages = (int) next;
    return previous;
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
    if (dataSegments != null) {
      initialized = true;
    }
  }

  @Override
  public void zero() {
    if (initialized) {
      fill((byte) 0, 0, sizeInBytes());
    }
  }

  @Override
  public void fill(byte value, int fromIndex, int toIndex) {
    if (!initialized) {
      return;
    }
    long size = (long) toIndex - fromIndex;
    if (size < 0 || size > Integer.MAX_VALUE) {
      throw trap(fromIndex, toIndex - fromIndex);
    }
    bounds(fromIndex, (int) size);
    for (int current = fromIndex; current < toIndex;) {
      int length = Math.min(COW_PAGE_SIZE - (current & COW_PAGE_MASK), toIndex - current);
      Arrays.fill(
        writable(current),
        current & COW_PAGE_MASK,
        (current & COW_PAGE_MASK) + length,
        value
      );
      current += length;
    }
  }

  @Override
  public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
    DataSegment segment = dataSegments[segmentId];
    byte[] data = segment.data();
    if (offset < 0 || size < 0 || (long) offset + size > data.length) {
      throw new WasmRuntimeException(
        "out of bounds memory access: attempted to access address: " +
        offset +
        " but limit is: " +
        data.length +
        " and size: " +
        size
      );
    }
    write(dest, data, offset, size);
  }

  @Override
  public byte read(int addr) {
    try {
      return reads[addr >>> COW_PAGE_SHIFT][addr & COW_PAGE_MASK];
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      throw trap(addr, 1);
    }
  }

  @Override
  public short readShort(int addr) {
    if ((addr & COW_PAGE_MASK) > COW_PAGE_SIZE - 2) {
      return (short) readAcross(addr, 2);
    }
    try {
      return (short) SHORT_HANDLE.get(
        reads[addr >>> COW_PAGE_SHIFT],
        addr & COW_PAGE_MASK
      );
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      return (short) readAcross(addr, 2);
    }
  }

  @Override
  public int readInt(int addr) {
    if ((addr & COW_PAGE_MASK) > COW_PAGE_SIZE - 4) {
      return (int) readAcross(addr, 4);
    }
    try {
      return (int) INT_HANDLE.get(reads[addr >>> COW_PAGE_SHIFT], addr & COW_PAGE_MASK);
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      return (int) readAcross(addr, 4);
    }
  }

  @Override
  public long readLong(int addr) {
    if ((addr & COW_PAGE_MASK) > COW_PAGE_SIZE - 8) {
      return readAcross(addr, 8);
    }
    try {
      return (long) LONG_HANDLE.get(reads[addr >>> COW_PAGE_SHIFT], addr & COW_PAGE_MASK);
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      return readAcross(addr, 8);
    }
  }

  @Override
  public float readFloat(int addr) {
    if ((addr & COW_PAGE_MASK) > COW_PAGE_SIZE - 4) {
      return Float.intBitsToFloat((int) readAcross(addr, 4));
    }
    try {
      return (float) FLOAT_HANDLE.get(
        reads[addr >>> COW_PAGE_SHIFT],
        addr & COW_PAGE_MASK
      );
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      return Float.intBitsToFloat((int) readAcross(addr, 4));
    }
  }

  @Override
  public double readDouble(int addr) {
    if ((addr & COW_PAGE_MASK) > COW_PAGE_SIZE - 8) {
      return Double.longBitsToDouble(readAcross(addr, 8));
    }
    try {
      return (double) DOUBLE_HANDLE.get(
        reads[addr >>> COW_PAGE_SHIFT],
        addr & COW_PAGE_MASK
      );
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      return Double.longBitsToDouble(readAcross(addr, 8));
    }
  }

  @Override
  public void writeByte(int addr, byte data) {
    try {
      writable(addr)[addr & COW_PAGE_MASK] = data;
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      throw trap(addr, 1);
    }
  }

  @Override
  public void writeShort(int addr, short data) {
    if ((addr & COW_PAGE_MASK) > COW_PAGE_SIZE - 2) {
      writeAcross(addr, data, 2);
      return;
    }
    try {
      SHORT_HANDLE.set(writable(addr), addr & COW_PAGE_MASK, data);
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      throw trap(addr, 2);
    }
  }

  @Override
  public void writeI32(int addr, int data) {
    if ((addr & COW_PAGE_MASK) > COW_PAGE_SIZE - 4) {
      writeAcross(addr, data, 4);
      return;
    }
    try {
      INT_HANDLE.set(writable(addr), addr & COW_PAGE_MASK, data);
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      throw trap(addr, 4);
    }
  }

  @Override
  public void writeLong(int addr, long data) {
    if ((addr & COW_PAGE_MASK) > COW_PAGE_SIZE - 8) {
      writeAcross(addr, data, 8);
      return;
    }
    try {
      LONG_HANDLE.set(writable(addr), addr & COW_PAGE_MASK, data);
    } catch (IndexOutOfBoundsException | NullPointerException e) {
      throw trap(addr, 8);
    }
  }

  @Override
  public byte[] readBytes(int addr, int len) {
    bounds(addr, len);
    byte[] result = new byte[len];
    for (int done = 0; done < len;) {
      int current = addr + done;
      int length = Math.min(COW_PAGE_SIZE - (current & COW_PAGE_MASK), len - done);
      System.arraycopy(
        reads[current >>> COW_PAGE_SHIFT],
        current & COW_PAGE_MASK,
        result,
        done,
        length
      );
      done += length;
    }
    return result;
  }

  @Override
  public void write(int addr, byte[] data, int offset, int size) {
    bounds(addr, size);
    for (int done = 0; done < size;) {
      int current = addr + done;
      int length = Math.min(COW_PAGE_SIZE - (current & COW_PAGE_MASK), size - done);
      System.arraycopy(
        data,
        offset + done,
        writable(current),
        current & COW_PAGE_MASK,
        length
      );
      done += length;
    }
  }

  @Override
  public void write(int addr, byte[] data) {
    Memory.super.write(addr, data);
  }

  @Override
  public void copy(int dest, int source, int size) {
    bounds(source, size);
    bounds(dest, size);
    if (dest > source && dest - source < size) {
      for (int remaining = size; remaining > 0;) {
        int src = source + remaining;
        int dst = dest + remaining;
        int length = Math.min(
          remaining,
          Math.min(((src - 1) & COW_PAGE_MASK) + 1, ((dst - 1) & COW_PAGE_MASK) + 1)
        );
        byte[] target = writable(dst - length);
        System.arraycopy(
          reads[(src - 1) >>> COW_PAGE_SHIFT],
          (src - length) & COW_PAGE_MASK,
          target,
          (dst - length) & COW_PAGE_MASK,
          length
        );
        remaining -= length;
      }
      return;
    }
    for (int done = 0; done < size;) {
      int src = source + done;
      int dst = dest + done;
      int length = Math.min(
        size - done,
        Math.min(
          COW_PAGE_SIZE - (src & COW_PAGE_MASK),
          COW_PAGE_SIZE - (dst & COW_PAGE_MASK)
        )
      );
      byte[] target = writable(dst);
      System.arraycopy(
        reads[src >>> COW_PAGE_SHIFT],
        src & COW_PAGE_MASK,
        target,
        dst & COW_PAGE_MASK,
        length
      );
      done += length;
    }
  }

  @Override
  public long readU16(int addr) {
    return readShort(addr) & 0xFFFF;
  }

  @Override
  public void writeF32(int addr, float data) {
    writeI32(addr, Float.floatToRawIntBits(data));
  }

  @Override
  public long readF32(int addr) {
    return readInt(addr);
  }

  @Override
  public void writeF64(int addr, double data) {
    writeLong(addr, Double.doubleToRawLongBits(data));
  }

  @Override
  public long readF64(int addr) {
    return readLong(addr);
  }

  @Override
  public void drop(int segment) {
    dataSegments[segment] = PassiveDataSegment.EMPTY;
  }

  @Override
  public boolean shared() {
    return false;
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

  /** Returns the shared golden snapshot this memory reads through. */
  public Golden getGolden() {
    return golden;
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

  /** Drops all private pages and growth, restoring the pristine snapshot view. */
  public void reset() {
    totalPages = Math.max(goldenPages, limits.initialPages());
    reads = pristine;
    ownership = ZERO_OWNERSHIP;
    ownsOwnership = false;
    hiddenRestores = Map.of();
    privateReads = false;
    newPageCount = 0;
    copiedPageCount = 0;
  }

  private byte[] writable(int addr) {
    int index = addr >>> COW_PAGE_SHIFT;
    byte[] page = reads[index];
    return isOwned(index) ? page : materialize(index);
  }

  private byte[] materialize(int index) {
    byte[] page = reads[index].clone();
    ensurePrivateReads();
    reads[index] = page;
    setOwned(index);
    if (index / COW_PAGES_PER_WASM_PAGE < goldenPages) {
      copiedPageCount++;
    } else {
      newPageCount++;
    }
    return page;
  }

  private long readAcross(int addr, int width) {
    bounds(addr, width);
    long value = 0;
    for (int offset = 0; offset < width; offset++) {
      value |= (read(addr + offset) & 255L) << (offset * 8);
    }
    return value;
  }

  private void writeAcross(int addr, long data, int width) {
    bounds(addr, width);
    for (int offset = 0; offset < width; offset++) {
      writeByte(addr + offset, (byte) (data >>> (offset * 8)));
    }
  }

  private int chunkCount(int pages) {
    return pages * COW_PAGES_PER_WASM_PAGE;
  }

  private int visibleChunkCount() {
    return Math.max(sizeInBytes(), 0) >>> COW_PAGE_SHIFT;
  }

  private void ensureCapacity(int required) {
    if (required <= reads.length) {
      return;
    }
    int capacity = Math.min(
      chunkCount(maximumPages()),
      Math.max(required, reads.length * 2)
    );
    reads = Arrays.copyOf(reads, capacity);
    privateReads = true;
    ownership =
      ownsOwnership ? Arrays.copyOf(ownership, ownershipWords(capacity)) : ZERO_OWNERSHIP;
  }

  private void initializeReads(int from, int to) {
    if (hiddenRestores.isEmpty()) {
      int goldenEnd = min(
        to,
        (int) (((long) golden.byteLength + COW_PAGE_MASK) >>> COW_PAGE_SHIFT)
      );
      for (int index = from; index < goldenEnd; index++) {
        reads[index] = golden.chunk(index);
      }
      for (int index = Math.max(from, goldenEnd); index < to; index++) {
        reads[index] = golden.zero;
      }
      return;
    }
    for (int index = from; index < to; index++) {
      byte[] restored = hiddenRestores.isEmpty() ? null : hiddenRestores.remove(index);
      reads[index] = restored == null ? golden.chunk(index) : restored;
      if (restored != null) {
        setOwned(index);
      }
    }
    if (hiddenRestores.isEmpty()) {
      hiddenRestores = Map.of();
    }
  }

  private void ensurePrivateReads() {
    if (!privateReads) {
      reads = reads.clone();
      privateReads = true;
    }
  }

  private static int ownershipWords(int capacity) {
    return (capacity + Long.SIZE - 1) >>> 6;
  }

  private boolean isOwned(int index) {
    return (ownership[index >>> 6] & (1L << (index & 63))) != 0;
  }

  private void setOwned(int index) {
    if (!ownsOwnership) {
      ownership = new long[ownershipWords(reads.length)];
      ownsOwnership = true;
    }
    ownership[index >>> 6] |= 1L << (index & 63);
  }

  private void bounds(int addr, int size) {
    if (addr < 0 || size < 0 || (long) addr + size > sizeInBytes()) {
      throw trap(addr, size);
    }
  }

  private WasmRuntimeException trap(int addr, int size) {
    return new WasmRuntimeException(
      "out of bounds memory access: attempted to access address: " +
      addr +
      " with size: " +
      size +
      " but memory size is: " +
      sizeInBytes()
    );
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
}

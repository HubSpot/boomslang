package com.hubspot.boomslang;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

class ResettableByteArrayInputStream extends InputStream {

  private ByteArrayInputStream delegate;

  ResettableByteArrayInputStream() {
    this.delegate = new ByteArrayInputStream(new byte[0]);
  }

  void resetData(byte[] data) {
    this.delegate = new ByteArrayInputStream(data);
  }

  void clear() {
    this.delegate = new ByteArrayInputStream(new byte[0]);
  }

  @Override
  public int read() {
    return delegate.read();
  }

  @Override
  public int read(byte[] b, int off, int len) {
    return delegate.read(b, off, len);
  }

  @Override
  public int available() {
    return delegate.available();
  }
}

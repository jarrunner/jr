package org.teavm.interop;

public final class Address {
    public native Address add(int offset);
    public native Address add(long offset);
    public native int toInt();
    public native long toLong();
    public native boolean isNull();
    public native byte getByte();
    public native void putByte(byte b);
    public native char getChar();
    public native void putChar(char c);
    public native int getInt();
    public native void putInt(int i);
    public native long getLong();
    public native void putLong(long l);
    public native Address getAddress();
    public native void putAddress(Address a);
    public static native Address fromInt(int i);
    public static native Address fromLong(long l);
    public static native Address ofObject(Object o);
    public static native Address ofData(byte[] a);
    public static native Address ofData(int[] a);
    public static native int sizeOf();
    public static native void fillZero(Address a, int n);
}

package wintype;

/**
 * POC stand-in for org.teavm.interop.Address - kept local so this proof-of-concept compiles with
 * plain javac and no TeaVM dependency. The real WinApi.java bindings use the real Address class;
 * the classification logic below only cares that this is a reference (pointer-shaped) Java type,
 * exactly like the real one.
 */
public final class Address {
    private Address() {}
}

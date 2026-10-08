// expect: NC1-field NC1-field NC1-field NC1-field
package t;

import org.teavm.interop.Address;

class Field {
    static Address saved;
    Address instance;
    static final Address computed = N.alloc(4);
    record Entry(Address data, int size) {}
}

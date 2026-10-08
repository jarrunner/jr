// expect: NC2-ofData NC2-ofObject NC2-array NC2-array
package t;

import org.teavm.interop.Address;

class Banned {
    static Address[] table;

    static void run() {
        var bytes = new byte[4];
        N.os(Address.ofData(bytes));
        N.os(Address.ofObject(new Object()));
        Address[] two = { N.NULL, N.NULL };
    }
}

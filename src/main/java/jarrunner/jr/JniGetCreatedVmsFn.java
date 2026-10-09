package jarrunner.jr;

import org.teavm.interop.Address;
import org.teavm.interop.Function;

/** jvm.dll's {@code jint JNI_GetCreatedJavaVMs(JavaVM **vmBuf, jsize bufLen, jsize *nVMs)}, found at run time.
 *  PRP-31: HotSpot reports 0 VMs after a failed JNI_CreateJavaVM and 1 while a created VM runs System.exit,
 *  which tells a JVM that never started from an app that chose to exit. */
public abstract class JniGetCreatedVmsFn extends Function {
    public abstract int invoke(Address vmBuf, int bufLen, Address nVMs);
}

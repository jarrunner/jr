package wintype;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which real Windows C type a native-binding parameter (or field) actually corresponds
 * to, so {@link WinTypeProcessor} can ask the REAL compiler whether that type is pointer-shaped
 * or integer-shaped - and fail the build if the Java side disagrees - instead of a human guessing
 * from the typedef's name. See ../../prp/11-prp.02.codegen-safety-poc.md.
 *
 * This is the POC answer to PRP-09's WPARAM/LPARAM/DWORD_PTR bug: those are integer typedefs
 * despite reading like pointer-sized handles, and hand-declaring them as a pointer type compiled
 * fine right up until the real header was actually in scope.
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.PARAMETER, ElementType.FIELD})
public @interface WinType {
    /** The real C type/typedef name, e.g. "WPARAM", "HWND", "DWORD". */
    String value();

    /** The header that declares it - probed via the real llvm-mingw clang, not memory. */
    String header() default "windows.h";
}

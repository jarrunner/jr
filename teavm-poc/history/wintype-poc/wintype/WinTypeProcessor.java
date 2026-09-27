package wintype;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Fails the BUILD (not a runtime check, not a lint report to read later) when a hand-written
 * native-binding parameter's Java type disagrees with what the real header says the underlying
 * C type actually is. This is the direct answer to "why can't an annotation processor catch this
 * class of bug" - see ../../prp/11-prp.02.codegen-safety-poc.md for the two real PRP-09 bugs this
 * targets and the demo/ files that prove it against both a wrong and a corrected binding.
 */
@SupportedAnnotationTypes("wintype.WinType")
public class WinTypeProcessor extends AbstractProcessor {

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends javax.lang.model.element.TypeElement> annotations, RoundEnvironment roundEnv) {
        for (var element : roundEnv.getElementsAnnotatedWith(WinType.class)) {
            check(element);
        }
        return true;
    }

    private void check(Element element) {
        var winType = element.getAnnotation(WinType.class);
        var cTypeName = winType.value();
        var header = winType.header();

        HeaderProbe.TypeClassification real;
        try {
            real = HeaderProbe.classify(cTypeName, header);
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@WinType(\"" + cTypeName + "\"): could not verify against the real compiler - " + e.getMessage(),
                    element);
            return;
        }

        if (real.kind() == HeaderProbe.Kind.UNKNOWN) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@WinType(\"" + cTypeName + "\"): the real compiler classified this as neither "
                            + "pointer-shaped nor integer-shaped (size=" + real.sizeBytes() + ") - "
                            + "this needs a human to look at it, not a guess either way.",
                    element);
            return;
        }

        var javaKind = classifyJavaType(element.asType());
        var expectedJavaKind = real.kind() == HeaderProbe.Kind.POINTER ? "Address" : "long/int";

        var mismatch = switch (real.kind()) {
            case POINTER -> javaKind != JavaKind.REFERENCE;
            case INTEGER -> javaKind != JavaKind.PRIMITIVE_INTEGER
                    || (real.sizeBytes() == 8 && element.asType().getKind() == TypeKind.INT);
            case UNKNOWN -> true;
        };

        if (mismatch) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@WinType(\"" + cTypeName + "\", header=\"" + header + "\"): the REAL header classifies this as "
                            + real.kind() + " (" + real.sizeBytes() + " bytes) but the Java parameter is declared `"
                            + element.asType() + "` - expected " + expectedJavaKind
                            + (real.kind() == HeaderProbe.Kind.INTEGER && real.sizeBytes() == 8 ? " (specifically `long`, not `int` - it's 8 bytes)" : "")
                            + ". This is exactly PRP-09's WPARAM/LPARAM bug, caught at compile time instead of at a WinAPI call site.",
                    element);
        }
    }

    private enum JavaKind { REFERENCE, PRIMITIVE_INTEGER, OTHER }

    private JavaKind classifyJavaType(TypeMirror type) {
        return switch (type.getKind()) {
            case LONG, INT -> JavaKind.PRIMITIVE_INTEGER;
            case DECLARED -> JavaKind.REFERENCE;
            default -> JavaKind.OTHER;
        };
    }
}

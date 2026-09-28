package littlejlib.jr;

/** Unwinds a resource-editing/signing operation to ReRun's single failure-reporting point, carrying
 *  the same message resedit.c would have written into its err[] buffer at the equivalent
 *  goto fail/goto cleanup - the Java idiom for what is, in the C original, "abort and report". */
public final class ReError extends RuntimeException {
    public ReError(String message) {
        super(message, null, false, false); // no stack trace / suppression bookkeeping needed
    }
}

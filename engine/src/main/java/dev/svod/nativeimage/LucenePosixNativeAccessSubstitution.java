package dev.svod.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.lang.invoke.MethodHandle;

/**
 * GraalVM native-image substitution for Lucene 9.12's JDK-21 multi-release
 * {@code org.apache.lucene.store.PosixNativeAccess}.
 *
 * <p>That class's {@code <clinit>} calls {@code lookupMadvise()}, which resolves the
 * {@code posix_madvise} symbol through the Foreign Function & Memory API
 * ({@code Linker.nativeLinker().defaultLookup().find(...)}). On GraalVM CE 21 the FFM
 * downcall path is not supported (the {@code -H:+ForeignAPISupport} option does not exist
 * on this builder), so native-image routes the lookup through the {@code @Delete}'d
 * {@code jdk.internal.loader.NativeLibrary.findEntry0} and aborts the closed-world analysis
 * with "Unsupported method ... findEntry0 is reachable", reached via
 * {@code PosixNativeAccess.<clinit>}.
 *
 * <p>By substituting {@code lookupMadvise()} to throw {@link UnsupportedOperationException},
 * the findEntry0 path becomes unreachable. The real {@code <clinit>} already catches
 * {@code UnsupportedOperationException} and degrades to no native access — Lucene simply
 * skips the {@code posix_madvise} readahead hint, which is a performance nicety, not a
 * correctness requirement. The JVM build is unaffected (substitutions apply to native-image
 * only).
 */
@TargetClass(className = "org.apache.lucene.store.PosixNativeAccess")
final class LucenePosixNativeAccessSubstitution {

    @Substitute
    private static MethodHandle lookupMadvise() {
        throw new UnsupportedOperationException(
                "posix_madvise lookup disabled in GraalVM native-image (FFM unsupported on GraalVM 21)");
    }
}

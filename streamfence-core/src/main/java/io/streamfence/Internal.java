package io.streamfence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker for packages and types that are <strong>not</strong> part of the
 * public {@code wsserver} API. Anything annotated with {@code @Internal} — or
 * living under a package that is annotated with it — may change or be removed
 * in any release without notice. Consumers must not depend on it.
 *
 * <p>The public API surface lives in {@link io.streamfence io.wsserver}. Every
 * {@code io.wsserver.internal.*} package carries a package-level
 * {@code @Internal} banner.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PACKAGE)
public @interface Internal {
}

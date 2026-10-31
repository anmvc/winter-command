package com.thewinterframework.command;

import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.kotlin.coroutines.annotations.KotlinAnnotatedMethodsKt;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Direct-reference bridge to {@code org.incendo:cloud-kotlin-coroutines-annotations}.
 * Loaded only when {@link KotlinRuntime#HAS_COROUTINE_ANNOTATIONS} is {@code true}.
 */
final class KotlinAnnotationsBridge {
    private static final String COROUTINES_ANNOTATIONS_PACKAGE =
            "org.incendo.cloud.kotlin.coroutines.annotations";

    private static final Map<AnnotationParser<?>, Boolean> INSTALLED_PARSERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private KotlinAnnotationsBridge() {
    }

    static boolean isCoroutineSupportInstalled(final AnnotationParser<?> annotationParser) {
        if (INSTALLED_PARSERS.containsKey(annotationParser)) {
            return true;
        }
        final var suggestionFactory = annotationParser.suggestionProviderFactory();
        return suggestionFactory != null
                && COROUTINES_ANNOTATIONS_PACKAGE.equals(suggestionFactory.getClass().getPackageName());
    }

    static <C> void installCoroutineSupport(
            final AnnotationParser<C> annotationParser,
            final Supplier<KotlinCoroutinesBridge.CoroutineEnvironment> environmentSupplier
    ) {
        if (isCoroutineSupportInstalled(annotationParser)) {
            return;
        }
        if (annotationParser.manager() == null
                || annotationParser.manager().parameterInjectorRegistry() == null) {
            return;
        }
        final var env = environmentSupplier.get();
        KotlinAnnotatedMethodsKt.installCoroutineSupport(
                annotationParser,
                env.scope(),
                env.context(),
                false
        );
        INSTALLED_PARSERS.put(annotationParser, Boolean.TRUE);
    }
}

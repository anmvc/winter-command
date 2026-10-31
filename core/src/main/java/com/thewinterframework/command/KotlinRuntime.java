package com.thewinterframework.command;

/**
 * Reflection-free runtime capability probe for optional {@code cloud-kotlin} modules on the
 * module's defining {@link ClassLoader}.
 */
final class KotlinRuntime {
    private static final String[] COROUTINES_RESOURCES = {
            "org/incendo/cloud/kotlin/coroutines/SuspendingArgumentParser.class",
            "org/incendo/cloud/kotlin/coroutines/SuspendingSuggestionProvider.class",
            "org/incendo/cloud/kotlin/coroutines/SuspendingExecutionHandler.class",
            "kotlinx/coroutines/CoroutineScope.class",
            "kotlinx/coroutines/GlobalScope.class",
            "kotlin/coroutines/CoroutineContext.class",
            "kotlin/coroutines/EmptyCoroutineContext.class"
    };

    private static final String[] COROUTINE_ANNOTATIONS_RESOURCES = {
            "org/incendo/cloud/kotlin/coroutines/annotations/KotlinAnnotatedMethodsKt.class",
            "kotlin/reflect/jvm/ReflectJvmMapping.class"
    };

    static final boolean HAS_COROUTINES = hasAllResources(
            KotlinRuntime.class.getClassLoader(),
            COROUTINES_RESOURCES
    );

    static final boolean HAS_COROUTINE_ANNOTATIONS = HAS_COROUTINES && hasAllResources(
            KotlinRuntime.class.getClassLoader(),
            COROUTINE_ANNOTATIONS_RESOURCES
    );

    private KotlinRuntime() {
    }

    private static boolean hasAllResources(final ClassLoader loader, final String[] resources) {
        for (final String resource : resources) {
            final var found = loader != null
                    ? loader.getResource(resource)
                    : ClassLoader.getSystemResource(resource);
            if (found == null) {
                return false;
            }
        }
        return true;
    }
}

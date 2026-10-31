package com.thewinterframework.command.processor;

import com.thewinterframework.processor.wire.ClassListWire;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Compile-time generated wire contract for
 * {@link com.thewinterframework.command.CommandComponent} classes, providing direct singleton
 * suppliers, static injection targets, and suspending command method metadata without runtime
 * reflection.
 */
public interface CommandComponentWire extends ClassListWire {
    /**
     * Returns direct singleton suppliers for components that expose a static singleton instance
     * (such as Kotlin {@code object} and {@code companion object} declarations).
     * @return map of component class to direct singleton supplier
     */
    default Map<Class<?>, Supplier<?>> singletonProviders() {
        return Map.of();
    }

    /**
     * Returns the static member injection owner class for each singleton component (for example,
     * the Kotlin {@code object} class itself or the enclosing host class of a {@code companion object}).
     * @return map of component class to static injection target class
     */
    default Map<Class<?>, Class<?>> staticInjectionTargets() {
        return Map.of();
    }

    /**
     * Returns components declaring or inheriting at least one {@code suspend} Cloud-annotated method,
     * mapped to a diagnostic {@code ClassName#methodName} identifier.
     * @return map of component class to suspending method identifier
     */
    default Map<Class<?>, String> suspendingCommandMethods() {
        return Map.of();
    }
}

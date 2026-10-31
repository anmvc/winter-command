package com.thewinterframework.command;

import com.google.inject.ScopeAnnotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a command, parser, injector, configuration, decorator, or sender mapper as a Winter component.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ScopeAnnotation
public @interface CommandComponent {
}

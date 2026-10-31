package com.thewinterframework.command.paper.processor;

import com.google.auto.service.AutoService;
import com.thewinterframework.command.processor.AbstractCommandComponentProcessor;
import com.thewinterframework.processor.handler.WinterAnnotationProcessor;

/** Generates the Paper command module and component list. */
@AutoService(WinterAnnotationProcessor.class)
public final class CommandComponentProcessor extends AbstractCommandComponentProcessor {
    private static final String MODULE_CLASS = "com.thewinterframework.command.paper.PaperCommandModule";

    @Override
    protected String moduleClassName() {
        return MODULE_CLASS;
    }
}

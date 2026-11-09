package org.apache.camel.kit.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.kit.config.AgentDescriptor;
import org.apache.camel.kit.config.AgentRegistry;
import org.apache.camel.kit.util.TemplateUtils;

class DispatchBlockAppender {

    void append(Path skillMdFile, String agentName) throws IOException {
        AgentDescriptor descriptor = AgentRegistry.descriptor(agentName);
        if (descriptor == null) {
            return;
        }
        String dispatchTemplatePath = descriptor.dispatchTemplatePath();
        String dispatchBlock = TemplateUtils.readTemplate(dispatchTemplatePath);
        String existing = Files.readString(skillMdFile);
        AtomicFileWriter.write(skillMdFile, existing + "\n---\n\n" + dispatchBlock);
    }
}

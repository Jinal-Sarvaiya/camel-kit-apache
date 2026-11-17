package org.apache.camel.kit.command;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.aesh.command.converter.Converter;
import org.aesh.command.converter.ConverterInvocation;
import org.aesh.command.validator.OptionValidatorException;

/** Keep paths relative: the owning service resolves them against its selected project. */
public final class PathConverter implements Converter<Path, ConverterInvocation> {
    @Override
    public Path convert(ConverterInvocation invocation) throws OptionValidatorException {
        try {
            return Path.of(invocation.getInput());
        } catch (InvalidPathException e) {
            throw new OptionValidatorException(e.getMessage());
        }
    }
}

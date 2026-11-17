package org.apache.camel.kit.command;

import org.aesh.command.impl.internal.ProcessedOption;
import org.aesh.command.parser.OptionParser;
import org.aesh.command.parser.OptionParserException;
import org.aesh.parser.ParsedLineIterator;

/** Keeps Kit's scalar strictness and token-based collection arity on both Aesh entry points. */
public class KitOptionParser implements OptionParser {
    @Override
    public void parse(ParsedLineIterator iterator, ProcessedOption option) throws OptionParserException {
        String token = iterator.pollWord();
        if (!option.hasMultipleValues() && !option.getValues().isEmpty()) {
            throw new OptionParserException("Option --" + option.name() + " may only be specified once");
        }
        String attached = token.substring(option.isLongNameUsed() ? option.name().length() + 2 : 2);
        if (!option.hasValue()) {
            String value = attached.isEmpty() ? "true" : attached.startsWith("=") ? attached.substring(1) : attached;
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                throw new OptionParserException("Option --" + option.name() + " requires true or false");
            }
            option.addValue(value);
            return;
        }
        if (!attached.isEmpty()) {
            option.addValue(attached.startsWith("=") ? attached.substring(1) : attached);
        } else {
            if (!hasValue(iterator, option)) {
                throw new OptionParserException("Option --" + option.name() + " requires a value");
            }
            option.addValue(iterator.pollWord());
        }
        if (option.hasMultipleValues() && option.getValueSeparator() == ' ') {
            while (hasValue(iterator, option)) {
                option.addValue(iterator.pollWord());
            }
        }
    }

    private static boolean hasValue(ParsedLineIterator iterator, ProcessedOption option) {
        return iterator.hasNextWord() && !"--".equals(iterator.peekWord())
                && option.parent().searchAllOptions(iterator.peekWord()) == null;
    }
}

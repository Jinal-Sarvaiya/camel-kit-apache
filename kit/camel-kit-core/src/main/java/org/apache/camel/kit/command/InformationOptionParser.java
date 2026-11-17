package org.apache.camel.kit.command;

import org.aesh.command.impl.internal.ProcessedOption;
import org.aesh.command.parser.OptionParserException;
import org.aesh.parser.ParsedLineIterator;

/** Informational flags act on presence, including an explicit false, without discarding later validation. */
public final class InformationOptionParser extends KitOptionParser {
    @Override
    public void parse(ParsedLineIterator iterator, ProcessedOption option) throws OptionParserException {
        String token = iterator.peekWord();
        if (token.equals("-hV") || token.equals("-Vh")) {
            iterator.pollWord();
            for (char name : token.substring(1).toCharArray()) {
                ProcessedOption flag = option.parent().findOption(String.valueOf(name));
                if (flag == null || !flag.getValues().isEmpty()) {
                    throw new MalformedOptionException("Invalid or repeated option -" + name);
                }
                flag.addValue("true");
            }
        } else {
            super.parse(iterator, option);
            option.getValues().set(0, "true");
        }
    }
}

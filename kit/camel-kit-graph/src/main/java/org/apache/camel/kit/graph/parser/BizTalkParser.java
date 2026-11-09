package org.apache.camel.kit.graph.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.camel.kit.graph.ProjectGraph;
import org.apache.camel.kit.graph.parser.biztalk.*;

/**
 * Parser for Microsoft BizTalk Server artifacts.
 * <p>
 * This parser detects and dispatches BizTalk file types to specialized sub-parsers:
 * <ul>
 * <li>.odx files (orchestrations) → {@link BizTalkOdxParser}</li>
 * <li>.btm files (maps) → {@link BizTalkBtmParser}</li>
 * <li>.btp files (pipelines) → {@link BizTalkBtpParser}</li>
 * <li>.xml files (binding files) → {@link BizTalkBindingParser}</li>
 * </ul>
 * </p>
 */
public class BizTalkParser implements GraphParser {

    private static final int SNIFF_BYTES = 1024;
    private static final String BIZTALK_BINDING_MARKER = "schemas.microsoft.com/BizTalk";
    private static final String BINDING_INFO_MARKER = "BindingInfo";

    private final List<String> warnings = new ArrayList<>();
    private final BizTalkOdxParser odxParser = new BizTalkOdxParser(warnings::add);
    private final BizTalkBtmParser btmParser = new BizTalkBtmParser(warnings::add);
    private final BizTalkBtpParser btpParser = new BizTalkBtpParser(warnings::add);
    private final BizTalkBindingParser bindingParser = new BizTalkBindingParser(warnings::add);

    @Override
    public void parse(Path projectRoot, ProjectGraph graph) {
        warnings.clear();
        parseFiles(projectRoot, scannedFilePaths(projectRoot, GraphParser.projectFiles(projectRoot)), graph);
    }

    @Override
    public void parseFiles(Path projectRoot, List<Path> files, ProjectGraph graph) {
        GraphParser.requireSafeFiles(projectRoot, files);
        warnings.clear();
        files.forEach(file -> parseArtifact(projectRoot, file, graph));
    }

    @Override
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    @Override
    public List<String> scannedFiles(Path projectRoot) {
        return GraphParser.relativeFileNames(projectRoot,
                scannedFilePaths(projectRoot, GraphParser.projectFiles(projectRoot)));
    }

    @Override
    public List<Path> scannedFilePaths(Path projectRoot, List<Path> projectFiles) {
        return GraphParser.findFilePaths(projectRoot, projectFiles, this::isCandidateName).stream()
                .filter(this::isBizTalkArtifact).toList();
    }

    private void parseArtifact(Path projectRoot, Path file, ProjectGraph graph) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (fileName.endsWith(".odx")) {
            odxParser.parse(file, graph);
        } else if (fileName.endsWith(".btm")) {
            btmParser.parse(projectRoot, file, graph);
        } else if (fileName.endsWith(".btp")) {
            btpParser.parse(projectRoot, file, graph);
        } else if (fileName.endsWith(".xml") && !fileName.equals("pom.xml")) {
            bindingParser.parse(file, graph);
        }
    }

    private boolean isCandidateName(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".odx") || name.endsWith(".btm") || name.endsWith(".btp")
                || (name.endsWith(".xml") && !name.equals("pom.xml"));
    }

    private boolean isBizTalkArtifact(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return !fileName.endsWith(".xml") || isBizTalkBindingXml(file);
    }

    /**
     * Check if an XML file is a BizTalk binding file by sniffing the first 1KB of content.
     *
     * @param  file the XML file to check
     * @return      true if the file contains BizTalk binding markers
     */
    private boolean isBizTalkBindingXml(Path file) {
        try (InputStream is = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buf = new byte[SNIFF_BYTES];
            int read = is.read(buf);
            if (read <= 0) {
                return false;
            }
            // BizTalk binding files may be UTF-8 or UTF-16 (BizTalk Admin Console exports UTF-16)
            for (Charset cs : new Charset[]{
                    StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
                String head = new String(buf, 0, read, cs);
                if (head.contains(BIZTALK_BINDING_MARKER) && head.contains(BINDING_INFO_MARKER)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Check if XML content (already read) is a BizTalk XML file.
     * <p>
     * This method is used by {@link XmlRouteParser} to skip BizTalk XML files.
     * </p>
     *
     * @param  head the first portion of the XML file content
     * @return      true if the content contains BizTalk markers
     */
    static boolean isBizTalkXml(String head) {
        return head.contains("schemas.microsoft.com/BizTalk")
                || head.contains("<mapsource")
                || (head.contains("<Document") && head.contains("PolicyFilePath"));
    }
}

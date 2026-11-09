import java.nio.file.Path;
import org.apache.camel.kit.knowledge.schema.KnowledgeDocument;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

public class FixtureIndex {
    public static void main(String[] args) throws Exception {
        try (var directory = FSDirectory.open(Path.of(args[0]));
             var writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
            writer.addDocument(new KnowledgeDocument("runtime-canary", "apache_camel")
                    .source("local-canary").docType("guide").sourceVersion("4.22")
                    .sectionTitle("Apache Camel timer component")
                    .content("The Apache Camel timer component generates periodic messages. "
                            + "Use a timer endpoint to trigger a route at regular intervals. "
                            + "The period option configures the interval between generated messages.")
                    .build());
        }
    }
}

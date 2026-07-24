package ae.gov.dubaicustoms.platform.docs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Renders {@code docs/reference/bom.md} — the list of platform artifacts a consumer gets by importing
 * {@code platform-bom} — straight from that BOM's {@code dependencyManagement}. Keeps the published
 * artifact list in the docs honest against the actual BOM (the {@code check-bom} gate keeps the BOM
 * honest against the reactor).
 */
final class BomReferenceGenerator {

    private BomReferenceGenerator() {
    }

    record Artifact(String groupId, String artifactId, String version) {
    }

    static Path bomSource() {
        return PlatformDocs.repoRoot().resolve("build/platform-bom/pom.xml");
    }

    /** Regenerates {@code docs/reference/bom.md}; returns the written path. */
    static Path generate() {
        List<Artifact> artifacts = parse();
        StringBuilder md = new StringBuilder();
        md.append("# Platform BOM\n\n");
        md.append("> Generated from `build/platform-bom/pom.xml` at build time. Do not edit by hand.\n\n");
        md.append("Import one BOM to align every platform artifact to a single release-train version ");
        md.append("(ADR-005). Then add only the starters you need — a capability you don't add costs ");
        md.append("you nothing.\n\n");
        md.append("```xml\n");
        md.append("<dependencyManagement>\n");
        md.append("  <dependencies>\n");
        md.append("    <dependency>\n");
        md.append("      <groupId>ae.gov.dubaicustoms.platform</groupId>\n");
        md.append("      <artifactId>platform-bom</artifactId>\n");
        md.append("      <version>${platform.version}</version>\n");
        md.append("      <type>pom</type>\n");
        md.append("      <scope>import</scope>\n");
        md.append("    </dependency>\n");
        md.append("  </dependencies>\n");
        md.append("</dependencyManagement>\n");
        md.append("```\n\n");
        md.append("## Managed artifacts\n\n");
        md.append("| Artifact | Version |\n");
        md.append("|----------|---------|\n");
        for (Artifact a : artifacts) {
            md.append("| `").append(a.groupId()).append(':').append(a.artifactId()).append("` | `")
                    .append(a.version()).append("` |\n");
        }

        Path out = PlatformDocs.docsRoot().resolve("reference/bom.md");
        PlatformDocs.writeString(out, md.toString());
        return out;
    }

    private static List<Artifact> parse() {
        List<Artifact> artifacts = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(bomSource().toFile());
            NodeList dmList = doc.getElementsByTagName("dependencyManagement");
            if (dmList.getLength() == 0) {
                return artifacts;
            }
            Element dm = (Element) dmList.item(0);
            NodeList deps = dm.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                Element dep = (Element) deps.item(i);
                artifacts.add(new Artifact(
                        text(dep, "groupId"), text(dep, "artifactId"), text(dep, "version")));
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse platform-bom at " + bomSource(), e);
        }
        return artifacts;
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            if (node.getParentNode() == parent) {
                return node.getTextContent().trim();
            }
        }
        return "";
    }
}

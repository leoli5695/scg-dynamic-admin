package com.leoli.gateway.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The admin image builds from the repository root and copies each child POM before any source.
 * Maven validates the aggregator's default {@code <modules>} list up front, so a module added to
 * pom.xml without a matching COPY breaks the image with "Child module ... does not exist" - which
 * is exactly how gateway-moderation-spi broke it. Profile-only modules are exempt: profiles are
 * evaluated before module directories are checked (measured - hiding seckill-core-engine still
 * builds). This test is the guard that keeps the two lists in sync.
 */
class DockerfileModuleCoverageTest {

    private static final Pattern MODULE_POM_COPY = Pattern.compile("(?m)^COPY\\s+(\\S+)/pom\\.xml\\s");

    private static Path repositoryRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    && Files.isRegularFile(dir.resolve("gateway-admin/Dockerfile"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("Could not locate the repository root from " + System.getProperty("user.dir"));
    }

    private static boolean insideProfile(Node node) {
        for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (parent instanceof Element element && "profile".equals(element.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private record Modules(Set<String> defaultModules, Set<String> allModules) {
    }

    private static Modules readModules(Path root) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document pom = factory.newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        NodeList nodes = pom.getElementsByTagNameNS("*", "module");
        Set<String> defaults = new LinkedHashSet<>();
        Set<String> all = new LinkedHashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            String module = node.getTextContent().trim();
            all.add(module);
            if (!insideProfile(node)) {
                defaults.add(module);
            }
        }
        return new Modules(defaults, all);
    }

    private static Set<String> copiedModules(String dockerfile) {
        Set<String> copied = new LinkedHashSet<>();
        Matcher matcher = MODULE_POM_COPY.matcher(dockerfile);
        while (matcher.find()) {
            copied.add(matcher.group(1));
        }
        return copied;
    }

    @Test
    @DisplayName("every default-profile module in pom.xml has a COPY in the admin Dockerfile")
    void everyDefaultModulePomIsCopied() throws Exception {
        Path root = repositoryRoot();
        Modules modules = readModules(root);
        String dockerfile = Files.readString(root.resolve("gateway-admin/Dockerfile"));
        Set<String> copied = copiedModules(dockerfile);

        List<String> missing = new ArrayList<>(modules.defaultModules());
        missing.removeAll(copied);
        assertTrue(missing.isEmpty(), () -> "gateway-admin/Dockerfile is missing COPY lines for " + missing
                + ". Add `COPY <module>/pom.xml ./<module>/` next to the other module POMs; Maven fails the"
                + " image build with \"Child module ... does not exist\" without it.");
    }

    @Test
    @DisplayName("the Dockerfile copies no module that pom.xml no longer declares")
    void copiedModulesStillExist() throws Exception {
        Path root = repositoryRoot();
        Modules modules = readModules(root);
        Set<String> copied = copiedModules(Files.readString(root.resolve("gateway-admin/Dockerfile")));

        List<String> stale = new ArrayList<>(copied);
        stale.removeAll(modules.allModules());
        assertTrue(stale.isEmpty(), () -> "gateway-admin/Dockerfile copies POMs for modules absent from pom.xml: "
                + stale + ". A renamed or deleted module leaves a COPY that fails the build context.");
    }

    @Test
    @DisplayName("the aggregator POM itself is copied, so the child POMs have a reachable parent")
    void aggregatorPomIsCopied() throws Exception {
        String dockerfile = Files.readString(repositoryRoot().resolve("gateway-admin/Dockerfile"));
        assertTrue(Pattern.compile("(?m)^COPY\\s+pom\\.xml\\s").matcher(dockerfile).find(),
                "gateway-admin/Dockerfile must COPY the root pom.xml; without it every child POM"
                        + " fails with \"Non-resolvable parent POM\".");
    }
}
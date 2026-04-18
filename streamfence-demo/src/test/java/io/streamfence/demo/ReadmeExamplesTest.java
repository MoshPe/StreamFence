package io.streamfence.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadmeExamplesTest {

    @Test
    void readmeLinksToRealExampleSourcesAndFixtures() throws Exception {
        Path repoRoot = findRepoRoot();
        String readme = Files.readString(repoRoot.resolve("README.md"));

        assertReadmeLinkTargetsExistingFile(readme, repoRoot,
                "streamfence-demo/src/main/java/io/streamfence/demo/examples/SingleServerExample.java");
        assertReadmeLinkTargetsExistingFile(readme, repoRoot,
                "streamfence-demo/src/main/java/io/streamfence/demo/examples/MultiNamespaceExample.java");
        assertReadmeLinkTargetsExistingFile(readme, repoRoot,
                "streamfence-demo/src/main/java/io/streamfence/demo/examples/MixedWorkloadExample.java");
        assertReadmeLinkTargetsExistingFile(readme, repoRoot,
                "streamfence-demo/src/test/java/io/streamfence/demo/examples/ExamplesSmokeTest.java");
        assertReadmeLinkTargetsExistingFile(readme, repoRoot,
                "streamfence-demo/src/main/resources/examples/mixed-workload-feed.yaml");
        assertReadmeLinkTargetsExistingFile(readme, repoRoot,
                "streamfence-demo/src/main/resources/examples/mixed-workload-control.yaml");
    }

    @Test
    void readmeFollowsTheJsSectionFlowWithJavaSpecificDemoSection() throws Exception {
        Path repoRoot = findRepoRoot();
        String readme = Files.readString(repoRoot.resolve("README.md"));

        assertHeadingsAppearInOrder(readme, List.of(
                "## What it is",
                "## When to use one server vs two",
                "## Install",
                "## Quick start",
                "## Client-side protocol",
                "## Config file loading",
                "## Delivery modes",
                "## Overflow policies",
                "## Spill to disk",
                "## Authentication",
                "## TLS",
                "## Metrics & management",
                "## Event listeners",
                "## Server API reference",
                "## NamespaceSpec builder",
                "## API reference — public types",
                "## Examples",
                "## Demo",
                "## Status / roadmap",
                "## License"));
        assertThat(readme).doesNotContain("## Building");
    }

    @Test
    void readmeDocumentsJavaInstallAndQuickStart() throws Exception {
        Path repoRoot = findRepoRoot();
        String readme = Files.readString(repoRoot.resolve("README.md"));

        assertThat(readme).contains("<artifactId>streamfence-core</artifactId>");
        assertThat(readme).contains("<version>1.0.1</version>");
        assertThat(readme).contains("Requires Java 25.");
        assertThat(readme).contains("NamespaceSpec.builder(\"/feed\")");
        assertThat(readme).contains("server.publish(\"/feed\", \"snapshot\"");
        assertThat(readme).contains("server.publishTo(\"/feed\", \"client-session-id\", \"snapshot\"");
    }

    @Test
    void readmeDocumentsRunnableDemoAndRepositoryCommandsBackedByTheRepo() throws Exception {
        Path repoRoot = findRepoRoot();
        String readme = Files.readString(repoRoot.resolve("README.md"));
        String demoPom = Files.readString(repoRoot.resolve("streamfence-demo").resolve("pom.xml"));
        String rootPom = Files.readString(repoRoot.resolve("pom.xml"));
        String corePom = Files.readString(repoRoot.resolve("streamfence-core").resolve("pom.xml"));

        assertThat(readme).contains("mvn -pl streamfence-demo exec:java");
        assertThat(readme).contains(
                "mvn -pl streamfence-demo exec:java -Dexec.args=\"--server-port=9192 --management-port=9193 --console-port=9194\"");
        assertThat(readme).contains("mvn --no-transfer-progress clean verify -Dgpg.skip=true");
        assertThat(demoPom).contains("<artifactId>exec-maven-plugin</artifactId>");
        assertThat(demoPom).contains("<mainClass>io.streamfence.demo.Main</mainClass>");
        assertThat(rootPom).contains("<module>streamfence-core</module>");
        assertThat(rootPom).contains("<module>streamfence-demo</module>");
        assertThat(corePom).contains("<artifactId>jacoco-maven-plugin</artifactId>");
        assertThat(corePom).contains("<goal>check</goal>");
        assertThat(Class.forName("io.streamfence.demo.Main")).isNotNull();
        assertThat(Class.forName("io.streamfence.demo.DemoLauncherMain")).isNotNull();
    }

    private static void assertReadmeLinkTargetsExistingFile(String readme, Path repoRoot, String relativePath) {
        assertThat(readme).contains(relativePath);
        assertThat(repoRoot.resolve(relativePath))
                .as("README link target must exist: %s", relativePath)
                .exists();
    }

    private static void assertHeadingsAppearInOrder(String readme, List<String> headings) {
        int previousIndex = -1;
        for (String heading : headings) {
            int currentIndex = readme.indexOf(heading);
            assertThat(currentIndex)
                    .as("README should contain heading %s", heading)
                    .isGreaterThanOrEqualTo(0);
            assertThat(currentIndex)
                    .as("README heading %s should appear after the previous section", heading)
                    .isGreaterThan(previousIndex);
            previousIndex = currentIndex;
        }
    }

    private static Path findRepoRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve("README.md"))
                    && Files.exists(current.resolve("pom.xml"))
                    && Files.exists(current.resolve("streamfence-demo"))
                    && Files.exists(current.resolve("streamfence-core"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("Failed to locate repository root from " + Path.of("").toAbsolutePath());
    }
}

// Runs after the archetype generates a project. Two jobs Velocity filtering can't do cleanly:
//  1. prune feature sample code that wasn't requested (features=messaging,data),
//  2. substitute @TOKEN@ placeholders in the verbatim-copied markdown (## headings can't be Velocity-
//     filtered) and create AGENTS.md as a copy of CLAUDE.md.

import java.nio.file.Files

def projectDir = new File(request.outputDirectory, request.artifactId)
def props = request.properties
def features = (props.getProperty("features") ?: "").toLowerCase()
def packagePath = request.getPackage().replace('.', '/')
def javaRoot = new File(projectDir, "src/main/java/${packagePath}")

// 1. Prune unselected feature samples.
if (!features.contains("messaging")) {
    new File(javaRoot, "messaging").deleteDir()
}
if (!features.contains("data")) {
    new File(javaRoot, "data").deleteDir()
}

// 2. Token substitution in markdown + AGENTS.md copy.
def tokens = [
        "@ARTIFACT_ID@": request.artifactId,
        "@DOCS_URL@"   : (props.getProperty("docsUrl") ?: "https://platform.dubaicustoms.gov.ae/docs"),
        "@ERROR_NS@"   : (props.getProperty("errorCodeNamespace") ?: "XX"),
]
["CLAUDE.md", "README.md"].each { name ->
    def file = new File(projectDir, name)
    if (file.exists()) {
        def text = file.getText("UTF-8")
        tokens.each { token, value -> text = text.replace(token, value) }
        file.write(text, "UTF-8")
    }
}

def claude = new File(projectDir, "CLAUDE.md")
if (claude.exists()) {
    // AGENTS.md mirrors CLAUDE.md for agent tooling that looks for it by that name.
    Files.copy(claude.toPath(), new File(projectDir, "AGENTS.md").toPath())
}

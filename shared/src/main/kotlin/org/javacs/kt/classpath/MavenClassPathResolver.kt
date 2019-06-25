package org.javacs.kt.classpath

import org.javacs.kt.LOG
import org.javacs.kt.util.KotlinLSException
import java.nio.file.Path
import java.nio.file.Files
import java.io.File

/** Resolver for reading maven dependencies */
internal class MavenClassPathResolver private constructor(private val pom: Path) : ClassPathResolver {

    override val classpath: Set<Path> get() {
        val mavenOutput = generateMavenDependencyList(pom)
        val artifacts = mavenOutput?.let(::readMavenDependencyList) ?: throw KotlinLSException("No artifacts could be read from $pom")

        when {
            artifacts.isEmpty() -> LOG.warn("No artifacts found in {}", pom)
            artifacts.size < 5 -> LOG.info("Found {} in {}", artifacts, pom)
            else -> LOG.info("Found {} artifacts in {}", artifacts.size, pom)
        }

        return artifacts.mapNotNull { findMavenArtifact(it, false) }.toSet()
    }

    companion object {

        /** Create a maven resolver if a file is a pom */
        fun maybeCreate(file: Path): MavenClassPathResolver? =
            file?.takeIf { it.endsWith("pom.xml") }?.let { MavenClassPathResolver(it) }
    }
}

private val artifact = ".*:.*:.*:.*:.*".toRegex()

private fun readMavenDependencyList(mavenOutput: Path): Set<Artifact> =
    mavenOutput.toFile()
        .readLines()
        .filter { it.matches(artifact) }
        .map { parseArtifact(it) }
        .toSet()

private fun findMavenArtifact(a: Artifact, source: Boolean): Path? {
    val result = mavenHome.resolve("repository")
        .resolve(a.group.replace('.', File.separatorChar))
        .resolve(a.artifact)
        .resolve(a.version)
        .resolve(mavenJarName(a, source))

    return if (Files.exists(result))
        result
    else {
        LOG.warn("Couldn't find {} in {}", a, result)
        null
    }
}

private fun mavenJarName(a: Artifact, source: Boolean) =
    if (source) "${a.artifact}-${a.version}-sources.jar"
    else "${a.artifact}-${a.version}.jar"

private fun generateMavenDependencyList(pom: Path): Path? {
    val mavenOutput = Files.createTempFile("deps", ".txt")
    val workingDirectory = pom.toAbsolutePath().parent.toFile()
    val cmd = "$mvnCommand dependency:list -DincludeScope=test -DoutputFile=$mavenOutput"
    LOG.info("Run {} in {}", cmd, workingDirectory)
    val process = Runtime.getRuntime().exec(cmd, null, workingDirectory)

    process.inputStream.bufferedReader().use { reader ->
        while (process.isAlive) {
            val line = reader.readLine()?.trim() ?: break
            if ((line.isNotEmpty()) && !line.startsWith("Progress")) {
                LOG.info("Maven: {}", line)
            }
        }
    }

    return mavenOutput
}

private val mvnCommand: Path by lazy {
    requireNotNull(findCommandOnPath("mvn")) { "Unable to find the 'mvn' command" }
}

fun parseArtifact(rawArtifact: String, version: String? = null): Artifact {
    val parts = rawArtifact.trim().split(':')

    return when (parts.size) {
        3 -> Artifact(parts[0], parts[1], version ?: parts[2])
        5, 6 -> Artifact(parts[0], parts[1], version ?: parts[3])
        else -> throw IllegalArgumentException("$rawArtifact is not a properly formed Maven/Gradle artifact")
    }
}

data class Artifact(val group: String, val artifact: String, val version: String) {
    override fun toString() = "$group:$artifact:$version"
}

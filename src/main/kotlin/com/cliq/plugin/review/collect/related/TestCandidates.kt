package com.cliq.plugin.review.collect.related

object TestCandidates {

    private const val MAX_CANDIDATE_DIRECTORIES = 6
    private val JVM_EXTENSIONS = setOf("kt", "kts", "java", "groovy", "scala")
    private val JS_EXTENSIONS = setOf("ts", "tsx", "js", "jsx", "mjs", "cjs", "mts", "cts")
    private val NATIVE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx", "h", "hpp")
    private val SOURCE_ROOTS = setOf("src", "lib", "app", "pkg", "internal")
    private val TEST_ROOTS = listOf("test", "tests", "spec")
    private val MULTIPLATFORM_MAIN = Regex("(^|/)src/([A-Za-z0-9]+)Main/")
    private val TEST_DIRECTORY = Regex("(^|/)(test|tests|__tests__|spec|specs|androidTest|testFixtures)/")
    private val TEST_FILE = Regex(
        "(Tests?|Spec|IT)\\.[A-Za-z0-9]+$|_(test|spec)\\.[A-Za-z0-9]+$|^test_[^/]+$|\\.(test|spec)\\.[A-Za-z0-9]+$",
    )

    fun isTestPath(path: String): Boolean =
        TEST_DIRECTORY.containsMatchIn(path) || TEST_FILE.containsMatchIn(path.substringAfterLast('/'))

    fun testFileNames(sourcePath: String): List<String> {
        val fileName = sourcePath.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val stem = fileName.substringBeforeLast('.', fileName)
        if (stem.isEmpty() || extension.isEmpty()) return emptyList()
        return namesFor(stem, extension)
    }

    fun forSource(sourcePath: String): List<String> {
        if (isTestPath(sourcePath)) return emptyList()
        val names = testFileNames(sourcePath)
        if (names.isEmpty()) return emptyList()
        val extension = sourcePath.substringAfterLast('.').lowercase()
        val directory = sourcePath.substringBeforeLast('/', "")
        return candidateDirectories(directory, extension)
            .flatMap { candidateDirectory -> names.map { join(candidateDirectory, it) } }
            .filter { it != sourcePath }
            .distinct()
    }

    private fun namesFor(stem: String, extension: String): List<String> = when (extension) {
        in JVM_EXTENSIONS -> listOf("${stem}Test", "${stem}Tests", "${stem}Spec", "${stem}IT").map { "$it.$extension" }
        in JS_EXTENSIONS -> listOf("$stem.test.$extension", "$stem.spec.$extension")
        "vue", "svelte" -> listOf("$stem.spec.ts", "$stem.test.ts", "$stem.spec.js", "$stem.test.js")
        "go" -> listOf("${stem}_test.go")
        "py" -> listOf("test_$stem.py", "${stem}_test.py")
        "rb" -> listOf("${stem}_spec.rb", "${stem}_test.rb")
        "rs" -> listOf("${stem}_test.rs", "$stem.rs")
        "php" -> listOf("${stem}Test.php")
        "cs" -> listOf("${stem}Tests.cs", "${stem}Test.cs")
        "swift" -> listOf("${stem}Tests.swift")
        "dart" -> listOf("${stem}_test.dart")
        "ex", "exs" -> listOf("${stem}_test.exs")
        in NATIVE_EXTENSIONS -> listOf("${stem}_test.cpp", "${stem}_test.cc", "test_$stem.c", "${stem}Test.cpp")
        else -> emptyList()
    }

    private fun candidateDirectories(directory: String, extension: String): List<String> {
        val result = linkedSetOf(directory)
        val withSlash = "$directory/"

        if (withSlash.contains("src/main/")) {
            result += withSlash.replaceFirst("src/main/", "src/test/").removeSuffix("/")
            result += withSlash.replaceFirst("src/main/", "src/androidTest/").removeSuffix("/")
        }
        MULTIPLATFORM_MAIN.find(withSlash)?.let { match ->
            val platform = match.groupValues[2]
            result += withSlash.replaceFirst(match.value, "${match.groupValues[1]}src/${platform}Test/").removeSuffix("/")
        }

        when (extension) {
            in JS_EXTENSIONS, "vue", "svelte" -> {
                result += join(directory, "__tests__")
                result += join(directory, "test")
                result += join(directory, "tests")
            }
            "py" -> {
                result += join(directory, "tests")
                result += "tests"
            }
            "rs" -> result += "tests"
        }

        val segments = directory.split('/').filter { it.isNotEmpty() }
        val relativeToSourceRoot = if (segments.firstOrNull() in SOURCE_ROOTS) segments.drop(1) else segments
        if (extension != "go") {
            TEST_ROOTS.forEach { testRoot ->
                result += (listOf(testRoot) + relativeToSourceRoot).joinToString("/")
            }
        }
        return result.take(MAX_CANDIDATE_DIRECTORIES)
    }

    private fun join(directory: String, name: String): String =
        if (directory.isEmpty()) name else "$directory/$name"
}

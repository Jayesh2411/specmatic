package application

import `in`.specmatic.conversions.OpenApiSpecification
import `in`.specmatic.core.Feature
import `in`.specmatic.core.git.GitCommand
import `in`.specmatic.core.git.SystemGit
import `in`.specmatic.core.log.LogMessage
import `in`.specmatic.core.log.logger
import `in`.specmatic.core.pattern.ContractException
import `in`.specmatic.core.utilities.exceptionCauseMessage
import `in`.specmatic.core.value.JSONArrayValue
import `in`.specmatic.core.value.JSONObjectValue
import `in`.specmatic.core.value.StringValue
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

fun fetchAllContracts(git: GitCommand): List<Pair<Feature, String>> =
    listOfAllContractFiles(File(git.gitRoot())).mapNotNull {
        loadContractData(it)
    }

fun loadContractData(it: File) = try {
    Pair(OpenApiSpecification.fromYAML(it.readText(), it.path).toFeature(), it.path)
} catch (e: Throwable) {
    logger.debug(exceptionCauseMessage(e))
    null
}

@CommandLine.Command(name = "redeclared",
    mixinStandardHelpOptions = true,
    description = ["Checks if new APIs in this file have been re-declared"])
class ReDeclaredAPICommand: Callable<Unit> {
    @CommandLine.Command(name = "file", description = ["Check the specified contract for re-declarations"])
    fun file(@CommandLine.Parameters(paramLabel = "contractPath") contractFilePath: String): Int {
        val redeclarations = findReDeclaredContracts(ContractToCheck(contractFilePath, SystemGit()))

        if(redeclarations.isNotEmpty()) {
            logger.log("Some APIs in $contractFilePath have been declared in other files as well.")
            logger.newLine()
        }

        redeclarations.forEach { (newPath, contracts) ->
            logger.log(newPath)
            logger.log(contracts.joinToString("\n") { "- $it" })
        }

        return if(redeclarations.isNotEmpty())
            1
        else
            0
    }

    class JSONArrayLogMessage(val json: JSONArrayValue): LogMessage {
        override fun toJSONObject(): JSONObjectValue {
            return JSONObjectValue(mapOf("list" to json))
        }

        override fun toLogString(): String {
            return json.displayableValue()
        }

    }

    @CommandLine.Command(name = "entire-repo", description = ["Check all contracts in the repo for re-declarations"])
    fun entireRepo(@Option(names = ["--json"]) json: Boolean): Int {
        val contracts: List<Pair<Feature, String>> = fetchAllContracts(SystemGit())

        val redeclarations = findReDeclarationsAmongstContracts(contracts)

        if(json) {
            val redeclarationsJSON = JSONArrayValue(redeclarations.map { (api, files) ->
                val jsonFileList = JSONArrayValue(files.map { StringValue(it) })
                JSONObjectValue(mapOf("api" to StringValue(api), "files" to jsonFileList))
            })

            logger.log(JSONArrayLogMessage(redeclarationsJSON))
        } else {
            if(redeclarations.isNotEmpty()) {
                logger.log("Some APIs have been declared in multiple files.")
                logger.newLine()
            }

            redeclarations.forEach { (newPath, contracts) ->
                logger.log(newPath)
                logger.log(contracts.joinToString("\n"))
                logger.newLine()
            }

            logger.log("Count of APIs re-declared: ${redeclarations.size}")
        }

        return if(redeclarations.isNotEmpty())
            1
        else
            0
    }

    @CommandLine.Option(names = ["--entire-repo"], description = ["Check all contracts for redeclaration instead of a single contract"], defaultValue = "false")
    var entireRepo: Boolean = false

    override fun call() {
        CommandLine(GitCompatibleCommand()).usage(System.out)
    }
}

data class ReDeclarations(val apiURLPath: String, val contractsContainingAPI: List<String>)

fun findReDeclarationsAmongstContracts(contracts: List<Pair<Feature, String>>): Map<String, List<String>> {
    val declarations = contracts.flatMap { (feature, filePath) ->
        pathsFromFeature(feature).map { urlPath -> Pair(urlPath, filePath) }
    }.groupBy { (urlPath, _) -> urlPath }

    val multipleDeclarations = declarations.filter { (_, filePaths) -> filePaths.size > 1 }

    return multipleDeclarations.mapValues { (_, value) ->
        value.map { (_, path) -> path }
    }
}

fun findReDeclaredContracts(
    contractToCheck: ContractToCheck,
): List<ReDeclarations> {
    val paths: List<String> = contractToCheck.getPathsInContract() ?: emptyList()
    val contracts: List<Pair<Feature, String>> = contractToCheck.fetchAllOtherContracts()

    return findRedeclarations(paths, contracts)
}

fun findRedeclarations(
    newPaths: List<String>,
    contracts: List<Pair<Feature, String>>
): List<ReDeclarations> {
    val newPathToContractMap = newPaths.map { newPath ->
        val matchingContracts = contracts.filter { (feature, _) ->
            feature.scenarios.map { it.httpRequestPattern.urlMatcher!!.path }.any { scenarioPath ->
                scenarioPath == newPath
            }
        }.map { it.second }

        ReDeclarations(newPath, matchingContracts)
    }

    return newPathToContractMap
}

fun urlPaths(newerContractYaml: String): List<String>? {
    return try {
        val newContract = OpenApiSpecification.fromYAML(newerContractYaml, "").toFeature()
        pathsFromFeature(newContract)
    } catch(e: ContractException) {
        logger.debug(exceptionCauseMessage(e))
        null
    }
}

private fun pathsFromFeature(newContract: Feature) =
    newContract.scenarios.map { it.httpRequestPattern.urlMatcher!!.path }.sorted().distinct()

open class CanonicalFile(val file: File) {
    val path: String = file.path

    constructor (path: String) : this(File(path).canonicalFile)
    fun readText(): String = file.readText()
    fun relativeTo(parentDir: File): File = file.relativeTo(parentDir)
}

fun listOfAllContractFiles(dir: File): List<File> {
    val fileGroups = dir.listFiles()!!.groupBy { it.isDirectory }

    val files = (fileGroups[false] ?: emptyList()).filter { it.extension == "yaml" }.map { it.canonicalFile }
    val dirs = (fileGroups[true] ?: emptyList()).filter { it.name != ".git" }.map { it.canonicalFile }

    val dirFiles = dirs.flatMap { listOfAllContractFiles(it) }

    return files.plus(dirFiles)
}
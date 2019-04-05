/*
 * Copyright 2013-2019 Julien Guerinet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.guerinet.weave

import com.guerinet.weave.config.AnalyticsConfig
import com.guerinet.weave.config.Source
import com.guerinet.weave.config.StringsConfig
import com.squareup.okhttp.OkHttpClient
import com.squareup.okhttp.Request
import com.squareup.okhttp.Response
import kotlinx.serialization.Optional
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.buffer
import okio.source
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io.CsvListReader
import org.supercsv.prefs.CsvPreference
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.regex.Pattern

/**
 * Main class, executes the main code for parsing the Google Docs file
 * @author Julien Guerinet
 * @since 1.0.0
 */
@Suppress("MemberVisibilityCanBePrivate")
open class Weave {

    open val config: Configs by lazy { parseConfigJson() }

    open val platform by lazy {
        val platform = Platform.parse(config.platform)
        if (platform == null) {
            error("The platform must be Android, iOS, or Web")
            throw IllegalStateException()
        }
        platform
    }

    open val idKey by lazy { config.keyColumnName }

    open val headerKey by lazy { config.headerColumnName }

    open val platformsKey by lazy { config.platformsColumnName }

    open val analyticsHeader = "Constant list of analytics screens and events, auto-generated by Weave"

    /**
     * Weaves the Strings
     */
    open fun weave() {
        try {
            val stringsConfig = config.strings
            val analyticsConfig: AnalyticsConfig? = config.analytics

            if (stringsConfig == null) {
                warning("No Strings config found")
            } else {
                verifyStringsConfigInfo(stringsConfig)
                val downloadedStrands = downloadAllStringStrands(stringsConfig)
                val verifiedIds = verifyKeys(downloadedStrands)
                val verifiedStrands = verifyStringStrands(stringsConfig, verifiedIds)
                writeStringStrands(stringsConfig, verifiedStrands)
                println("Strings parsing complete")
            }

            println()

            if (analyticsConfig == null) {
                warning("No Analytics config found")
            } else {
                verifyAnalyticsConfigInfo(analyticsConfig)
                val downloadedStrands = downloadAllAnalyticStrands(analyticsConfig)
                val verifiedIds = verifyKeys(downloadedStrands)
                val verifiedStrands = verifyAnalyticsStrands(verifiedIds)
                writeAnalyticStrands(analyticsConfig, verifiedStrands)
                println("Analytics parsing complete")
            }
        } catch (e: IOException) {
            error("Weaving failed")
            e.printStackTrace()
        }
    }

    /**
     * Reads and returns the json String from the config file
     *
     * @throws IOException Thrown if there was an error opening or reading the config file
     */
    @Throws(IOException::class)
    open fun readFromConfigFile(): String {
        // Find the config file
        var configFile = File(FILE_NAME)
        if (!configFile.exists()) {
            // If it's not in the directory, check one up
            configFile = File("../$FILE_NAME")
            if (!configFile.exists()) {
                error("Config File $FILE_NAME not found in current or parent directory")
            }
        }

        // Parse the Config from the file
        return configFile.source().buffer().readUtf8()
    }

    /**
     * Returns the parsed [Configs] from the read json String
     */
    open fun parseConfigJson(): Configs {
        val json = readFromConfigFile()
        return Json.nonstrict.parse(Configs.serializer(), json)
    }

    /* VERIFICATION */

    /**
     * Verifies the info is correct on a [StringsConfig] [config]
     */
    open fun verifyStringsConfigInfo(config: StringsConfig) {
        // Make sure there's at least one language
        if (config.languages.isEmpty()) {
            error("Please provide at least one language")
        }
    }

    /**
     * Verifies that all of the config info is present
     */
    open fun verifyAnalyticsConfigInfo(config: AnalyticsConfig) {
        // Make sure there's a package for Android
        if (platform == Platform.ANDROID && config.packageName == null) {
            error("Please provide a package name for Android")
        }
    }

    /* DOWNLOAD */

    open fun downloadCsv(source: Source): CsvListReader? {
        // Connect to the URL
        println("Connecting to ${source.url}")
        val request = Request.Builder()
            .get()
            .url(source.url)
            .build()

        val response: Response
        try {
            response = OkHttpClient().setCache(null).newCall(request).execute()
        } catch (e: IOException) {
            // Catch the exception here to be able to continue a build even if we are not connected
            println("IOException while connecting to the URL")
            error("Message received: ${e.message}", false)
            return null
        }

        val responseCode = response.code()
        println("Response Code: $responseCode")

        if (responseCode != 200) {
            error("Response Message: ${response.message()}", false)
            return null
        }

        // Set up the CSV reader and return it
        return CsvListReader(InputStreamReader(response.body().byteStream(), "UTF-8"), CsvPreference.EXCEL_PREFERENCE)
    }

    /**
     * Parses the [headers] by letting the caller deal with specific cases with [onColumn], and returning the columns of
     *  the key and platform (-1 if not found)
     */
    open fun parseHeaders(
        headers: Array<String?>,
        onColumn: (index: Int, header: String) -> Unit
    ): Pair<Int, Int> {
        // Keep track of which columns hold the keys and the platform
        var keyColumn = -1
        var platformColumn = -1

        headers.forEachIndexed { index, s ->
            if (s == null) {
                // Disregard the null headers
                return@forEachIndexed
            }

            when {
                // Note the key column if it matches the key key
                s.equals(idKey, ignoreCase = true) -> keyColumn = index
                // Note the platform column if it matches the platform key
                s.equals(platformsKey, ignoreCase = true) -> platformColumn = index
            }

            // Pass it to the lambda for the caller to do whatever with the result
            onColumn(index, s)
        }

        // Make sure there is a key column
        if (keyColumn == -1) {
            error("There must be a column marked 'key' with the String keys")
        }

        return Pair(keyColumn, platformColumn)
    }

    /**
     * Parses the csv from the [reader] using the [headers]. Determines the key and platforms using the [keyColumn] and
     *  [platformColumn], and delegates the parsing to the caller with [onLine]. Parses the headers itself using the
     *  [source]. Returns the list of parsed [BaseStrand]s
     */
    open fun parseCsv(
        source: Source,
        reader: CsvListReader,
        headers: Array<String?>,
        keyColumn: Int,
        platformColumn: Int,
        onLine: (lineNumber: Int, key: String, line: List<Any>) -> BaseStrand?
    ): List<BaseStrand> {
        // Create the list of Strings
        val strings = mutableListOf<BaseStrand>()

        // Make a CellProcessor with the right length
        val processors = arrayOfNulls<CellProcessor>(headers.size)

        // Go through each line of the CSV document into a list of objects.
        var currentLine = reader.read(*processors)

        // The current line number (start at 2 since 1 is the header)
        var lineNumber = 2

        while (currentLine != null) {
            // Get the key from the current line
            val key = (currentLine[keyColumn] as? String)?.trim()

            // Check if there's a key
            if (key.isNullOrBlank()) {
                println("Warning: Line $lineNumber does not have a key and will not be parsed")

                // Increment the line number
                lineNumber++
                currentLine = reader.read(*processors)
                continue
            }

            // Check if this is a header
            if (key.startsWith(headerKey)) {
                strings.add(BaseStrand(key.replace("###", "").trim(), source.title, lineNumber))

                // Increment the line number
                lineNumber++
                currentLine = reader.read(*processors)
                continue
            }

            if (platformColumn != -1) {
                // If there's a platform column, parse it and check that it's for the current platform
                val platforms = currentLine[platformColumn] as? String
                if (!isForPlatform(platforms)) {
                    // Increment the line number
                    lineNumber++
                    currentLine = reader.read(*processors)
                    continue
                }
            }

            // Delegate the parsing to the caller, add the resulting BaseStrand if there is one
            val baseString = onLine(lineNumber, key, currentLine)
            baseString?.apply { strings.add(this) }

            // Increment the line number
            lineNumber++

            // Next line
            currentLine = reader.read(*processors)
        }

        // Close the CSV reader
        reader.close()

        return strings
    }

    /**
     * Writes all of the Strings. Throws an [IOException] if there's an error
     */
    @Throws(IOException::class)
    open fun preparePrintWriter(path: String, title: String, write: (PrintWriter) -> Unit) {
        // Set up the writer for the given language, enforcing UTF-8
        val writer = PrintWriter(path, "UTF-8")

        // Write the Strings
        write(writer)

        // Show the outcome
        println("Wrote $title to file: $path")

        // Flush and close the writer
        writer.flush()
        writer.close()
    }

    /* STRING PARSING */

    /**
     * Downloads all of the Strings from all of the Urls. Throws an [IOException] if there are
     *  any errors downloading the Strings
     */
    @Throws(IOException::class)
    open fun downloadAllStringStrands(config: StringsConfig): List<BaseStrand> = config.sources
        .mapNotNull { downloadStrands(config, it) }
        .flatten()

    /**
     * Uses the given [source] to connect to a Url and download all of the Strings in the right
     *  format. This will return a list of [BaseStrand]s, null if there were any errors
     */
    open fun downloadStrands(config: StringsConfig, source: Source): List<BaseStrand>? {
        val reader = downloadCsv(source) ?: return null

        // Get the header
        val headers = reader.getHeader(true)

        // Get the key and platform columns, and map the languages to the right indexes
        val (keyColumn, platformColumn) = parseHeaders(headers) { index, header ->
            // Check if the String matches any of the languages parsed
            val language = config.languages.find { header.trim().equals(it.id, ignoreCase = true) }
            language?.columnIndex = index
        }

        // Make sure that all languages have an index
        val language = config.languages.find { it.columnIndex == -1 }
        if (language != null) {
            error("${language.id} in ${source.title} does not have any translations.")
        }

        return parseCsv(source, reader, headers, keyColumn, platformColumn) { lineNumber, key, line ->
            // Add a new language String
            val languageString = LanguageStrand(key, source.title, lineNumber)

            // Go through the languages, add each translation
            config.languages.forEach {
                val currentLanguage = line[it.columnIndex] as? String
                if (currentLanguage != null) {
                    languageString.addTranslation(it.id, currentLanguage)
                }
            }
            languageString
        }
    }

    /**
     * Verifies that the keys are valid
     */
    open fun verifyKeys(strands: List<BaseStrand>): List<BaseStrand> {
        // Define the key checker pattern to make sure no illegal characters exist within the keys
        val keyChecker = Pattern.compile("[^A-Za-z0-9_]")

        // Get rid of all of the headers
        val filteredStrands = strands.filter { it is LanguageStrand || it is AnalyticsStrand }

        val toRemove = mutableListOf<BaseStrand>()

        // Check if there are any errors with the keys
        filteredStrands.forEach {
            // Check if there are any spaces in the keys
            if (it.key.contains(" ")) {
                error("${getLog(it)} contains a space in its key.")
            }

            if (keyChecker.matcher(it.key).find()) {
                error("${getLog(it)} contains some illegal characters.")
            }
        }

        // Remove all duplicates
        val verifiedStrands = strands.toMutableList()
        verifiedStrands.removeAll(toRemove)
        return verifiedStrands
    }

    /**
     * Verifies the [strands] by removing those who don't have translations and warning about the others
     *  that don't have all translations
     */
    open fun verifyStringStrands(config: StringsConfig, strands: List<BaseStrand>): List<BaseStrand> {
        val stringStrands = strands.mapNotNull { it as? LanguageStrand }
        val toRemove = mutableListOf<BaseStrand>()

        // Check if there are any duplicates
        for (i in stringStrands.indices) {
            val strand1 = stringStrands[i]

            for (j in i + 1 until stringStrands.size) {
                val strand2 = stringStrands[j]

                // If the keys are the same and it's not a header, show a warning and remove the older one
                if (strand1.key == strand2.key) {
                    warning("${getLog(strand1)} and ${getLog(strand2)} have the same key. The second one will be used")
                    toRemove.add(strand1)
                }
            }
        }

        val verifiedStrands = strands.toMutableList()
        verifiedStrands.removeAll(toRemove)
        toRemove.clear()

        stringStrands
            .forEach {
                val lineNumber = it.lineNumber
                val sourceName = it.sourceName
                if (it.translations.isEmpty()) {
                    // Show a warning message if there are no translations and remove it
                    warning("Line $lineNumber from $sourceName has no translations so it will not be parsed.")
                    toRemove.add(it)
                } else if (it.translations.size != config.languages.size) {
                    warning("Warning: Line $lineNumber from $sourceName is missing at least one translation")
                }
            }

        verifiedStrands.removeAll(toRemove)
        return verifiedStrands
    }

    /**
     * Writes all of the Strings for the different languages. Throws an [IOException] if there's
     *  an error
     */
    @Throws(IOException::class)
    open fun writeStringStrands(config: StringsConfig, strands: List<BaseStrand>) {
        // If there are no Strings to write, no need to continue
        if (strands.isEmpty()) {
            println("No Strings to write")
            return
        }

        // Go through each language, and write the file
        config.languages.forEach {
            preparePrintWriter(it.path, it.id) { writer ->
                writeStrands(writer, it, strands)
            }
        }
    }

    /**
     * Processes the Strings and writes them to a given file for the given [language]
     */
    open fun writeStrands(writer: PrintWriter, language: Language, strands: List<BaseStrand>) {
        // Header
        writeHeader(writer)

        val last = strands.last()

        // Go through the Strings
        strands.forEach {
            try {
                if (it !is LanguageStrand) {
                    // If we are parsing a header, write the value as a comment
                    writeComment(writer, it.key)
                } else {
                    writeString(writer, language, it, last == it)
                }
            } catch (e: Exception) {
                error(getLog(it), false)
                e.printStackTrace()
            }
        }

        // Footer
        writeFooter(writer)
    }

    /**
     * Writes the header to the current file
     */
    open fun writeHeader(writer: PrintWriter) {
        writer.apply {
            when (platform) {
                Platform.ANDROID -> println("<?xml version=\"1.0\" encoding=\"utf-8\"?> \n <resources>")
                Platform.WEB -> println("{")
                else -> return
            }
        }
    }

    /**
     * Writes a [comment] to the file
     */
    open fun writeComment(writer: PrintWriter, comment: String) {
        writer.apply {
            when (platform) {
                Platform.ANDROID -> println("\n    <!-- $comment -->")
                Platform.IOS -> println("\n/* $comment */")
                else -> return
            }
        }
    }

    /**
     * Writes a [strand] to the file within the current [language] within the file.
     *  Depending on the platform and whether this [isLastStrand], the String differs
     */
    open fun writeString(
        writer: PrintWriter,
        language: Language,
        strand: LanguageStrand,
        isLastStrand: Boolean
    ) {
        var string = strand.getString(language.id)

        // Check if value is or null empty: if it is, continue
        if (string == null) {
            string = ""
        }

        if (string.isBlank() && platform != Platform.WEB) {
            // Skip over the empty values unless we're on Web
            return
        }

        // Trim
        string = string
            .trim()
            // Unescaped quotations
            .replace("\"", "\\" + "\"")
            // Copyright
            .replace("(c)", "\u00A9")
            // New Lines
            .replace("\n", "")

        val key = strand.key
        when (platform) {
            Platform.ANDROID -> {
                string = string
                    // Ampersands
                    .replace("&", "&amp;")
                    // Apostrophes
                    .replace("'", "\\'")
                    // Unescaped @ signs
                    .replace("@", "\\" + "@")
                    // Ellipses
                    .replace("...", "&#8230;")
                    // Dashes
                    .replace("-", "–")

                // Check if this is an HTML String
                string = if (string.contains("<html>", ignoreCase = true)) {
                    // Don't format the greater than and less than symbols
                    string
                        .replace("<html>", "<![CDATA[", ignoreCase = true)
                        .replace("</html>", "]]>", ignoreCase = true)
                } else {
                    // Format the greater then and less than symbol otherwise
                    string
                        // Greater than
                        .replace(">", "&gt;")
                        // Less than
                        .replace("<", "&lt;")
                }

                // Add the XML tag
                writer.println("    <string name=\"$key\">$string</string>")
            }
            Platform.IOS -> {
                string = string
                    // Replace %s format specifiers with %@
                    .replace("%s", "%@")
                    .replace("\$s", "$@")
                    // Remove <html> </html>tags
                    .replace("<html>", "", ignoreCase = true)
                    .replace("</html>", "", ignoreCase = true)

                writer.println("\"$key\" = \"$string\";")
            }
            Platform.WEB -> {
                // Remove <html> </html>tags
                string = string
                    .replace("<html>", "", ignoreCase = true)
                    .replace("</html>", "", ignoreCase = true)

                writer.println("    \"$key\": \"$string\"${if (isLastStrand) "" else ","}")
            }
        }
    }

    /**
     * Writes the footer to the current file
     */
    open fun writeFooter(writer: PrintWriter) {
        writer.apply {
            when (platform) {
                Platform.ANDROID -> println("</resources>")
                Platform.WEB -> println("}")
                else -> return
            }
        }
    }

    /* ANALYTICS PARSING */

    /**
     * Downloads all of the [AnalyticsStrand]s from all of the Urls. Throws an [IOException] if there are
     *  any errors downloading them
     */
    @Throws(IOException::class)
    open fun downloadAllAnalyticStrands(config: AnalyticsConfig): List<BaseStrand> = config.sources
        .mapNotNull { downloadAnalyticStrands(config, it) }
        .flatten()

    open fun downloadAnalyticStrands(config: AnalyticsConfig, source: Source): List<BaseStrand>? {
        val reader = downloadCsv(source) ?: return null

        val headers = reader.getHeader(true)
        var typeColumn = -1
        var tagColumn = -1

        // Keep track of which columns hold the keys and the platform
        val (keyColumn, platformColumn) = parseHeaders(headers) { index, header ->
            when {
                header.equals(config.typeColumnName, ignoreCase = true) -> typeColumn = index
                header.equals(config.tagColumnName, ignoreCase = true) -> tagColumn = index
            }
        }

        // Make sure we have the tag column
        if (tagColumn == -1) {
            error("Tag column with name ${config.tagColumnName} not found")
        }

        return parseCsv(source, reader, headers, keyColumn, platformColumn) { lineNumber, key, line ->
            val type = if (typeColumn != -1) {
                line[typeColumn] as? String
            } else {
                null
            }
            val tag = line[tagColumn] as? String

            when (tag) {
                null -> {
                    warning("Line $lineNumber has no tag and will not be parsed")
                    null
                }
                else -> AnalyticsStrand(key, source.title, lineNumber, type.orEmpty().trim(), tag.trim())
            }
        }
    }

    /**
     * Verifies the analytics [strands] by ensuring that there are no duplicates (same type and same key)
     */
    open fun verifyAnalyticsStrands(strands: List<BaseStrand>): List<BaseStrand> {
        val analyticsStrands = strands.mapNotNull { it as? AnalyticsStrand }
        val toRemove = mutableListOf<BaseStrand>()

        // Check if there are any duplicates
        for (i in analyticsStrands.indices) {
            val strand1 = analyticsStrands[i]

            for (j in i + 1 until analyticsStrands.size) {
                val strand2 = analyticsStrands[j]

                // If the keys are the same and the type is the same, show a warning and remove the older one
                if (strand1.key == strand2.key && strand1.type == strand2.type) {
                    warning(
                        "${getLog(strand1)} and ${getLog(strand2)} have the same key and type. " +
                                "The second one will be used"
                    )
                    toRemove.add(strand1)
                }
            }
        }

        val verifiedStrands = strands.toMutableList()
        verifiedStrands.removeAll(toRemove)
        return verifiedStrands
    }

    /**
     * Writes the analytics Strings using the [config] data
     */
    open fun writeAnalyticStrands(config: AnalyticsConfig, strands: List<BaseStrand>) {
        // If there are no Strings to write, don't continue
        if (strands.isEmpty()) {
            warning("No Analytics Strings to write")
            return
        }

        // Get the object name by taking the last item on the path and removing the right suffix depending on platform
        val objectName = when (platform) {
            Platform.ANDROID -> config.path.split("/").last().removeSuffix(".kt")
            Platform.IOS -> config.path.split("/").last().removeSuffix(".swift")
            // No object name on web
            else -> ""
        }

        preparePrintWriter(config.path, "Analytics") { writer ->
            // Header
            writeAnalyticsHeader(writer, objectName, config.packageName)

            val sortedStrands = strands
                // Only write the Analytics Strands, since they get pre-sorted so the comments make no sense
                .mapNotNull { it as? AnalyticsStrand }
                .toMutableList()

            // Get the Strands with no type
            val noTypeStrands = sortedStrands.filter { it.type.isBlank() }

            // Remove them from the original list
            sortedStrands.removeAll(noTypeStrands)

            // Write them
            noTypeStrands.forEachIndexed { index, analyticsStrand ->
                writeAnalyticsString(writer, analyticsStrand, false, index == noTypeStrands.lastIndex)
            }

            // Go through the types, pull out the appropriate Strings and write them one by one
            config.types.forEachIndexed { index, type ->
                // Get the Strands for it
                val typeStrands = sortedStrands.filter { it.type.equals(type, ignoreCase = true) }

                // If there are no strands, don't continue
                if (typeStrands.isEmpty()) {
                    return@forEachIndexed
                }

                // Remove them from the original list
                sortedStrands.removeAll(typeStrands)

                // Write the header
                writeAnalyticsTypeHeader(writer, type)

                // Get the last strand
                val lastStrand = typeStrands.last()

                // Write the Strands
                typeStrands.forEach { strand -> writeAnalyticsString(writer, strand, true, strand == lastStrand) }

                writeAnalyticsTypeFooter(writer, index == config.types.lastIndex)
            }

            // Footer
            writeAnalyticsFooter(writer)
        }
    }

    open fun writeAnalyticsTypeHeader(writer: PrintWriter, typeName: String) {
        writer.apply {
            when (platform) {
                Platform.ANDROID -> println("    object $typeName {")
                Platform.IOS -> println("    enum $typeName {")
                Platform.WEB -> println("    \"${typeName.toLowerCase()}\" : { ")
            }
        }
    }

    open fun writeAnalyticsTypeFooter(writer: PrintWriter, isLastType: Boolean) {
        writer.apply {
            print("    }")

            if (!isLastType) {
                when (platform) {
                    // If we're on web and this isn't the last type, add a comma
                    Platform.WEB -> print(",")
                    // If we're on mobile and this isn't the last type, add an extra space
                    Platform.ANDROID, Platform.IOS -> println()
                }
            }

            println()
        }
    }

    open fun writeAnalyticsHeader(writer: PrintWriter, objectName: String, packageName: String?) {
        writer.apply {
            when (platform) {
                Platform.ANDROID -> {
                    println("package $packageName")
                    println()
                    println("/**")
                    println(" * $analyticsHeader")
                    println(" */")
                    println("object $objectName {")
                    println()
                }
                Platform.IOS -> {
                    println("//  $analyticsHeader")
                    println()
                    println("class $objectName {")
                }
                Platform.WEB -> {
                    println("{")
                }
            }
        }
    }

    open fun writeAnalyticsString(
        writer: PrintWriter,
        analyticsString: AnalyticsStrand,
        hasType: Boolean,
        isLast: Boolean
    ) {
        try {
            val isWeb = platform == Platform.WEB
            // Capitalize the key for the mobile platforms
            val key = if (isWeb) analyticsString.key else analyticsString.key.toUpperCase()
            val tag = analyticsString.tag
            writer.apply {
                if (hasType) {
                    // If there's a type, add more spacing
                    print("    ")
                }

                when (platform) {
                    Platform.ANDROID -> println("    const val $key = \"$tag\"")
                    Platform.IOS -> println("    static let $key = \"$tag\"")
                    Platform.WEB -> {
                        print("    \"$key\": \"$tag\"")
                        if (!isLast) {
                            print(",")
                        }
                        println()
                    }
                }
            }
        } catch (e: Exception) {
            error(getLog(analyticsString), false)
            e.printStackTrace()
        }
    }

    open fun writeAnalyticsFooter(writer: PrintWriter) = writer.println("}")

    /* HELPERS */

    /**
     * Returns the header for a log message for a given [strand]
     */
    open fun getLog(strand: BaseStrand): String = "Line ${strand.lineNumber} from ${strand.sourceName}"

    /**
     * Prints an error [message], and terminates the program is [isTerminated] is true (defaults to true)
     */
    open fun error(message: String, isTerminated: Boolean = true) {
        println("Error: $message")
        if (isTerminated) {
            System.exit(-1)
        }
    }

    /**
     * Returns true if this [platformCsv] contains the [platform], false otherwise
     */
    open fun isForPlatform(platformCsv: String?): Boolean {
        val platforms = platformCsv
            ?.split(",")
            ?.mapNotNull { Platform.parse(it.trim().toLowerCase()) } ?: listOf()
        return platforms.isEmpty() || platforms.contains(platform)
    }

    open fun warning(message: String) = println("Warning: $message")

    companion object {

        const val FILE_NAME = "weave-config.json"

        @JvmStatic
        fun main(args: Array<String>) {
            Weave().weave()
        }
    }
}

/**
 * Convenience mapping to what is read from the config file. It can have a [strings] and/or an [analytics].
 *  Also contains the [platform] this is for
 */
@Serializable
class Configs(
    val platform: String,
    @Optional val headerColumnName: String = "###",
    @Optional val keyColumnName: String = "key",
    @Optional val platformsColumnName: String = "platforms",
    @Optional val strings: StringsConfig? = null,
    @Optional val analytics: AnalyticsConfig? = null
)

enum class Platform {
    ANDROID,
    IOS,
    WEB;

    companion object {
        /**
         * Parses the [string] into a [Platform]. Returns null if none found
         */
        internal fun parse(string: String) = when {
            string.equals("Android", ignoreCase = true) -> ANDROID
            string.equals("iOS", ignoreCase = true) -> IOS
            string.equals("Web", ignoreCase = true) -> WEB
            else -> null
        }
    }
}

package io.eels.component.csv

import com.typesafe.config.ConfigFactory
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import io.eels.Option
import io.eels.Row
import io.eels.Schema
import io.eels.component.Part
import io.eels.component.SchemaInferrer
import io.eels.component.Source
import io.eels.component.SourceReader
import io.eels.component.StringInferrer
import io.eels.component.Using
import io.eels.getOrElse
import java.nio.file.Path
import java.util.stream.Stream

enum class Header {
  None, FirstComment, FirstRow
}

data class CsvSource(val path: Path,
                     val overrideSchema: Option<Schema> = Option.None,
                     val format: CsvFormat = CsvFormat(),
                     val inferrer: SchemaInferrer = StringInferrer,
                     val ignoreLeadingWhitespaces: Boolean = true,
                     val ignoreTrailingWhitespaces: Boolean = true,
                     val skipEmptyLines: Boolean = true,
                     val emptyCellValue: Option<String> = Option.None,
                     val verifyRows: Option<Boolean> = Option.None,
                     val header: Header = Header.FirstRow) : Source, Using {

  val config = ConfigFactory.load()
  val defaultVerifyRows = verifyRows.getOrElse(config.getBoolean("eel.csv.verifyRows"))

  private fun createParser(): CsvParser {
    val settings = CsvParserSettings()
    settings.format.delimiter = format.delimiter
    settings.format.quote = format.quoteChar
    settings.format.quoteEscape = format.quoteEscape
    settings.isLineSeparatorDetectionEnabled = true
    // this is always true as we will fetch the headers ourselves by reading first row
    settings.isHeaderExtractionEnabled = false
    settings.ignoreLeadingWhitespaces = ignoreLeadingWhitespaces
    settings.ignoreTrailingWhitespaces = ignoreTrailingWhitespaces
    settings.skipEmptyLines = skipEmptyLines
    settings.isCommentCollectionEnabled = true
    settings.emptyValue = emptyCellValue.orNull()
    settings.nullValue = emptyCellValue.orNull()
    return com.univocity.parsers.csv.CsvParser(settings)
  }

  fun withSchemaInferrer(inferrer: SchemaInferrer): CsvSource = copy(inferrer = inferrer)
  fun withHeader(header: Header): CsvSource = copy(header = header)
  fun withSchema(schema: Schema): CsvSource = copy(overrideSchema = Option.Some(schema))
  fun withDelimiter(c: Char): CsvSource = copy(format = format.copy(delimiter = c))
  fun withQuoteChar(c: Char): CsvSource = copy(format = format.copy(quoteChar = c))
  fun withQuoteEscape(c: Char): CsvSource = copy(format = format.copy(quoteEscape = c))
  fun withFormat(format: CsvFormat): CsvSource = copy(format = format)
  fun withVerifyRows(verifyRows: Boolean): CsvSource = copy(verifyRows = Option.Some(verifyRows))
  fun withEmptyCellValue(emptyCellValue: String): CsvSource = copy(emptyCellValue = Option.Some(emptyCellValue))
  fun withSkipEmptyLines(skipEmptyLines: Boolean): CsvSource = copy(skipEmptyLines = skipEmptyLines)
  fun withIgnoreLeadingWhitespaces(ignore: Boolean): CsvSource = copy(ignoreLeadingWhitespaces = ignore)
  fun withIgnoreTrailingWhitespaces(ignore: Boolean): CsvSource = copy(ignoreTrailingWhitespaces = ignore)

  override fun schema(): Schema = overrideSchema.getOrElse {
    val parser = createParser()
    parser.beginParsing(path.toFile())
    val headers = when (header) {
      Header.None -> {
        val headers = parser.parseNext()
        (0..headers.size).map { it.toString() }.toList()
      }
      Header.FirstComment -> {
        while (parser.context.lastComment() == null && parser.parseNext() != null) {
        }
        val str = Option.Some(parser.context.lastComment()).getOrElse("")
        str.split(format.delimiter).toList()
      }
      Header.FirstRow -> parser.parseNext().toList()
    }
    parser.stopParsing()
    inferrer.schemaOf(headers)
  }

  override fun parts(): List<Part> {
    val verify = when (header) {
      Header.None -> false
      else -> verifyRows.getOrElse(defaultVerifyRows)
    }
    val part = CsvPart(createParser(), path, header, verify, schema())
    return listOf(part)
  }
}

class CsvPart(val parser: CsvParser, val path: Path, val header: Header, val verifyRows: Boolean, val schema: Schema) : Part {

  override fun reader(): SourceReader = object : io.eels.component.SourceReader {

    init {
      parser.beginParsing(path.toFile())
    }

    override fun close(): Unit = parser.stopParsing()
    override fun iterator(): Iterator<Row> {
      val rowsToSkip = when (header) {
        Header.FirstRow -> 1
        else -> 0
      }
      return Stream.generate { parser.parseNext() }.skip(rowsToSkip.toLong()).iterator().asSequence().takeWhile { it != null }.map {
        if (verifyRows) {
          assert(
              it.size == schema.size(),
              { "Row has ${it.size} fields but schema has ${schema.size()}\nRow=${it.joinToString(", ")}\nSchema=${schema.columnNames().joinToString(", ")}" }
          )
        }
        Row(schema, it.toList())
      }.iterator()
    }
  }
}
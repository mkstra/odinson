package ai.lum.odinson

import java.nio.file.Path
import org.apache.lucene.analysis.core.WhitespaceAnalyzer
import org.apache.lucene.document.{ Document => LuceneDocument }
import org.apache.lucene.search.{ Query, BooleanClause => LuceneBooleanClause, BooleanQuery => LuceneBooleanQuery }
import org.apache.lucene.queryparser.classic.{ QueryParser => LuceneQueryParser }
import org.apache.lucene.store.{ Directory, FSDirectory }
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.queryparser.classic.QueryParser
import com.typesafe.config.Config
import ai.lum.common.ConfigUtils._
import ai.lum.common.StringUtils._
import ai.lum.common.ConfigFactory
import ai.lum.odinson.compiler.QueryCompiler
import ai.lum.odinson.lucene._
import ai.lum.odinson.lucene.analysis.TokenStreamUtils
import ai.lum.odinson.lucene.search._
import ai.lum.odinson.state.State
import ai.lum.odinson.digraph.Vocabulary



class ExtractorEngine(
  val indexSearcher: OdinsonIndexSearcher,
  val compiler: QueryCompiler,
  val displayField: String,
  val state: State,
  val parentDocIdField: String
) {

  /** Analyzer for parent queries.  Don't skip any stopwords. */
  val analyzer = new WhitespaceAnalyzer()

  /** query parser for parent doc queries */
  val luceneQueryParser = new LuceneQueryParser("docId", analyzer)

  val indexReader = indexSearcher.getIndexReader()

  val ruleReader = new RuleReader(compiler)

  def doc(docID: Int): LuceneDocument = {
    indexSearcher.doc(docID)
  }

  def numDocs(): Int = {
    indexReader.numDocs()
  }

  /** Retrieves the parent Lucene Document by docId */
  def getParentDoc(docId: String): LuceneDocument = {
    val sterileDocID =  docId.escapeJava
    val booleanQuery = new LuceneBooleanQuery.Builder()
    val q1 = new QueryParser(parentDocIdField, analyzer).parse(s""""$sterileDocID"""")
    booleanQuery.add(q1, LuceneBooleanClause.Occur.MUST)
    val q2 = new QueryParser("type", analyzer).parse("root")
    booleanQuery.add(q2, LuceneBooleanClause.Occur.MUST)
    val q = booleanQuery.build
    val docs = indexSearcher.search(q, 10).scoreDocs.map(sd => indexReader.document(sd.doc))
    //require(docs.size == 1, s"There should be only one parent doc for a docId, but ${docs.size} found.")
    docs.head
  }

  /** Filter query by the contents of the parent document */
  def addParentFilter(query: OdinsonQuery, parentQuery: Query): OdinsonQuery = {
    compiler.addParentFilter(query, parentQuery)
  }

  def compileLuceneQuery(pattern: String) = {
    luceneQueryParser.parse(pattern)
  }

  def compilePattern(pattern: String, patternType: String): OdinsonQuery = {
    compiler.compile(pattern, patternType)
  }

  def compileRules(input: String): Seq[Extractor] = {
    ruleReader.compileRules(input)
  }

  def compileRules(input: String, variables: Map[String, String]): Seq[Extractor] = {
    ruleReader.compileRules(input, variables)
  }

  /** Apply the extractors and return all results */
  def extractMentions(extractors: Seq[Extractor]): Seq[Mention] = {
    extractMentions(extractors, false)
  }

  /** Apply the extractors and return all results */
  def extractMentions(extractors: Seq[Extractor], allowTriggerOverlaps: Boolean): Seq[Mention] = {
    extractMentions(extractors, numDocs(), allowTriggerOverlaps)
  }

  /** Apply the extractors and return results for at most `numSentences` */
  def extractMentions(extractors: Seq[Extractor], numSentences: Int, allowTriggerOverlaps: Boolean): Seq[Mention] = {
    // extract mentions
    val mentions = for {
      e <- extractors
      results = query(e.query, numSentences)
      scoreDoc <- results.scoreDocs
      docFields = doc(scoreDoc.doc)
      docId = docFields.getField("docId").stringValue
      sentId = docFields.getField("sentId").stringValue
      odinsonMatch <- scoreDoc.matches
    } yield Mention(odinsonMatch, e.label, scoreDoc.doc, scoreDoc.segmentDocId, scoreDoc.segmentDocBase, docId, sentId, e.name)
    // if needed, filter results to discard trigger overlaps
    if (allowTriggerOverlaps) {
      mentions
    } else {
      mentions.flatMap { m =>
        m.odinsonMatch match {
          case e: EventMatch => e.removeTriggerOverlaps.map(e => m.copy(odinsonMatch = e))
          case _ => Some(m)
        }
      }
    }
  }

  /** executes query and returns all results */
  def query(odinsonQuery: OdinsonQuery): OdinResults = {
    query(odinsonQuery, indexReader.numDocs())
  }

  /** executes query and returns at most n documents */
  def query(odinsonQuery: OdinsonQuery, n: Int): OdinResults = {
    indexSearcher.odinSearch(odinsonQuery, n)
  }

  /** executes query and returns next n results after the provided doc */
  def query(odinsonQuery: OdinsonQuery, n: Int, afterDoc: Int, afterScore: Float): OdinResults = {
    val after = new OdinsonScoreDoc(afterDoc, afterScore)
    indexSearcher.odinSearch(after, odinsonQuery, n)
  }

  /** executes query and returns next n results after the provided doc */
  def query(odinsonQuery: OdinsonQuery, n: Int, after: OdinsonScoreDoc): OdinResults = {
    indexSearcher.odinSearch(after, odinsonQuery, n)
  }

  def getString(docID: Int, m: OdinsonMatch): String = {
    getTokens(docID, m).mkString(" ")
  }

  def getTokens(m: Mention): Array[String] = {
    getTokens(m.luceneDocId, m.odinsonMatch)
  }

  def getTokens(docID: Int, m: OdinsonMatch): Array[String] = {
    getTokens(docID, displayField).slice(m.start, m.end)
  }

  def getTokens(scoreDoc: OdinsonScoreDoc): Array[String] = {
    getTokens(scoreDoc.doc, displayField)
  }

  def getTokens(scoreDoc: OdinsonScoreDoc, fieldName: String): Array[String] = {
    getTokens(scoreDoc.doc, fieldName)
  }

  def getTokens(docID: Int, fieldName: String): Array[String] = {
    TokenStreamUtils.getTokens(docID, fieldName, indexSearcher, analyzer)
  }

}

object ExtractorEngine {

  def fromConfig(): ExtractorEngine = {
    fromConfig("odinson")
  }

  def fromConfig(path: String): ExtractorEngine = {
    val config = ConfigFactory.load()
    fromConfig(config[Config](path))
  }

  def fromConfig(config: Config): ExtractorEngine = {
    val indexPath = config[Path]("indexDir")
    val indexDir = FSDirectory.open(indexPath)
    fromDirectory(config, indexDir)
  }

  def fromDirectory(config: Config, indexDir: Directory): ExtractorEngine = {
    val indexReader = DirectoryReader.open(indexDir)
    val computeTotalHits = config[Boolean]("computeTotalHits")
    val displayField = config[String]("displayField")
    val indexSearcher = new OdinsonIndexSearcher(indexReader, computeTotalHits)
    val vocabulary = Vocabulary.fromDirectory(indexDir)
    val compiler = QueryCompiler(config, vocabulary)
    val jdbcUrl = config[String]("state.jdbc.url")
    val state = new State(jdbcUrl)
    state.init()
    compiler.setState(state)
    val parentDocIdField = config[String]("index.documentIdField")
    new ExtractorEngine(
      indexSearcher,
      compiler,
      displayField,
      state,
      parentDocIdField
    )
  }

}

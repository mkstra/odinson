package ai.lum.odinson

class DefaultMentionFactory extends MentionFactory {

  def newMention(
    odinsonMatch: OdinsonMatch,
    label: Option[String],
    luceneDocId: Int,
    luceneSegmentDocId: Int,
    luceneSegmentDocBase: Int,
    idGetter: IdGetter,
    foundBy: String,
    arguments: Map[String, Array[Mention]],
  ): Mention = {
    new Mention(odinsonMatch, label, luceneDocId, luceneSegmentDocId, luceneSegmentDocBase, idGetter, foundBy, arguments)
  }

}

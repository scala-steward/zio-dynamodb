package zio.dynamodb

import scala.collection.immutable.HashSet

private[dynamodb] object ReservedAttributeNames {
  val reservedWords: Set[String] = HashSet("FILTER", "FLOAT", "TTL")                // TODO: complete with all reserved words
  val Prefix: String             = "~~~~~~~~~~~~"
  val boundaryCharRegex          = "[\\.|\\[|\\)]$".r
  private val pathRegex          = s"($Prefix\\S+\\.|$Prefix\\S+\\[|$Prefix\\S+)".r // TODO: add ")" ????

  def escape(pathSegment: String): String =
    if (reservedWords.contains(pathSegment.toUpperCase)) s"$Prefix$pathSegment" else pathSegment

  def parse(expression: String): (Map[String, String], String) = {
    def trimBoundaryChars(s: String) = boundaryCharRegex.replaceFirstIn(s, "")

    val targetsToEscape                              = pathRegex.findAllIn(expression).map(trimBoundaryChars(_))
    val replacements: List[(String, String, String)] = targetsToEscape.foldLeft(List.empty[(String, String, String)]) {
      case (acc, s) =>
        val replaced = s.replace(Prefix, "")
        acc :+ ((s"N$replaced", replaced, s)) // ${acc.size}
    }

    val escaped = replacements.foldLeft(expression) {
      case (acc, (sub, _, s)) =>
        val x = acc.replace(s, "#" + sub)
        x
    }
    val map     = replacements.map {
      case (sub, rep, _) =>
        ("#" + sub, rep)
    }.toMap

    val x = (map, escaped)
    x
  }
}

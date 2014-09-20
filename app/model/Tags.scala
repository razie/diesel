package model

object Tags {
  type Tags = Seq[(String, Int)]

  final val ARCHIVE = "archive"
  final val ALL = "all"
  final val NONE = "none"
  final val RECENT = "recent"
  final val INBOX = "inbox"

}



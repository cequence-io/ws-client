package io.cequence.wsclient.service.ws

case class MultipartFormData(
  dataParts: Map[String, Seq[String]] = Map(),
  files: Seq[FilePart] = Nil
)

case class FilePart(
  key: String,
  path: String,
  headerFileName: Option[String] = None,
  contentType: Option[String] = None
) {
  def name = s""""${key}""""
  // the file name sent to the server: the display name if given, else the file's BASE name -
  // never the local path, which would disclose the local directory layout
  def filenameAux: String = headerFileName.getOrElse {
    val baseName = new java.io.File(path).getName
    if (baseName.nonEmpty) baseName else path
  }
  def filenamePart = s""""${filenameAux}""""

  def extension: String = filenameAux.split('.').last
}

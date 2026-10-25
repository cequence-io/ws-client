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
  def name = MultipartParams.quoted(key)
  // the file name sent to the server: the display name if given, else the file's BASE name -
  // never the local path, which would disclose the local directory layout
  def filenameAux: String = headerFileName.getOrElse {
    val baseName = new java.io.File(path).getName
    if (baseName.nonEmpty) baseName else path
  }
  def filenamePart = MultipartParams.quoted(filenameAux)

  // the part after the last dot - empty for a name without a dot (split('.').last made such a
  // name its own extension: a file named "pdf" went out as application/pdf), after a trailing
  // dot, and for a name of only dots ("...", or a "." / ".." path - .last threw there)
  def extension: String = {
    val lastDot = filenameAux.lastIndexOf('.')
    if (lastDot < 0) "" else filenameAux.substring(lastDot + 1)
  }
}

/**
 * Caller-supplied `Content-Disposition` parameter values (data-part names, file keys, file
 * names) in the WHATWG / RFC 7578 4.2 encoding - the one browsers and Play 2.8+ use: `"`, CR
 * and LF become `%22`, `%0D` and `%0A`, so a value can never leave its parameter (no extra
 * parameter, part header or premature end of the part's header block). Any other value is sent
 * unchanged.
 */
private[wsclient] object MultipartParams {

  def escape(value: String): String =
    value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

  def quoted(value: String): String = "\"" + escape(value) + "\""

  // data-part names have always gone out unquoted - kept for every name that is a valid token
  // (byte-identical bodies), quoted otherwise
  def dataPartName(name: String): String =
    if (name.nonEmpty && name.forall(isTokenChar)) name else quoted(name)

  // RFC 9110 tchar
  private def isTokenChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      "!#$%&'*+-.^_`|~".indexOf(c) >= 0
}

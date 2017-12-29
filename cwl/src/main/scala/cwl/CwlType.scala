package cwl

import java.util.UUID

import cats.implicits._
import common.validation.ErrorOr._
import eu.timepit.refined._
import shapeless.Poly1
import shapeless.syntax.singleton._
import wom.types.WomFileType
import wom.values.{WomArray, WomFile, WomSingleDirectory, WomSingleDirectoryOrFile, WomSingleFile, WomString, WomValue}

import scala.annotation.tailrec

object CwlType extends Enumeration {
  type CwlType = Value

  val Null = Value("null")
  val Boolean = Value("boolean")
  val Int = Value("int")
  val Long = Value("long")
  val Float = Value("float")
  val Double = Value("double")
  val String = Value("string")
  val File = Value("File")
  val Directory = Value("Directory")
}

case class File private
(
  `class`: W.`"File"`.T,
  location: Option[String], //TODO refine w/ regex  of IRI
  path: Option[String],
  basename: Option[String],
  checksum: Option[String],
  size: Option[Long],
  secondaryFiles: Option[Array[FileOrDirectory]],
  format: Option[String],
  contents: Option[String]
) {

  lazy val errorOrSecondaryFiles: ErrorOr[List[WomSingleDirectoryOrFile]] = {
    val dirsOrFiles: List[FileOrDirectory] = secondaryFiles.getOrElse(Array.empty).toList
    dirsOrFiles.traverse[ErrorOr, WomSingleDirectoryOrFile] {
      _.fold(CwlDirectoryOrFileAsWomSingleDirectoryOrFile)
    }
  }

  lazy val asWomValue: ErrorOr[WomSingleFile] = {
    path.orElse(location).orElse(basename) match {
      case Some(value) =>
        errorOrSecondaryFiles map {
          WomSingleFile(value, checksum, size, format, contents, _)
        }
      case None =>
        contents match {
          case Some(_) =>
            errorOrSecondaryFiles map {
              val generatedFileName = s"generated-${UUID.randomUUID}.txt"
              WomSingleFile(generatedFileName, checksum, size, format, contents, _)
            }
          case None =>
            "Cannot convert CWL File to WomValue without either a location, a path, a basename, or contents".invalidNel
        }
    }
  }
}

object File {
  def apply(
             location: Option[String] = None, //TODO refine w/ regex  of IRI
             path: Option[String] = None,
             basename: Option[String] = None,
             checksum: Option[String] = None,
             size: Option[Long] = None,
             secondaryFiles: Option[Array[FileOrDirectory]] = None,
             format: Option[String] = None,
             contents: Option[String] = None): File =
    new cwl.File(
      "File".narrow,
      location,
      path,
      basename,
      checksum,
      size,
      secondaryFiles,
      format,
      contents
    )

  def dirname(value: String): String = {
    val index = value.lastIndexOf('/')
    if (index >= 0) {
      value.substring(0, index)
    } else {
      ""
    }
  }

  def basename(value: String): String = value.substring(value.lastIndexOf('/') + 1)

  def nameroot(value: String): String = basename(value).stripSuffix(nameext(value))

  def nameext(value: String): String = {
    val base = basename(value)
    val index = base.lastIndexOf('.')
    if (index >= 0) {
      base.substring(index)
    } else {
      ""
    }
  }

  def secondaryStringFile(primaryWomFile: WomFile, secondaryValue: String): ErrorOr[WomFile] = {
    primaryWomFile.womType match {
      case womFileType: WomFileType =>
        WomFile(womFileType, File.relativeFileName(primaryWomFile.value, secondaryValue)).valid
      case other => s"primaryWomFile.womType returned a non-WomFileType: $other".invalidNel
    }
  }

  def secondaryExpressionFiles(primaryWomFile: WomFile,
                               expression: Expression,
                               parameterContext: ParameterContext): ErrorOr[List[WomSingleDirectoryOrFile]] = {
    /*
    If the value is an expression, the value of self in the expression must be the primary input or output File object
    to which this binding applies.
     */
    val secondaryParameterContext = parameterContext.copy(self = primaryWomFile)

    /*
    The expression must return a filename string relative to the path to the primary File, a File or Directory object
    with either path or location and basename fields set, or an array consisting of strings or File or Directory
    objects.
     */
    def parseResult(nestedLevel: Int)(womValue: WomValue): ErrorOr[List[WomSingleDirectoryOrFile]] = {
      womValue match {
        case womString: WomString => List(WomSingleFile(womString.value)).valid
        case womSingleFile: WomSingleFile => List(womSingleFile).valid
        case womSingleDirectory: WomSingleDirectory => List(womSingleDirectory).valid
        case womArray: WomArray if nestedLevel == 0 =>
          womArray.value.toList flatTraverse parseResult(nestedLevel + 1)
        case other => s"Not a valid secondary file: $other".invalidNel
      }
    }

    val possibleArrayErrorOr: ErrorOr[WomValue] = expression.fold(EvaluateExpression).apply(secondaryParameterContext)
    possibleArrayErrorOr.flatMap(parseResult(nestedLevel = 0))
  }

  def relativeFileName(primary: String, secondary: String): String = {
    /*
    If a value in secondaryFiles is a string that is not an expression, it specifies that the following pattern should
    be applied to the path of the primary file to yield a filename relative to the primary File:

    1. If string begins with one or more caret ^ characters, for each caret, remove the last file extension from the
    path (the last period . and all following characters). If there are no file extensions, the path is unchanged.

    2. Append the remainder of the string to the end of the file path.
     */
    if (secondary.startsWith("^")) {
      @tailrec
      def stripCaret(primaryAcc: String, secondaryAcc: String): (String, String) = {
        if (secondaryAcc.startsWith("^")) {
          val idx = primaryAcc.lastIndexOf('.')
          if (idx < 0) {
            (primaryAcc, secondaryAcc.dropWhile(_ == '^'))
          } else {
            val primaryNext = primaryAcc.substring(0, idx)
            val secondaryNext = secondaryAcc.drop(1)
            stripCaret(primaryNext, secondaryNext)
          }
        } else {
          (primaryAcc, secondaryAcc)
        }
      }

      val (prefix, suffix) = stripCaret(primary, secondary)
      prefix + suffix
    } else {
      secondary
    }

  }
}

case class Directory private
(
  `class`: W.`"Directory"`.T,
  location: Option[String],
  path: Option[String],
  basename: Option[String],
  listing: Option[Array[FileOrDirectory]]
) {

  lazy val errorOrMaybeListing: ErrorOr[Option[List[WomSingleDirectoryOrFile]]] = {
    val maybeErrorOrList: Option[ErrorOr[List[WomSingleDirectoryOrFile]]] =
      listing map {
        _.toList.traverse[ErrorOr, WomSingleDirectoryOrFile] {
          _.fold(CwlDirectoryOrFileAsWomSingleDirectoryOrFile)
        }
      }
    maybeErrorOrList.sequence[ErrorOr, List[WomSingleDirectoryOrFile]]
  }

  lazy val asWomValue: ErrorOr[WomSingleDirectory] = {
    path.orElse(location).orElse(basename) match {
      case Some(value) =>
        errorOrMaybeListing map {
          WomSingleDirectory(value, _)
        }
      case None =>
        listing match {
          case Some(_) =>
            errorOrMaybeListing map {
              val generatedDirName = s"generated-${UUID.randomUUID}-dir"
              WomSingleDirectory(generatedDirName, _)
            }
          case None =>
            "Cannot convert CWL File to WomValue without either a location, a path, a basename, or a listing".invalidNel
        }
    }
  }
}

object Directory {
  def apply(location: Option[String],
            path: Option[String],
            basename: Option[String],
            listing: Option[Array[FileOrDirectory]]
           ): Directory =
    new cwl.Directory("Directory".narrow, location, path, basename, listing)

  def basename(value: String): String = value.stripSuffix("/").substring(value.lastIndexOf('/') + 1)
}

private[cwl] object CwlDirectoryOrFileAsWomSingleDirectoryOrFile extends Poly1 {
  implicit def caseFile: Case.Aux[File, ErrorOr[WomSingleDirectoryOrFile]] = at {
    _.asWomValue
  }

  implicit def caseDirectory: Case.Aux[Directory, ErrorOr[WomSingleDirectoryOrFile]] = at {
    _.asWomValue
  }
}

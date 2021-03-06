package au.org.ala.biocache.load

import java.io._
import java.net.{HttpURLConnection, URI, URL}
import java.security.MessageDigest
import java.util
import java.util.Properties;

import com.jayway.jsonpath.JsonPath
import net.minidev.json.JSONArray
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.{FilenameUtils, FileUtils}
import org.apache.commons.lang3.StringUtils
import org.apache.http.NameValuePair
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.utils.URLEncodedUtils
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.content.{StringBody, FileBody}
import org.apache.http.entity.mime.{MultipartEntityBuilder, HttpMultipartMode, MultipartEntity}
import org.apache.http.impl.client.DefaultHttpClient
import org.slf4j.LoggerFactory
import scala.collection.mutable
import scala.io.Source

import au.org.ala.biocache.Config
import au.org.ala.biocache.model.{Multimedia, FullRecord}
import au.org.ala.biocache.util.{ HttpUtil, Json}


/**
 * Trait for Media stores to implement.
 */
trait MediaStore {

  val logger = LoggerFactory.getLogger("MediaStore")

  // Regular expression used to parse an image URL - adapted from
  // http://stackoverflow.com/questions/169625/regex-to-check-if-valid-url-that-ends-in-jpg-png-or-gif#169656
  // Extended to allow query parameters after the path and ftp as well as http access
  lazy val imageParser = """^((?:http|https|ftp|file)s?://[^\'"<>]+?\.(bmp|gif|ico|jpe|jpeg|jpg|png|svg)(\?.+)?)$""".r
  lazy val soundParser = """^((?:http|https|ftp|file)s?://[^\'"<>]+?\.(?:aiff|amr|flac|m4a|mp3|oga|ogg|opus|wav)(\?.+)?)$""".r
  lazy val videoParser = """^((?:http|https|ftp|file)s?://[^\'"<>]+?\.(?:3gp|avi|m4v|mov|mp4|mpg|ogm|ogv|webm|wmv)(\?.+)?)$""".r

  val imageExtension = Array(".bmp", ".gif", ".ico", ".jpe", ".jpeg", ".jpg", ".png", ".svg")
  val soundExtension = Array(".aiff", ".amr", ".flac", ".m4a", ".mp3", ".oga", ".ogg", ".opus", ".wav")
  val videoExtension = Array(".3gp", ".avi", ".m4v", ".mov", ".mp4", ".mpg", ".ogm", ".ogv", ".webm", ".wmv")

  def isValidImageURL(url: String) = !imageParser.unapplySeq(url.trim.toLowerCase).isEmpty
  def isValidSoundURL(url: String) = !soundParser.unapplySeq(url.trim.toLowerCase).isEmpty
  def isValidVideoURL(url: String) = !videoParser.unapplySeq(url.trim.toLowerCase).isEmpty

  def isValidImage(filename: String) = endsWithOneOf(imageExtension, filename) || !imageParser.findAllMatchIn(filename).isEmpty
  def isValidSound(filename: String) = endsWithOneOf(soundExtension, filename) || !soundParser.findAllMatchIn(filename).isEmpty
  def isValidVideo(filename: String) = endsWithOneOf(videoExtension, filename) || !videoParser.findAllMatchIn(filename).isEmpty

  def getImageFormats(filenameOrID: String) : java.util.Map[String, String]

  def isMediaFile(file: File) : Boolean = {
    val name = file.getAbsolutePath()
    endsWithOneOf(imageExtension, name) || endsWithOneOf(soundExtension, name) || endsWithOneOf(videoExtension, name)
  }

  def endsWithOneOf(acceptedExtensions: Array[String], url: String): Boolean =
    !(acceptedExtensions collectFirst { case x if url.toLowerCase().endsWith(x) => x } isEmpty)

  protected def extractSimpleFileName(urlToMedia: String): String = {
    val base = urlToMedia.substring(urlToMedia.lastIndexOf("/") + 1).trim
    val queryPart = base.lastIndexOf("?")
    if (queryPart < 0) base else base.substring(0, queryPart).trim
  }

  protected def extractFileName(urlToMedia: String): String = if (urlToMedia.contains("fileName=")) {
    // HACK for CS URLs which dont make for nice file names
    urlToMedia.substring(urlToMedia.indexOf("fileName=") + "fileName=".length).replace(" ", "_")
  } else if (urlToMedia.contains("?id=") && urlToMedia.contains("imgType=")) {
    // HACK for Morphbank URLs which don't make nice file names
    extractSimpleFileName(urlToMedia).replace("?id=", "").replace("&imgType=", ".")
  } else if (urlToMedia.lastIndexOf("/") == urlToMedia.length - 1) {
    "raw"
  } else if(Config.hashImageFileNames) {
    val md = MessageDigest.getInstance("MD5")
    val fileName = extractSimpleFileName(urlToMedia)
    val extension = FilenameUtils.getExtension(fileName)
    if(extension != null && extension != "") {
      DigestUtils.md5Hex(fileName) + "." + extension
    } else {
      DigestUtils.md5Hex(fileName)
    }
  } else {
    extractSimpleFileName(urlToMedia).replace(" ", "_")
  }

  /**
   * Test to see if the supplied file is already stored in the media store.
   * Returns tuple with:
   *
   * boolean - true if stored
   * String - original file name
   * String - identifier or filesystem path to where the media is stored.
   *
   * @param uuid
   * @param resourceUID
   * @param urlToMedia
   * @return
   */
  def alreadyStored(uuid: String, resourceUID: String, urlToMedia: String) : (Boolean, String, String)

  /**
   * Checks to see if the supplied media file is accessible on the file system
   * or over http.
   *
   * @param urlString
   * @return true if successful
   */
  def isAccessible(urlString: String): Boolean = {

    if(StringUtils.isBlank(urlString)) {
      return false
    }

    val urlToTest = if(urlString.startsWith(Config.mediaFileStore)) {
      "file://" + urlString
    } else {
      urlString
    }

    var in: java.io.InputStream = null

    try {
      val url = new java.net.URL(urlToTest.replaceAll(" ", "%20"))
      in = url.openStream
      true
    } catch {
      case e:Exception => {
        logger.debug("File with URI '" + urlString + "' is not accessible: " + e.getMessage())
        false
      }
    } finally {
      if (in != null) {
        in.close()
      }
    }
  }

  /**
   * Save the supplied media file returning a handle for retrieving
   * the media file.
   *
   * @param uuid Media uuid
   * @param resourceUID Resource associated with the media
   * @param urlToMedia The media source
   * @param media Optional multimedia instance containing additional metadata
   *
   * @return
   */
  def save(uuid: String, resourceUID: String, urlToMedia: String, media: Option[Multimedia]) : Option[(String, String)]

  def delete(filePath: String) : Unit

  def loadMetadata(filePath: String): java.util.Map[String, String]

  def getSoundFormats(filePath: String): java.util.Map[String, String]

  def convertPathsToUrls(fullRecord: FullRecord, baseUrlPath: String) : Unit

  def convertPathToUrl(str: String, baseUrlPath: String) : String

  def convertPathToUrl(str: String) : String
}

/**
 * A file store for media files that uses the local filesystem.
 *
 * @author Dave Martin
 */
object LocalMediaStore extends MediaStore {

  override val logger = LoggerFactory.getLogger("LocalMediaStore")

  import scala.collection.JavaConversions._

  /** Some unix filesystems has a limit of 32k files per directory */
  val limit = 32000

  def getImageFormats(fileName: String) : java.util.Map[String, String] = {
    val url = convertPathToUrl(fileName)
    val dp = url.lastIndexOf(".")
    val extension = if (dp >= 0) url.substring(dp) else ""
    val map = new util.HashMap[String, String]
    // some files will not have an extension - also some files are not images...
    if (extension.isEmpty()) {
      map.put("thumb", url + "__thumb")
      map.put("small", url + "__small")
      map.put("large", url + "__large")
  } else if (imageExtension.contains(extension.toLowerCase)) {
      val base = url.substring(0, dp)
      map.put("thumb", base + "__thumb" + extension)
      map.put("small", base + "__small" + extension)
      map.put("large", base + "__large" + extension)
    }
    map.put("raw", url)
    map
  }

  def convertPathsToUrls(fullRecord: FullRecord, baseUrlPath: String) = if (fullRecord.occurrence.images != null) {
    fullRecord.occurrence.images = fullRecord.occurrence.images.map(x => convertPathToUrl(x, baseUrlPath))
  }

  def convertPathToUrl(str: String, baseUrlPath: String) = str.replaceAll(Config.mediaFileStore, baseUrlPath)

  def convertPathToUrl(str: String) = str.replaceAll(Config.mediaFileStore, Config.mediaBaseUrl)

  def alreadyStored(uuid: String, resourceUID: String, urlToMedia: String) : (Boolean, String, String) = {
    val path = createFilePath(uuid, resourceUID, urlToMedia)
    (new File(path).exists, extractFileName(urlToMedia), path)
  }

  /**
   * Create a file path for this UUID and resourceUID
   */
  private def createFilePath(uuid: String, resourceUID: String, urlToMedia: String): String = {
    val subdirectory = (uuid.hashCode % limit).abs

    val absoluteDirectoryPath = Config.mediaFileStore +
      File.separator + resourceUID +
      File.separator + subdirectory +
      File.separator + uuid

    val directory = new File(absoluteDirectoryPath)
    if (!directory.exists) {
      FileUtils.forceMkdir(directory)
    }

    directory.getAbsolutePath + File.separator + extractFileName(urlToMedia)
  }

  /**
   * Creates/Updates the metadata associated with this file.
   *
   * @param fullPath
   * @param media
   */
  private def updateMetadata(name: String, fullPath: String, media: Option[Multimedia]): Unit = {
      if(media.isDefined  && !fullPath.isEmpty) {
        var metaFileStreamOut: java.io.FileOutputStream = null

        try {
          val metaProps = new Properties()

          media.get.metadata.foreach {
            case(header, value) => metaProps.setProperty(header, value)
          }

          val metaFile = new File(fullPath + ".properties")
          metaFileStreamOut = new FileOutputStream(metaFile)
          metaProps.store(metaFileStreamOut, fullPath)

        } catch {
          case e: Exception =>
            logger.warn(s"Unable save file meta for $name | $e.getMessage")
            None

        } finally {
          if(metaFileStreamOut != null) {
            metaFileStreamOut.close()
          }
        }
      }
  }

  /*
   * Try to load .properties file into a map or return empty map
   */
  def loadMetadata(filePath: String): java.util.Map[String, String] = {
      val fileMeta = new java.util.HashMap[String, String]()
      var metaFileStreamIn: java.io.FileInputStream = null

      // for prop in properties
      val metaFile = new File(filePath + ".properties")
      metaFile.exists match {
        case true => {
          val metaProps = new Properties()
          try {
            metaFileStreamIn = new FileInputStream(metaFile)
            metaProps.load(metaFileStreamIn)

          } finally {
            if(metaFileStreamIn != null) {
              metaFileStreamIn.close()
            }
          }

          metaProps.foreach {
            case(key, value) => fileMeta.put(key, value)
          }
        }
        case false => Array()
      }

      val fileFormat = fileMeta.getOrElse("format", "")
      if(fileFormat == "") {
          val extension = FilenameUtils.getExtension(filePath).toLowerCase()
          fileMeta.put("format", extension)
      }

      return fileMeta;
  }

  /**
   * Saves the file to local filesystem and returns the file path where the file is stored.
   */
  def save(uuid: String, resourceUID: String, urlToMedia: String,
           media: Option[Multimedia]): Option[(String, String)] = {

    // check to see if the media is already stored
    val (stored, name, path) = alreadyStored(uuid, resourceUID, urlToMedia)

    logger.debug(s"media: $media | stored: $stored | name: $name")

    var result = Some("", "")

    if(stored) {
      logger.debug("Media already stored to: " + path)
      result = Some(name, path)
      // if file exists, still try to update metadata
      updateMetadata(name, path, media)
    } else {

      // handle the situation where the urlToMedia does not exits -
      var in: java.io.InputStream = null
      var out: java.io.FileOutputStream = null

      var fullPath = "";
      try {
        fullPath = createFilePath(uuid, resourceUID, urlToMedia)
        val file = new File(fullPath)
        if (!file.exists() || file.length() == 0) {
          val url = new java.net.URL(urlToMedia.replaceAll(" ", "%20"))
          in = url.openStream
          out = new FileOutputStream(file)
          val buffer: Array[Byte] = new Array[Byte](1024)
          var numRead = 0
          while ({
            numRead = in.read(buffer)
            numRead != -1
          }) {
            out.write(buffer, 0, numRead)
            out.flush
          }
          logger.debug("File saved to: " + fullPath)
          // is this file an image???
          if (isValidImageURL(urlToMedia)) {
            Thumbnailer.generateAllSizes(new File(fullPath))
          } else {
            logger.debug("Invalid media file. Not generating derivatives for: " + fullPath)
          }
        } else {
          logger.debug("File previously saved to: " + fullPath)
          if (isValidImageURL(urlToMedia)) {
            Thumbnailer.generateAllSizes(new File(fullPath))
          } else {
            logger.debug("Invalid media file. Not generating derivatives for: " + fullPath)
          }
        }
        // store the media
        result = Some((extractFileName(urlToMedia), fullPath))
      } catch {
        case e: Exception =>
          logger.warn("Unable to load media " + urlToMedia + ". " + e.getMessage);
          None
      } finally {
        if(in != null) {
          in.close()
        }

        if(out != null) {
          out.close()
        }
      }

      // after file save try to store/update file metadata as key=value properties file
      updateMetadata(name, fullPath, media)
    }

    result
  }

  def delete(filePath: String) : Unit = {
      val dp = filePath.lastIndexOf(".")
      val extension = if (dp >= 0) filePath.substring(dp) else ""
      val base = filePath.substring(0, dp)

      FileUtils.deleteQuietly(new File(base + "__thumb" + extension))
      FileUtils.deleteQuietly(new File(base + "__small" + extension))
      FileUtils.deleteQuietly(new File(base + "__large" + extension))
      FileUtils.deleteQuietly(new File(filePath + ".properties"))
      FileUtils.deleteQuietly(new File(filePath))
  }

  val extensionToMimeTypes = Map(
    "aiff" -> "audio/aiff",
    "amr" -> "audio/amr",
    "flac" -> "audio/flac",
    "m4a" -> "audio/mp4",
    "mp3" -> "audio/mpeg",
    "oga" -> "audio/oga",
    "ogg" -> "audio/ogg",
    "opus" -> "audio/opus",
    "wav" -> "audio/wav",
    "mp4" -> "audio/mp4"
  )

  def getSoundFormats(filePath: String): java.util.Map[String, String] = {
    val formats = new java.util.HashMap[String, String]()
    val file = new File(filePath)
    file.exists match {
      case true => {
        val filenames = file.getParentFile.list(new SameNameDifferentExtensionFilter(filePath))
        filenames.foreach(f => {
          val extension = FilenameUtils.getExtension(f).toLowerCase()
          val mimeType = extensionToMimeTypes.getOrElse(extension, "")
          formats.put(mimeType, convertPathToUrl(file.getParent + File.separator + f))
        })
      }
      case false => Array()
    }

    formats
  }
}

class SameNameDifferentExtensionFilter(name: String) extends FilenameFilter {
  val nameToMatch = FilenameUtils.removeExtension(FilenameUtils.getName(name)).toLowerCase
  def accept(dir: File, name: String) = FilenameUtils.removeExtension(name.toLowerCase) == nameToMatch
}

trait ImageSize {
  def suffix: String
  def size: Float
}

object THUMB extends ImageSize {
  def suffix = "__thumb"
  def size = 100f
}

object SMALL extends ImageSize {
  def suffix = "__small"
  def size = 314f
}

object LARGE extends ImageSize {
  def suffix = "__large"
  def size = 650f
}

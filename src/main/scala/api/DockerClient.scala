package api

import api.DockerClientConfig.APIRequired._
import model._
import org.scalajs.dom.ext.Ajax
import org.scalajs.dom.raw._
import upickle._
import util.EventsCustomParser.DockerEventStream
import util.PullEventsCustomParser.{EventStatus, EventStream}
import util.logger._
import util.{EventsCustomParser, PullEventsCustomParser}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

object DockerClientConfig {
  val HttpTimeOut = 10 * 1000
  val PingTimeOut = 4 * 1000
  val DockerVersion = s"v$Mayor.$Minor"

  object APIRequired {
    val Mayor = 1
    val Minor = 17
  }
}

case class DockerClient(connection: Connection) {

  import DockerClientConfig._

  val url = connection.url + "/" + DockerVersion

  // https://docs.docker.com/reference/api/docker_remote_api_v1.17/#ping-the-docker-server
  def ping(): Future[Unit] =
    Ajax.get(url = s"$url/_ping", timeout = PingTimeOut).map { xhr =>
      log.info("[dockerClient.ping] return: " + xhr.responseText)
    }


  def metadata(): Future[DockerMetadata] = for {
    test <- ping()
    info <- info()
    version <- version()
    containers <- containers(all = false)
  } yield DockerMetadata(connection, info, version, containers)


  def containersInfo(): Future[ContainersInfo] = for {
    r <- containers(all = false)
    all <- containers(all = true)
  } yield ContainersInfo(r, all)


  // https://docs.docker.com/reference/api/docker_remote_api_v1.17/#inspect-a-container
  def containerInfo(containerId: String): Future[ContainerInfo] =
    Ajax.get(s"$url/containers/$containerId/json", timeout = HttpTimeOut).map { xhr =>
      log.debug("[dockerClient.containerInfo]")
      read[ContainerInfo](xhr.responseText)
    }


  // https://docs.docker.com/reference/api/docker_remote_api_v1.17/#stop-a-container
  def stopContainer(containerId: String): Future[ContainerInfo] =
    Ajax.post(s"$url/containers/$containerId/stop?t=3", timeout = HttpTimeOut)
      .flatMap(_ => containerInfo(containerId))


  def startContainer(containerId: String): Future[ContainerInfo] = {
    Ajax.post(s"$url/containers/$containerId/start", timeout = HttpTimeOut).flatMap(_ => containerInfo(containerId))
  }

  def removeContainer(containerId: String): Future[Unit] =
    Ajax.delete(s"$url/containers/$containerId", timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.removeContainer] return: " + xhr.readyState)
    }

  def top(containerId: String): Future[ContainerTop] =
    Ajax.get(s"$url/containers/$containerId/top", timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.top] return: " + xhr.responseText)
      read[ContainerTop](xhr.responseText)
    }

  //https://docs.docker.com/reference/api/docker_remote_api_v1.17/#list-images
  def images(): Future[Seq[Image]] =
    Ajax.get(s"$url/images/json", timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.images] ")
      read[Seq[Image]](xhr.responseText)
    }.map {
      _.sortBy(-_.Created)
        .filterNot(_.RepoTags.contains("<none>:<none>"))
    }


  def imageInfo(imageId: String): Future[ImageInfo] =
    Ajax.get(s"$url/images/$imageId/json", timeout = HttpTimeOut).map { xhr =>
      log.debug("[dockerClient.imageInfo] ")
      read[ImageInfo](xhr.responseText)
    }

  def imageHistory(imageId: String): Future[Seq[ImageHistory]] =
    Ajax.get(s"$url/images/$imageId/history", timeout = HttpTimeOut).map { xhr =>
      log.debug("[dockerClient.imageHistory]")
      read[Seq[ImageHistory]](xhr.responseText)
    }

  def imagesSearch(term: String): Future[Seq[ImageSearch]] =
    Ajax.get(s"$url/images/search?term=${term.toLowerCase}", timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.imagesSearch]")
      read[Seq[ImageSearch]](xhr.responseText)
    }

  // https://docs.docker.com/reference/api/docker_remote_api_v1.17/#list-containers
  private def containers(all: Boolean): Future[Seq[Container]] =
    Ajax.get(s"$url/containers/json?all=$all", timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.containers]")
      read[Seq[Container]](xhr.responseText)
    }


  private def info(): Future[Info] =
    Ajax.get(s"$url/info", timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.info] return: " + xhr.responseText)
      read[Info](xhr.responseText)
    }


  private def version(): Future[Version] =
    Ajax.get(s"$url/version", timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.version] return: " + xhr.responseText)
      read[Version](xhr.responseText)
    }

  def attachToContainer(containerId: String): WebSocket = {
    import util.StringUtils._
    val schema = if (connection.url.startsWith("http://")) "ws://" else "wss://"
    val ws = schema + substringAfter(s"$url/containers/$containerId/attach/ws?logs=1&stderr=1&stdout=1&stream=1&stdin=1", "://")
    log.info(s"[dockerClient.attach] url: $ws")
    new WebSocket(ws)
  }

  def pullImage(term: String)(update: Seq[EventStatus] => Unit): Future[Seq[EventStatus]] = {
    val Loading = 3
    val Done = 4
    val p = Promise[Seq[EventStatus]]
    val xhr = new XMLHttpRequest()

    val currentStream = EventStream()
    xhr.onreadystatechange = { event: Event =>
      PullEventsCustomParser.parse(currentStream, xhr.responseText)
      if (xhr.readyState == Loading) {
        update(currentStream.events)
      } else if (xhr.readyState == Done) {
        p.success(currentStream.events)
      }
    }

    xhr.open("POST", s"$url/images/create?fromImage=$term", async = true)
    log.info(s"[dockerClient.pullImage] start")
    xhr.send()
    p.future
  }

  def createContainer(name: String, request: CreateContainerRequest): Future[CreateContainerResponse] =
    Ajax.post(s"$url/containers/create?name=$name",
      write(request),
      headers = Map("Content-Type" -> "application/json"),
      timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.version] return: " + xhr.responseText)
      read[CreateContainerResponse](xhr.responseText)
    }

  def events(update: Seq[DockerEvent] => Unit): Future[Seq[DockerEvent]] = {
    log.info("[dockerClient.events] start")
    val since = {
      val t = new js.Date()
      t.setDate(t.getDate() - 3) // pull 3 days
      t.getTime() / 1000
    }.toLong

    val Loading = 3
    val Done = 4
    val p = Promise[Seq[DockerEvent]]
    val xhr = new XMLHttpRequest()

    val currentStream = DockerEventStream()
    xhr.onreadystatechange = { event: Event =>
      EventsCustomParser.parse(currentStream, xhr.responseText)
      if (xhr.readyState == Loading) {
        update(currentStream.events)
      } else if (xhr.readyState == Done) {
        p.success(currentStream.events)
      }
    }

    val eventsUrl = s"$url/events?since=$since"
    xhr.open("GET",eventsUrl, async = true)
    log.info(s"[dockerClient.events] start:  $eventsUrl")
    xhr.send()
    p.future
  }


  def containerChanges(containerId: String): Future[Seq[FileSystemChange]] = {
    Ajax.get(s"$url/containers/$containerId/changes", timeout = HttpTimeOut).map { xhr =>
      log.info("[dockerClient.containerChanges]")
      read[Seq[FileSystemChange]](xhr.responseText)
    }
  }
}

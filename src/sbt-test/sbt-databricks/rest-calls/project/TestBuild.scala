import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import org.apache.http.entity.StringEntity
import org.apache.http.{ProtocolVersion, HttpResponse}
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.{HttpGet, HttpUriRequest}
import org.apache.http.message.BasicHttpResponse
import org.mockito.Matchers.{any, anyString}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar.{mock => mmock}

import sbtdatabricks._
import sbtdatabricks.DatabricksPlugin._
import sbtdatabricks.DatabricksPlugin.autoImport._

import scala.io.Source
import sbt._
import Keys._

object TestBuild extends Build {

  val mapper = new ObjectMapper() with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)

  val dbcSettings = Seq(
    dbcApiUrl := "dummy",
    dbcUsername := "test",
    dbcPassword := "test"
  )

  lazy val root = Project(id = "root", base = file("."),
    settings = dbcSettings)

  val exampleClusters = Seq(
    Cluster("a", "1", "running", "123", "234", 2),
    Cluster("b", "2", "running", "123", "234", 2),
    Cluster("c", "3", "running", "123", "234", 2))

  def clusterFetchTest: Seq[Setting[_]] = {
    val expect = Seq(Cluster("a", "1", "running", "123", "234", 2))
    val response = mapper.writeValueAsString(expect)
    Seq(
      dbcApiClient := mockClient(Seq(response), file("1") / "output.txt"),
      TaskKey[Unit]("test") := {
        val (fetchClusters, _) = dbcFetchClusters.value
        if (fetchClusters.length != 1) sys.error("Returned wrong number of clusters.")
        if (expect(0) != fetchClusters(0)) sys.error("Cluster not returned properly.")
      }
    )
  }

  lazy val test1 = Project(id = "clusterFetch", base = file("1"),
    settings = dbcSettings ++ clusterFetchTest)

  def libraryFetchTest: Seq[Setting[_]] = {
    val expect = Seq(LibraryListResult("1", "abc", "/"), LibraryListResult("2", "abc", "/def/"),
      LibraryListResult("3", "ghi", "/"))
    val response = mapper.writeValueAsString(expect)
    Seq(
      dbcApiClient := mockClient(Seq(response), file("2") / "output.txt"),
      TaskKey[Unit]("test") := {
        val libraries = dbcFetchLibraries.value
        if (libraries.size != 2) sys.error("Returned wrong number of libraries.")
        if (libraries("abc").size != 2) sys.error("Returned wrong number of libraries.")
        if (libraries("ghi").size != 1) sys.error("Returned wrong number of libraries.")
      }
    )
  }

  lazy val test2 = Project(id = "libraryFetch", base = file("2"),
    settings = dbcSettings ++ libraryFetchTest)

  def uploadedLibResponse(id: String): String = {
    val uploads = UploadedLibraryId(id)
    mapper.writeValueAsString(uploads)
  }

  def libraryUploadTest: Seq[Setting[_]] = {
    val res = mapper.writeValueAsString(Seq.empty[LibraryListResult])
    val outputFile = file("3") / "output.txt"
    Seq(
      dbcApiClient := mockClient(Seq(res, uploadedLibResponse("1"), uploadedLibResponse("2"),
        uploadedLibResponse("3")), outputFile),
      libraryDependencies += "com.databricks" %% "spark-csv" % "1.0.0",
      TaskKey[Unit]("test") := {
        dbcUpload.value
        val output = Source.fromFile(outputFile).getLines().toSeq
        // 2 from Spark csv, 1 from test2, 1 from test3
        if (output.length != 4) sys.error("Wrong number of libraries uploaded.")
        output.foreach { line =>
          if (!line.contains("Uploading")) sys.error("Upload message not printed")
        }
      }
    )
  }

  lazy val test3 = Project(id = "libraryUpload", base = file("3"),
    settings = dbcSettings ++ libraryUploadTest, dependencies = Seq(test2))

  def oldLibraryDeleteTest: Seq[Setting[_]] = {
    val expect = Seq(
      LibraryListResult("1", "test4_2.10-0.1-SNAPSHOT.jar", "/"),
      LibraryListResult("2", "abc", "/def/"),
      LibraryListResult("3", "test4_2.10-0.1-SNAPSHOT.jar", "/jkl"),
      LibraryListResult("4", "spark-csv_2.10-1.0.0.jar", "/"))
    val res = mapper.writeValueAsString(expect)
    val outputFile = file("4") / "output.txt"
    Seq(
      name := "test4",
      version := "0.1-SNAPSHOT",
      dbcApiClient := mockClient(Seq(res, "", // delete test4 because it is a SNAPSHOT version
        uploadedLibResponse("5"), uploadedLibResponse("6"), uploadedLibResponse("7")), outputFile),
      libraryDependencies += "com.databricks" %% "spark-csv" % "1.0.0",
      TaskKey[Unit]("test") := {
        dbcUpload.value
        val output = Source.fromFile(outputFile).getLines().toSeq
        // 1 from Spark csv (upload common-csv, the dependency),
        // 1 from deleting test4 (the one in /jkl is omitted), 1 from uploading test4
        if (output.length != 3) sys.error("Wrong number of updates printed.")
        output.zipWithIndex.foreach { case (line, index) =>
          if (index > 0) {
            if (!line.contains("Uploading")) sys.error("Upload message not printed")
          } else {
            if (!line.contains("Deleting")) sys.error("Delete message not printed")
          }
        }
      }
    )
  }

  lazy val test4 = Project(id = "oldLibraryDelete", base = file("4"),
    settings = dbcSettings ++ oldLibraryDeleteTest)

  def clusterRestartTest: Seq[Setting[_]] = {
    val response = mapper.writeValueAsString(exampleClusters)
    val outputFile = file("5") / "output.txt"
    Seq(
      dbcApiClient := mockClient(Seq(response), outputFile),
      dbcClusters += "a",
      dbcClusters += "b",
      TaskKey[Unit]("test") := {
        dbcRestartClusters.value
        val output = Source.fromFile(outputFile).getLines().toSeq
        if (output.length != 2) sys.error("Wrong number of cluster restarts printed.")
        output.foreach { line =>
          if (!line.contains("Restarting cluster:")) sys.error("Restart message not printed")
        }
      }
    )
  }

  lazy val test5 = Project(id = "clusterRestart", base = file("5"),
    settings = dbcSettings ++ clusterRestartTest)

  def clusterRestartAllTest: Seq[Setting[_]] = {
    val response = mapper.writeValueAsString(exampleClusters)
    val outputFile = file("6") / "output.txt"
    Seq(
      dbcApiClient := mockClient(Seq(response), outputFile),
      dbcClusters += "a", // useless. There to check if we don't do cluster `a` twice
      dbcClusters += "ALL_CLUSTERS",
      TaskKey[Unit]("test") := {
        dbcRestartClusters.value
        val output = Source.fromFile(outputFile).getLines().toSeq
        if (output.length != 3) sys.error("Wrong number of cluster restarts printed.")
        output.foreach { line =>
          if (!line.contains("Restarting cluster:")) sys.error("Restart message not printed")
        }
      }
    )
  }

  lazy val test6 = Project(id = "clusterRestartAll", base = file("6"),
    settings = dbcSettings ++ clusterRestartAllTest)

  def libAttachTest: Seq[Setting[_]] = {
    val existingLibs = Seq(
      LibraryListResult("1", "test7_2.10-0.1-SNAPSHOT.jar", "/"),
      LibraryListResult("2", "abc", "/def/"),
      LibraryListResult("3", "test7_2.10-0.1-SNAPSHOT.jar", "/jkl"),
      LibraryListResult("4", "spark-csv_2.10-1.0.0.jar", "/"))
    val response1 = mapper.writeValueAsString(exampleClusters)
    val response2 = mapper.writeValueAsString(existingLibs)
    val outputFile = file("7") / "output.txt"
    Seq(
      dbcApiClient := mockClient(Seq(response1, response2), outputFile),
      dbcClusters += "a",
      dbcClusters += "b",
      name := "test7",
      version := "0.1-SNAPSHOT",
      libraryDependencies += "com.databricks" %% "spark-csv" % "1.0.0",
      TaskKey[Unit]("test") :=  {
        dbcAttach.value
        val output = Source.fromFile(outputFile).getLines().toSeq
        // 2 clusters x 2 libraries (test7 + spark-csv (dependency not in path, therefore skip))
        if (output.length != 4) sys.error("Wrong number of messages printed.")
        output.foreach { line =>
          if (!line.contains("Attaching") || !line.contains("to cluster")) {
            sys.error("Restart message not printed")
          }
        }
      }
    )
  }

  lazy val test7 = Project(id = "libAttach", base = file("7"),
    settings = dbcSettings ++ libAttachTest)

  def libAttachAllTest: Seq[Setting[_]] = {
    val existingLibs = Seq(
      LibraryListResult("1", "test8_2.10-0.1-SNAPSHOT.jar", "/"),
      LibraryListResult("2", "abc", "/def/"),
      LibraryListResult("3", "test8_2.10-0.1-SNAPSHOT.jar", "/jkl"),
      LibraryListResult("4", "spark-csv_2.10-1.0.0.jar", "/"))
    val response1 = mapper.writeValueAsString(exampleClusters)
    val response2 = mapper.writeValueAsString(existingLibs)
    val outputFile = file("8") / "output.txt"
    Seq(
      dbcApiClient := mockClient(Seq(response1, response2), outputFile),
      dbcClusters += "a", // useless
      dbcClusters += "ALL_CLUSTERS",
      name := "test8",
      version := "0.1-SNAPSHOT",
      libraryDependencies += "com.databricks" %% "spark-csv" % "1.0.0",
      TaskKey[Unit]("test") := {
        dbcAttach.value
        val output = Source.fromFile(outputFile).getLines().toSeq
        // 3 clusters x 2 libraries (test8 + spark-csv (dependency not in path, therefore skip))
        if (output.length != 6) sys.error("Wrong number of cluster restarts printed.")
        output.foreach { line =>
          if (!line.contains("Attaching") || !line.contains("to cluster")) {
            sys.error("Restart message not printed")
          }
        }
      }
    )
  }

  lazy val test8 = Project(id = "libAttachAll", base = file("8"),
    settings = dbcSettings ++ libAttachAllTest)

  def deployTest: Seq[Setting[_]] = {
    val initialLibs = Seq(LibraryListResult("2", "abc", "/def/"))
    val libraryFetch = mapper.writeValueAsString(initialLibs)
    val clusterList = mapper.writeValueAsString(exampleClusters)
    val outputFile = file("9") / "output.txt"
    Seq(
      /* Work flow:
        1- Fetch all clusters from DBC
        2- Fetch existing libraries, see if any jars in the classpath match those libraries
        3- Fetch libraries once again (in order to delete inside dbcUpload)
        4- Upload all jars to DBC
        5- Attach libraries to the clusters
      */
      dbcApiClient := mockClient(Seq(clusterList, libraryFetch, libraryFetch,
        uploadedLibResponse("1"), uploadedLibResponse("3"), uploadedLibResponse("4")), outputFile),
      dbcClusters += "a",
      dbcLibraryPath := "/def/",
      name := "test9",
      version := "0.1-SNAPSHOT",
      libraryDependencies += "com.databricks" %% "spark-csv" % "1.0.0",
      TaskKey[Unit]("test") := {
        dbcDeploy.value
        val out = Source.fromFile(outputFile).getLines().toSeq
        if (out.length != 6) sys.error("Wrong number of messages printed.")
        if (!out(0).contains("Uploading")) sys.error("Upload message not printed")
        if (!out(1).contains("Uploading")) sys.error("Upload message not printed")
        if (!out(2).contains("Uploading")) sys.error("Upload message not printed")
        if (!out(3).contains("Attaching")) sys.error("Attach message not printed")
        if (!out(4).contains("Attaching")) sys.error("Attach message not printed")
        if (!out(5).contains("Attaching")) sys.error("Attach message not printed")
      }
    )
  }

  lazy val test9 = Project(id = "deploy", base = file("9"),
    settings = dbcSettings ++ deployTest)

  def secondDeployTest: Seq[Setting[_]] = {
    val initialLibs = Seq(
      LibraryListResult("1", "test10_2.10-0.1-SNAPSHOT.jar", "/def/"),
      LibraryListResult("2", "abc", "/def/"),
      LibraryListResult("3", "commons-csv-1.1.jar", "/def/"),
      LibraryListResult("4", "spark-csv_2.10-1.0.0.jar", "/def/"))
    val libraryFetch = mapper.writeValueAsString(initialLibs)
    val clusterList = mapper.writeValueAsString(exampleClusters)
    def generateLibStatus(id: String, name: String): String = {
      val libStatus = LibraryStatus(id, name, "/def/", "java-jar", List(name), false,
        List(LibraryClusterStatus("1", "Attached"), LibraryClusterStatus("2", "Detached"),
          LibraryClusterStatus("3", "Detached")))
      mapper.writeValueAsString(libStatus)
    }
    val t9Res = generateLibStatus("1", "test10_2.10-0.1-SNAPSHOT.jar")
    val outputFile = file("10") / "output.txt"
    Seq(
      /* Work flow:
        1- Fetch clusters from DBC
        2- Fetch existing libraries on DBC
        3- Get status of libraries on DBC that is also on the classpath (that is going to be uploaded)
        4- Fetch all libraries again (I think this comes from dbcUpload)
        5- Delete the older versions of the libraries
        6- Upload newer versions of libraries
        7- Attach the libraries and restart the cluster(s)
        Empty messages correspond to deleteJar, attachJar, and clusterRestart responses
        */
      dbcApiClient := mockClient(Seq(clusterList, libraryFetch,
        t9Res, libraryFetch, "", // delete only the SNAPSHOT jar and re-upload it
        uploadedLibResponse("5"), "", ""), outputFile), // first is attach, last is restart
      dbcClusters += "a",
      dbcLibraryPath := "/def/",
      name := "test10",
      version := "0.1-SNAPSHOT",
      libraryDependencies += "com.databricks" %% "spark-csv" % "1.0.0",
      TaskKey[Unit]("test") := {
        dbcDeploy.value
        val out = Source.fromFile(outputFile).getLines().toSeq
        if (out.length != 4) sys.error("Wrong number of messages printed.")
        if (!out(0).contains("Deleting")) sys.error("Delete message not printed")
        if (!out(1).contains("Uploading")) sys.error("Upload message not printed")
        if (!out(2).contains("Attaching")) sys.error("Attach message not printed")
        if (!out(3).contains("Restarting")) sys.error("Restart message not printed")
      }
    )
  }

  lazy val test10 = Project(id = "secondDeploy", base = file("10"),
    settings = dbcSettings ++ secondDeployTest)

  def serverErrorTest: Seq[Setting[_]] = {
    Seq(
      dbcApiClient := mockServerError("", file("11") / "output.txt"),
      TaskKey[Unit]("test") := {
        dbcFetchClusters.value
      }
    )
  }

  lazy val test11 = Project(id = "serverError", base = file("11"),
    settings = dbcSettings ++ serverErrorTest)

  def mockClient(responses: Seq[String], file: File): DatabricksHttp = {
    val client = mmock[HttpClient]
    val mocks = responses.map { res =>
      val mockReponse = new BasicHttpResponse(new ProtocolVersion("HTTP", 1, 1), 201, null)
      mockReponse.setEntity(new StringEntity(res))
      mockReponse
    }
    when(client.execute(any[HttpUriRequest]())).thenReturn(mocks(0), mocks.drop(1): _*)

    DatabricksHttp.testClient(client, file)
  }

  def mockServerError(responses: String, file: File): DatabricksHttp = {
    val client = mmock[HttpClient]
    val mockReponse = new BasicHttpResponse(new ProtocolVersion("HTTP", 1, 1), 500, null)
    when(client.execute(any[HttpUriRequest]())).thenReturn(mockReponse)
    DatabricksHttp.testClient(client, file)
  }
}



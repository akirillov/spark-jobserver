import sbt._
import sbt.Package.ManifestAttributes
import Keys._
import sbtassembly.Plugin._
import AssemblyKeys._
import spray.revolver.RevolverPlugin._
import spray.revolver.Actions
import com.typesafe.sbt.SbtScalariform._
import com.typesafe.sbt.SbtNativePackager._
import com.typesafe.sbt.SbtGit._
import org.scalastyle.sbt.ScalastylePlugin

import scalariform.formatter.preferences._
import bintray.Plugin.bintrayPublishSettings
import NativePackagerKeys._
import sbtrelease.ReleasePlugin.autoImport.{ReleaseStep, releaseProcess}
import sbtrelease.ReleaseStateTransformations._

// There are advantages to using real Scala build files with SBT:
//  - Multi-JVM testing won't work without it, for now
//  - You get full IDE support
object JobServerBuild extends Build {
  lazy val dirSettings = Seq(
    unmanagedSourceDirectories in Compile <<= Seq(baseDirectory(_ / "src" )).join,
    unmanagedSourceDirectories in Test <<= Seq(baseDirectory(_ / "test" )).join,
    scalaSource in Compile <<= baseDirectory(_ / "src" ),
    scalaSource in Test <<= baseDirectory(_ / "test" )
  )

  import Dependencies._

  // Packages up files into a debian packages.
  //   installDir - The destination where the files get unpacked when the package gets installed.
  //   assemblyName - The name of the jar file.  This specifies to the packager where to find the jar file,
  //       and the name of the jar file gets used as the debian package name as well.
  def packageMappingsSettings(installDir: String, assemblyName: String) = {
    Seq(
      packageSummary := "Spark Job Server",
      packageDescription := "Spark as a Service: a RESTful job server for Apache Spark",
      name in Debian := assemblyName,
      maintainer := "Ooyala Optimization",
      linuxPackageMappings += packageMapping(assembly.value -> (installDir + "/" + assemblyName + ".jar")),
      linuxPackageMappings += packageMapping(file("job-server/config/log4j-server.properties") ->
        (installDir + "/log4j-server.properties"))
    )
  }

  lazy val akkaApp = Project(id = "akka-app", base = file("akka-app"),
    settings = commonSettings210 ++ Seq(
      description := "Common Akka application stack: metrics, tracing, logging, and more.",
      libraryDependencies ++= coreTestDeps ++ akkaDeps ++ monitoringDeps
    )
  )

  lazy val jobServer = Project(id = "job-server", base = file("job-server"),
    settings = packagerSettings ++ commonSettings210 ++ Assembly.settings ++ releaseSettings ++
      packageMappingsSettings("/data/spark/job-server", "spark-job-server") ++ Revolver.settings ++ Seq(
      description := "Spark as a Service: a RESTful job server for Apache Spark",
      libraryDependencies ++= sparkDeps ++ slickDeps ++ monitoringDeps ++ coreTestDeps,

      // Automatically package the test jar when we run tests here
      // And always do a clean before package (package depends on clean) to clear out multiple versions
      test in Test <<= (test in Test).dependsOn(packageBin in Compile in jobServerTestJar)
        .dependsOn(clean in Compile in jobServerTestJar),

      // Adds the path of extra jars to the front of the classpath
      fullClasspath in Compile <<= (fullClasspath in Compile).map { classpath =>
        extraJarPaths ++ classpath
      },
      javaOptions in Revolver.reStart += jobServerLogging,
      // Give job server a bit more PermGen since it does classloading
      javaOptions in Revolver.reStart += "-XX:MaxPermSize=256m",
      javaOptions in Revolver.reStart += "-Djava.security.krb5.realm= -Djava.security.krb5.kdc=",
      // This lets us add Spark back to the classpath without assembly barfing
      fullClasspath in Revolver.reStart := (fullClasspath in Compile).value
    )
  ) dependsOn(
    akkaApp, jobServerApi
  ) settings(
    artifact in (Compile, assembly) ~= { art =>
      art.copy(`classifier` = Some("assembly"))
    }
  ) settings(
    addArtifact(artifact in (Compile, assembly), assembly).settings: _*
  )

  lazy val jobServerTestJar = Project(id = "job-server-tests", base = file("job-server-tests"),
    settings = commonSettings210 ++ Seq(libraryDependencies ++= sparkDeps ++ apiDeps,
      publish      := {},
      description := "Test jar for Spark Job Server",
      exportJars := true)   // use the jar instead of target/classes
  ) dependsOn(jobServerApi)

  lazy val jobServerApi = Project(id = "job-server-api", base = file("job-server-api"),
    settings = commonSettings210 ++ Seq(exportJars := true)
  )

  // This meta-project aggregates all of the sub-projects and can be used to compile/test/style check
  // all of them with a single command.
  //
  // Note: SBT's default project is the one with the first lexicographical variable name, so we
  // prepend "aaa" to the project name here.
  lazy val aaaMasterProject = Project(
    id = "master", base = file("master")
  ) aggregate(jobServer, jobServerApi, jobServerTestJar, akkaApp
  ) settings(
    parallelExecution in Test := false,
    publishArtifact := false,
    concurrentRestrictions := Seq(
      Tags.limit(Tags.CPU, java.lang.Runtime.getRuntime().availableProcessors()),
      // limit to 1 concurrent test task, even across sub-projects
      // Note: some components of tests seem to have the "Untagged" tag rather than "Test" tag.
      // So, we limit the sum of "Test", "Untagged" tags to 1 concurrent
      Tags.limitSum(1, Tags.Test, Tags.Untagged))
  )

  // To add an extra jar to the classpath when doing "re-start" for quick development, set the
  // env var EXTRA_JAR to the absolute full path to the jar
  lazy val extraJarPaths = Option(System.getenv("EXTRA_JAR"))
    .map(jarpath => Seq(Attributed.blank(file(jarpath))))
    .getOrElse(Nil)

  // Create a default Scala style task to run with compiles
  lazy val runScalaStyle = taskKey[Unit]("testScalaStyle")

  lazy val commonSettings210 = Defaults.defaultSettings ++ dirSettings ++ Seq(
    organization := "spark.jobserver",
    version      := "0.4.1",
    crossPaths   := false,
    scalaVersion := "2.10.4",
    scalaBinaryVersion := "2.10",
    packageOptions := Seq(ManifestAttributes(
      ("SHA", git.gitHeadCommit.value.get)
    )),

    runScalaStyle := {
      org.scalastyle.sbt.PluginKeys.scalastyle.toTask("").value
    },
    (compile in Compile) <<= (compile in Compile) dependsOn runScalaStyle,

    // In Scala 2.10, certain language features are disabled by default, such as implicit conversions.
    // Need to pass in language options or import scala.language.* to enable them.
    // See SIP-18 (https://docs.google.com/document/d/1nlkvpoIRkx7at1qJEZafJwthZ3GeIklTFhqmXMvTX9Q/edit)
    scalacOptions := Seq("-deprecation", "-feature",
      "-language:implicitConversions", "-language:postfixOps"),
    resolvers    ++= Dependencies.repos,
    libraryDependencies ++= apiDeps,
    parallelExecution in Test := false,
    // We need to exclude jms/jmxtools/etc because it causes undecipherable SBT errors  :(
    ivyXML :=
      <dependencies>
        <exclude module="jms"/>
        <exclude module="jmxtools"/>
        <exclude module="jmxri"/>
      </dependencies>
  ) ++ scalariformPrefs ++ ScalastylePlugin.Settings ++ scoverageSettings ++ publishSettings

  lazy val scoverageSettings = {
    import ScoverageSbtPlugin._
    instrumentSettings ++ Seq(
      // Semicolon-separated list of regexs matching classes to exclude
      ScoverageKeys.excludedPackages in scoverage := ".+Benchmark.*"
    )
  }

  lazy val publishSettings = Seq(
    publishMavenStyle := true,

    // disable publishing the main API jar
    publishArtifact in (Compile, packageDoc) := false,

    credentials += Credentials(
      Option(System.getProperty("nexus.credentials.file"))
        .map(path => Path.absolute(new File(path)))
        .getOrElse(Path.userHome / ".ivy2" / ".credentials")
    ),

    // Disallow publishing SNAPSHOTs by returning an empty location if try to publish SNAPSHOTs
    publishTo in ThisBuild <<= (version) { version: String =>
      val nexus = "http://nexus.ooyala.com/nexus/content/repositories/"
      if (version.trim.endsWith("SNAPSHOT")) None
      else                                   Some("releases" at nexus + "releases/")
    }
  )

  //simplified release process is used:
  //  - publishing to Nexus only, no versions or tags are commited to the repo
  //  - runTest release step is excluded, tests are executed as a part of assembly
  lazy val releaseSettings = Seq(
    releaseProcess in ThisBuild := Seq[ReleaseStep](
      checkSnapshotDependencies,
      runClean,
      inquireVersions,
      setReleaseVersion,
      publishArtifacts,
      setNextVersion
    )
  )

  // change to scalariformSettings for auto format on compile; defaultScalariformSettings to disable
  // See https://github.com/mdr/scalariform for formatting options
  lazy val scalariformPrefs = defaultScalariformSettings ++ Seq(
    ScalariformKeys.preferences := FormattingPreferences()
      .setPreference(AlignParameters, true)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(PreserveDanglingCloseParenthesis, false)
  )

  // This is here so we can easily switch back to Logback when Spark fixes its log4j dependency.
  lazy val jobServerLogbackLogging = "-Dlogback.configurationFile=config/logback-local.xml"
  lazy val jobServerLogging = "-Dlog4j.configuration=config/log4j-local.properties"
}

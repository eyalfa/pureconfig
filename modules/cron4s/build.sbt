name := "pureconfig-cron4s"

crossScalaVersions ~= { _.filterNot(_.startsWith("2.13")) }

libraryDependencies ++= Seq(
  "com.github.alonsodomin.cron4s" %% "cron4s-core" % "0.5.0")

developers := List(
  Developer("bardurdam", "Bárður Viberg Dam", "bardurdam@gmail.com", url("https://github.com/bardurdam")))

osgiSettings

OsgiKeys.exportPackage := Seq("pureconfig.module.cron4s.*")
OsgiKeys.privatePackage := Seq()
OsgiKeys.importPackage := Seq(s"""scala.*;version="[${scalaBinaryVersion.value}.0,${scalaBinaryVersion.value}.50)"""", "*")

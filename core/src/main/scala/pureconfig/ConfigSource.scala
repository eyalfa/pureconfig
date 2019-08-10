package pureconfig

import java.io.File
import java.net.URL
import java.nio.file.Path

import scala.reflect.ClassTag

import com.typesafe.config._
import pureconfig.ConfigReader.Result
import pureconfig.backend.{ ConfigFactoryWrapper, PathUtil }
import pureconfig.error.{ ConfigReaderException, ConfigReaderFailures }

/**
 * A representation of a source from which `ConfigValue`s can be loaded, such as a file or a URL.
 *
 * A source allows users to load configs from this source as any type for which a `ConfigReader` is
 * available. Raw configs can also be retrieved as a `ConfigValue`, a `ConfigCursor` or a
 * `FluentConfigCursor`. Before using any of the loading methods described, Users can opt to focus
 * on a specific part of a config by specifying a namespace.
 *
 * All config loading methods are lazy and defer resolution of references until needed.
 */
trait ConfigSource {

  /**
   * Retrieves a `ConfigValue` from this source.
   *
   * @return a `ConfigValue` retrieved from this source.
   */
  def value: Result[ConfigValue]

  /**
   * Returns a cursor for a `ConfigValue` retrieved from this source.
   *
   * @return a cursor for a `ConfigValue` retrieved from this source.
   */
  def cursor: Result[ConfigCursor] =
    value.right.map(ConfigCursor(_, Nil))

  /**
   * Returns a fluent cursor for a `ConfigValue` retrieved from this source.
   *
   * @return a fluent cursor for a `ConfigValue` retrieved from this source.
   */
  def fluentCursor: FluentConfigCursor =
    FluentConfigCursor(cursor)

  /**
   * Navigates through the config to focus on a namespace.
   *
   * @param namespace the namespace to focus on
   * @return a new `ConfigSource` focused on the given namespace.
   */
  def at(namespace: String): ConfigSource =
    ConfigSource.fromCursor(fluentCursor.at(PathUtil.splitPath(namespace).map(p => p: PathSegment): _*))

  /**
   * Loads a configuration of type `A` from this source.
   *
   * @tparam A the type of the config to be loaded
   * @return A `Right` with the configuration if it is possible to create an instance of type
   *         `A` from this source, a `Failure` with details on why it isn't possible otherwise
   */
  def load[A](implicit reader: Derivation[ConfigReader[A]]): Result[A] =
    cursor.right.flatMap(reader.value.from)

  /**
   * Loads a configuration of type `A` from this source. If it is not possible to create an
   * instance of `A`, this method throws a `ConfigReaderException`.
   *
   * @tparam A the type of the config to be loaded
   * @return The configuration of type `A` loaded from this source.
   */
  @throws[ConfigReaderException[_]]
  def loadOrThrow[A: ClassTag](implicit reader: Derivation[ConfigReader[A]]): A = {
    load[A] match {
      case Right(config) => config
      case Left(failures) => throw new ConfigReaderException[A](failures)
    }
  }
}

/**
 * A `ConfigSource` which is guaranteed to generate config objects (maps) as root values.
 *
 * @param config the thunk to generate a `Config` instance. This parameter won't be memoized so it
 *               can be used with dynamic sources (e.g. URLs)
 */
class ConfigObjectSource(config: => Result[Config]) extends ConfigSource {

  def value: Result[ConfigObject] =
    config.right.map(_.resolve.root)

  /**
   * Merges this source with another one, with the latter being used as a fallback (e.g. the
   * source on which this method is called takes priority). Both sources are required to produce
   * a config object successfully.
   *
   * @param cs the config source to use as fallback
   * @return a new `ConfigObjectSource` that loads configs from both sources and uses `cs` as a
   *         fallback for this source
   */
  def withFallback(cs: ConfigObjectSource): ConfigObjectSource =
    new ConfigObjectSource(Result.zipWith(config, cs.value.right.map(_.toConfig))(_.withFallback(_)))

  /**
   * Merges this source with another one, with the latter being used as a fallback (e.g. the
   * source on which this method is called takes priority). If the provided source fails to produce
   * a config it is ignored.
   *
   * @param cs the config source to use as fallback
   * @return a new `ConfigObjectSource` that loads configs from both sources and uses `cs` as a
   *         fallback for this source
   */
  def withOptionalFallback(cs: ConfigObjectSource): ConfigObjectSource =
    withFallback(cs.recoverWith({ case _ => Right(ConfigFactory.empty) }))

  /**
   * Applies a function `f` if this source returns a failure, returning an alternative config in
   * those cases.
   *
   * @param f the function to apply if this source returns a failure
   * @return a new `ConfigObjectSource` that provides an alternative config in case this source
   *         fails
   */
  def recoverWith(f: PartialFunction[ConfigReaderFailures, Result[Config]]): ConfigObjectSource =
    new ConfigObjectSource(config.left.flatMap(f))
}

object ConfigObjectSource {

  /**
   * Creates a `ConfigObjectSource` from a `Result[Config]`. The provided argument is allowed
   * to change value over time.
   *
   * @param conf the config to be provided by this source
   * @return a `ConfigObjectSource` providing the given config.
   */
  def apply(conf: => Result[Config]): ConfigObjectSource =
    new ConfigObjectSource(conf)

  /**
   * Creates a `ConfigObjectSource` from a `ConfigObjectCursor`.
   *
   * @param cur the cursor to be provided by this source
   * @return a `ConfigObjectSource` providing the given cursor.
   */
  def fromCursor(cur: ConfigObjectCursor): ConfigObjectSource =
    new ConfigObjectSource(Right(cur.value.toConfig))

  /**
   * Creates a `ConfigObjectSource` from a `FluentConfigCursor`.
   *
   * @param cur the cursor to be provided by this source
   * @return a `ConfigObjectSource` providing the given cursor.
   */
  def fromCursor(cur: FluentConfigCursor): ConfigObjectSource =
    new ConfigObjectSource(cur.asObjectCursor.right.map(_.value.toConfig))
}

object ConfigSource {

  /**
   * A config source for the default loading process in Typesafe Config. Typesafe Config stacks
   * `reference.conf` resources provided by libraries, application configs (by default
   * `application.conf` in resources) and system property overrides, resolves them and merges them
   * into a single config.
   */
  val default = ConfigObjectSource(ConfigFactoryWrapper.load())

  /**
   * A config source that always provides empty configs.
   */
  val empty = ConfigObjectSource(Right(ConfigFactory.empty))

  /**
   * A config source for Java system properties.
   */
  val systemProperties = ConfigObjectSource(ConfigFactoryWrapper.systemProperties())

  /**
   * A config source for the default reference config in Typesafe Config (`reference.conf`
   * resources provided by libraries).
   *
   * As required by
   * [[https://github.com/lightbend/config/blob/master/HOCON.md#conventional-configuration-files-for-jvm-apps the HOCON spec]],
   * the default reference files are pre-emptively resolved - substitutions in the reference config
   * aren't affected by application configs.
   */
  val defaultReference = ConfigObjectSource(ConfigFactoryWrapper.defaultReference())

  /**
   * A config source for the default reference config in Typesafe Config (`reference.conf`
   * resources provided by libraries) before being resolved. This can be used as an alternative
   * to `defaultReference` for use cases that require `reference.conf` to depend on
   * `application.conf`.
   */
  val defaultReferenceUnresolved = resources("reference.conf")

  /**
   * A config source for the default application config in Typesafe Config (by default
   * `application.conf` in resources).
   */
  val defaultApplication = ConfigObjectSource(ConfigFactoryWrapper.defaultApplication())

  /**
   * Returns a config source that provides configs read from a file.
   *
   * @param path the path to the file as a string
   * @return a config source that provides configs read from a file.
   */
  def file(path: String) = ConfigObjectSource(ConfigFactoryWrapper.parseFile(new File(path)))

  /**
   * Returns a config source that provides configs read from a file.
   *
   * @param path the path to the file
   * @return a config source that provides configs read from a file.
   */
  def file(path: Path) = ConfigObjectSource(ConfigFactoryWrapper.parseFile(path.toFile))

  /**
   * Returns a config source that provides configs read from a file.
   *
   * @param file the file
   * @return a config source that provides configs read from a file.
   */
  def file(file: File) = ConfigObjectSource(ConfigFactoryWrapper.parseFile(file))

  /**
   * Returns a config source that provides configs read from a URL. The URL can either point to a
   * local file or to a remote HTTP location.
   *
   * @param url the URL
   * @return a config source that provides configs read from a file.
   */
  def url(url: URL) = ConfigObjectSource(ConfigFactoryWrapper.parseURL(url))

  /**
   * Returns a config source that provides configs read from JVM resource files. If multiple files
   * are found, they are merged in no specific order.
   *
   * @param name the resource name
   * @return a config source that provides configs read from JVM resource files.
   */
  def resources(name: String) = ConfigObjectSource(ConfigFactoryWrapper.parseResources(name))

  /**
   * Returns a config source that provides a config parsed from a string.
   *
   * @param confStr the config content
   * @return a config source that provides a config parsed from a string.
   */
  def string(confStr: String) = ConfigObjectSource(ConfigFactoryWrapper.parseString(confStr))

  /**
   * Returns a config source that provides a fixed `Config`.
   *
   * @param conf the config to be provided
   * @return a config source that provides the given config.
   */
  def fromConfig(conf: Config) = ConfigObjectSource(Right(conf))

  /**
   * Creates a `ConfigSource` from a `ConfigCursor`.
   *
   * @param cur the cursor to be provided by this source
   * @return a `ConfigSource` providing the given cursor.
   */
  def fromCursor(cur: ConfigCursor): ConfigSource = new ConfigSource {
    def value: Result[ConfigValue] = Right(cur.value)
  }

  /**
   * Creates a `ConfigSource` from a `FluentConfigCursor`.
   *
   * @param cur the cursor to be provided by this source
   * @return a `ConfigSource` providing the given cursor.
   */
  def fromCursor(cur: FluentConfigCursor): ConfigSource = new ConfigSource {
    def value: Result[ConfigValue] = cur.cursor.right.map(_.value)
  }
}

package typings.node.anon

import typings.node.globalsMod.global.NodeJS.Dict
import typings.node.nodeStrings.BE
import typings.node.nodeStrings.LE
import typings.node.osMod.CpuInfo
import typings.node.osMod.NetworkInterfaceInfo
import typings.node.osMod.UserInfo_
import typings.node.processMod.global.NodeJS.Platform
import org.scalablytyped.runtime.StObject
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSGlobalScope, JSGlobal, JSImport, JSName, JSBracketAccess}

@js.native
trait TypeofimportedOs extends StObject {
  
  /**
    * The operating system-specific end-of-line marker.
    * * `\n` on POSIX
    * * `\r\n` on Windows
    */
  val EOL: String = js.native
  
  /**
    * Returns the operating system CPU architecture for which the Node.js binary was
    * compiled. Possible values are `'arm'`, `'arm64'`, `'ia32'`, `'loong64'`, `'mips'`, `'mipsel'`, `'ppc'`, `'ppc64'`, `'riscv64'`, `'s390'`, `'s390x'`,
    * and `'x64'`.
    *
    * The return value is equivalent to [process.arch](https://nodejs.org/docs/latest-v22.x/api/process.html#processarch).
    * @since v0.5.0
    */
  def arch(): String = js.native
  
  /**
    * Returns an estimate of the default amount of parallelism a program should use.
    * Always returns a value greater than zero.
    *
    * This function is a small wrapper about libuv's [`uv_available_parallelism()`](https://docs.libuv.org/en/v1.x/misc.html#c.uv_available_parallelism).
    * @since v19.4.0, v18.14.0
    */
  def availableParallelism(): Double = js.native
  
  val constants: TypeofconstantsDlopen = js.native
  
  /**
    * Returns an array of objects containing information about each logical CPU core.
    * The array will be empty if no CPU information is available, such as if the `/proc` file system is unavailable.
    *
    * The properties included on each object include:
    *
    * ```js
    * [
    *   {
    *     model: 'Intel(R) Core(TM) i7 CPU         860  @ 2.80GHz',
    *     speed: 2926,
    *     times: {
    *       user: 252020,
    *       nice: 0,
    *       sys: 30340,
    *       idle: 1070356870,
    *       irq: 0,
    *     },
    *   },
    *   {
    *     model: 'Intel(R) Core(TM) i7 CPU         860  @ 2.80GHz',
    *     speed: 2926,
    *     times: {
    *       user: 306960,
    *       nice: 0,
    *       sys: 26980,
    *       idle: 1071569080,
    *       irq: 0,
    *     },
    *   },
    *   {
    *     model: 'Intel(R) Core(TM) i7 CPU         860  @ 2.80GHz',
    *     speed: 2926,
    *     times: {
    *       user: 248450,
    *       nice: 0,
    *       sys: 21750,
    *       idle: 1070919370,
    *       irq: 0,
    *     },
    *   },
    *   {
    *     model: 'Intel(R) Core(TM) i7 CPU         860  @ 2.80GHz',
    *     speed: 2926,
    *     times: {
    *       user: 256880,
    *       nice: 0,
    *       sys: 19430,
    *       idle: 1070905480,
    *       irq: 20,
    *     },
    *   },
    * ]
    * ```
    *
    * `nice` values are POSIX-only. On Windows, the `nice` values of all processors
    * are always 0.
    *
    * `os.cpus().length` should not be used to calculate the amount of parallelism
    * available to an application. Use {@link availableParallelism} for this purpose.
    * @since v0.3.3
    */
  def cpus(): js.Array[CpuInfo] = js.native
  
  val devNull: String = js.native
  
  /**
    * Returns a string identifying the endianness of the CPU for which the Node.js
    * binary was compiled.
    *
    * Possible values are `'BE'` for big endian and `'LE'` for little endian.
    * @since v0.9.4
    */
  def endianness(): BE | LE = js.native
  
  /**
    * Returns the amount of free system memory in bytes as an integer.
    * @since v0.3.3
    */
  def freemem(): Double = js.native
  
  /**
    * Returns the scheduling priority for the process specified by `pid`. If `pid` is
    * not provided or is `0`, the priority of the current process is returned.
    * @since v10.10.0
    * @param [pid=0] The process ID to retrieve scheduling priority for.
    */
  def getPriority(): Double = js.native
  def getPriority(pid: Double): Double = js.native
  
  /**
    * Returns the string path of the current user's home directory.
    *
    * On POSIX, it uses the `$HOME` environment variable if defined. Otherwise it
    * uses the [effective UID](https://en.wikipedia.org/wiki/User_identifier#Effective_user_ID) to look up the user's home directory.
    *
    * On Windows, it uses the `USERPROFILE` environment variable if defined.
    * Otherwise it uses the path to the profile directory of the current user.
    * @since v2.3.0
    */
  def homedir(): String = js.native
  
  /**
    * Returns the host name of the operating system as a string.
    * @since v0.3.3
    */
  def hostname(): String = js.native
  
  /**
    * Returns an array containing the 1, 5, and 15 minute load averages.
    *
    * The load average is a measure of system activity calculated by the operating
    * system and expressed as a fractional number.
    *
    * The load average is a Unix-specific concept. On Windows, the return value is
    * always `[0, 0, 0]`.
    * @since v0.3.3
    */
  def loadavg(): js.Array[Double] = js.native
  
  /**
    * Returns the machine type as a string, such as `arm`, `arm64`, `aarch64`, `mips`, `mips64`, `ppc64`, `ppc64le`, `s390`, `s390x`, `i386`, `i686`, `x86_64`.
    *
    * On POSIX systems, the machine type is determined by calling [`uname(3)`](https://linux.die.net/man/3/uname). On Windows, `RtlGetVersion()` is used, and if it is not
    * available, `GetVersionExW()` will be used. See [https://en.wikipedia.org/wiki/Uname#Examples](https://en.wikipedia.org/wiki/Uname#Examples) for more information.
    * @since v18.9.0, v16.18.0
    */
  def machine(): String = js.native
  
  /**
    * Returns an object containing network interfaces that have been assigned a
    * network address.
    *
    * Each key on the returned object identifies a network interface. The associated
    * value is an array of objects that each describe an assigned network address.
    *
    * The properties available on the assigned network address object include:
    *
    * ```js
    * {
    *   lo: [
    *     {
    *       address: '127.0.0.1',
    *       netmask: '255.0.0.0',
    *       family: 'IPv4',
    *       mac: '00:00:00:00:00:00',
    *       internal: true,
    *       cidr: '127.0.0.1/8'
    *     },
    *     {
    *       address: '::1',
    *       netmask: 'ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff',
    *       family: 'IPv6',
    *       mac: '00:00:00:00:00:00',
    *       scopeid: 0,
    *       internal: true,
    *       cidr: '::1/128'
    *     }
    *   ],
    *   eth0: [
    *     {
    *       address: '192.168.1.108',
    *       netmask: '255.255.255.0',
    *       family: 'IPv4',
    *       mac: '01:02:03:0a:0b:0c',
    *       internal: false,
    *       cidr: '192.168.1.108/24'
    *     },
    *     {
    *       address: 'fe80::a00:27ff:fe4e:66a1',
    *       netmask: 'ffff:ffff:ffff:ffff::',
    *       family: 'IPv6',
    *       mac: '01:02:03:0a:0b:0c',
    *       scopeid: 1,
    *       internal: false,
    *       cidr: 'fe80::a00:27ff:fe4e:66a1/64'
    *     }
    *   ]
    * }
    * ```
    * @since v0.6.0
    */
  def networkInterfaces(): Dict[js.Array[NetworkInterfaceInfo]] = js.native
  
  /**
    * Returns a string identifying the operating system platform for which
    * the Node.js binary was compiled. The value is set at compile time.
    * Possible values are `'aix'`, `'darwin'`, `'freebsd'`, `'linux'`, `'openbsd'`, `'sunos'`, and `'win32'`.
    *
    * The return value is equivalent to `process.platform`.
    *
    * The value `'android'` may also be returned if Node.js is built on the Android
    * operating system. [Android support is experimental](https://github.com/nodejs/node/blob/HEAD/BUILDING.md#androidandroid-based-devices-eg-firefox-os).
    * @since v0.5.0
    */
  def platform(): Platform = js.native
  
  /**
    * Returns the operating system as a string.
    *
    * On POSIX systems, the operating system release is determined by calling [`uname(3)`](https://linux.die.net/man/3/uname). On Windows, `GetVersionExW()` is used. See
    * [https://en.wikipedia.org/wiki/Uname#Examples](https://en.wikipedia.org/wiki/Uname#Examples) for more information.
    * @since v0.3.3
    */
  def release(): String = js.native
  
  def setPriority(pid: Double, priority: Double): Unit = js.native
  /**
    * Attempts to set the scheduling priority for the process specified by `pid`. If `pid` is not provided or is `0`, the process ID of the current process is used.
    *
    * The `priority` input must be an integer between `-20` (high priority) and `19` (low priority). Due to differences between Unix priority levels and Windows
    * priority classes, `priority` is mapped to one of six priority constants in `os.constants.priority`. When retrieving a process priority level, this range
    * mapping may cause the return value to be slightly different on Windows. To avoid
    * confusion, set `priority` to one of the priority constants.
    *
    * On Windows, setting priority to `PRIORITY_HIGHEST` requires elevated user
    * privileges. Otherwise the set priority will be silently reduced to `PRIORITY_HIGH`.
    * @since v10.10.0
    * @param [pid=0] The process ID to set scheduling priority for.
    * @param priority The scheduling priority to assign to the process.
    */
  def setPriority(priority: Double): Unit = js.native
  
  /**
    * Returns the operating system's default directory for temporary files as a
    * string.
    * @since v0.9.9
    */
  def tmpdir(): String = js.native
  
  /**
    * Returns the total amount of system memory in bytes as an integer.
    * @since v0.3.3
    */
  def totalmem(): Double = js.native
  
  /**
    * Returns the operating system name as returned by [`uname(3)`](https://linux.die.net/man/3/uname). For example, it
    * returns `'Linux'` on Linux, `'Darwin'` on macOS, and `'Windows_NT'` on Windows.
    *
    * See [https://en.wikipedia.org/wiki/Uname#Examples](https://en.wikipedia.org/wiki/Uname#Examples) for additional information
    * about the output of running [`uname(3)`](https://linux.die.net/man/3/uname) on various operating systems.
    * @since v0.3.3
    */
  def `type`(): String = js.native
  
  /**
    * Returns the system uptime in number of seconds.
    * @since v0.3.3
    */
  def uptime(): Double = js.native
  
  def userInfo(): UserInfo_[String] = js.native
  /**
    * Returns information about the currently effective user. On POSIX platforms,
    * this is typically a subset of the password file. The returned object includes
    * the `username`, `uid`, `gid`, `shell`, and `homedir`. On Windows, the `uid` and `gid` fields are `-1`, and `shell` is `null`.
    *
    * The value of `homedir` returned by `os.userInfo()` is provided by the operating
    * system. This differs from the result of `os.homedir()`, which queries
    * environment variables for the home directory before falling back to the
    * operating system response.
    *
    * Throws a [`SystemError`](https://nodejs.org/docs/latest-v22.x/api/errors.html#class-systemerror) if a user has no `username` or `homedir`.
    * @since v6.0.0
    */
  def userInfo(options: `2`): UserInfo_[typings.node.bufferMod.global.Buffer] = js.native
  def userInfo(options: `4`): UserInfo_[String] = js.native
  
  /**
    * Returns a string identifying the kernel version.
    *
    * On POSIX systems, the operating system release is determined by calling [`uname(3)`](https://linux.die.net/man/3/uname). On Windows, `RtlGetVersion()` is used, and if it is not
    * available, `GetVersionExW()` will be used. See [https://en.wikipedia.org/wiki/Uname#Examples](https://en.wikipedia.org/wiki/Uname#Examples) for more information.
    * @since v13.11.0, v12.17.0
    */
  def version(): String = js.native
}

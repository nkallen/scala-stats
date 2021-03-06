/** Copyright 2009, Twitter, Inc. */
package com.twitter.service

import java.lang.management._
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import scala.collection.Map
import scala.collection.immutable
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.ListBuffer


/**
 * Basic Stats gathering object that returns performance data for the application.
 */
object Stats {
  val log = Logger.getLogger("Stats")

  /**
   * Measurement is a base type for collected statistics.
   */
  trait Measurement

  /**
   * A Counter is a measure that simply keeps track of how
   * many times an event occurred.
   */
  class Counter extends Measurement {
    var value = new AtomicLong

    def incr() = value.addAndGet(1)
    def incr(n: Int) = value.addAndGet(n)
    def apply(): Long = value.get()
  }


  /**
   * A Timing collates durations of an event and can report
   * min/max/avg along with how often the event occurred.
   */
  class Timing extends Measurement {
    private var maximum = Math.MIN_INT
    private var minimum = Math.MAX_INT
    private var sum: Long = 0
    private var count: Long = 0

    /**
     * Resets the state of this Timing. Clears the durations and counts collected sofar.
     */
    def clear = synchronized {
      maximum = Math.MIN_INT
      minimum = Math.MAX_INT
      sum = 0
      count = 0
    }

    /**
     * Adds a duration to our current Timing.
     */
    def add(n: Int): Long = synchronized {
      if (n > -1) {
        maximum = n max maximum
        minimum = n min minimum
        sum += n
        count += 1
      } else {
        log.warning("Tried to add a negative timing duration. Was the clock adjusted?")
      }
      count
    }

    /**
     * Returns a tuple of (Count, Min, Max, Average) for the measured event.
     * If `reset` is true, it clears the current event timings also.
     */
    def getCountMinMaxAvg(reset: Boolean): (Long, Int, Int, Int) = synchronized {
      if (count == 0) {
        (0, 0, 0, 0)
      } else {
        val average = (sum / count).toInt
        val rv = (count, minimum, maximum, average)
        if (reset) clear
        rv
      }
    }
  }


  /**
   * A gauge has an instantaneous value (like memory usage) and is polled whenever stats
   * are collected.
   */
  trait Gauge extends ((Boolean) => Double) with Measurement


  // Maintains a Map of the variables we are tracking and their value.
  private val counterMap = new mutable.HashMap[String, Counter]()
  private val timingMap = new mutable.HashMap[String, Timing]()
  private val gaugeMap = new mutable.HashMap[String, Gauge]()

  def clearAll() = {
    counterMap.synchronized { counterMap.clear }
    timingMap.synchronized { timingMap.clear }
    gaugeMap.synchronized { gaugeMap.clear }
  }

  /**
   * Find or create a counter with the given name.
   */
  def getCounter(name: String): Counter = counterMap.synchronized {
    counterMap.get(name) match {
      case Some(counter) => counter
      case None =>
        val counter = new Counter
        counterMap += (name -> counter)
        counter
    }
  }

  /**
   * Find or create a timing measurement with the given name.
   */
  def getTiming(name: String): Timing = timingMap.synchronized {
    timingMap.get(name) match {
      case Some(timing) => timing
      case None =>
        val timing = new Timing
        timingMap += (name -> timing)
        timing
    }
  }

  /**
   * Create a gauge with the given name.
   */
  def makeGauge(name: String)(gauge: => Double): Unit = timingMap.synchronized {
    gaugeMap += (name -> new Gauge { def apply(reset: Boolean) = gauge })
  }

  def makeDerivativeGauge(name: String, nomCounter: Counter, denomCounter: Counter): Unit = {
    val g = new Gauge {
      var lastNom: Long = 0
      var lastDenom: Long = 0

      def apply(reset: Boolean) = {
        val nom = nomCounter.value.get
        val denom = denomCounter.value.get
        val deltaNom = nom - lastNom
        val deltaDenom = denom - lastDenom
        if (reset) {
          lastNom = nom
          lastDenom = denom
        }
        if (deltaDenom == 0) 0.0 else deltaNom * 1.0 / deltaDenom
      }
    }
    timingMap.synchronized {
      gaugeMap += (name -> g)
    }
  }

  /**
   * Returns a function that increments the named counter by 1.
   */
  def buildIncr(name: String): () => Long = { () => incr(1L, name) }

  /**
   * Increments the named counter by <code>by</code>.
   */
  def incr(by: Long, name: String): Long = {
    getCounter(name).value.addAndGet(by)
  }

  /**
   * Creates a Timing object of name and duration and stores
   * it in the keymap. Returns the total number of timings stored so far.
   */
  def addTiming(duration: Int, name: String): Long = {
    getTiming(name).add(duration)
  }

  /**
   * Times the duration of function f, and adds that duration to a named timing measurement.
   */
  def time[T](name: String)(f: => T): T = {
    val (rv, duration) = time(f)
    addTiming(duration.toInt, name)
    rv
  }

  /**
   * Returns how long it took, in milliseconds, to run the function f.
   */
  def time[T](f: => T): (T, Long) = {
    val start = System.currentTimeMillis
    val rv = f
    val duration = System.currentTimeMillis - start
    (rv, duration)
  }

  /**
   * Returns how long it took, in nanoseconds, to run the function f.
   */
  def timeNanos[T](f: => T): (T, Long) = {
    val start = System.nanoTime
    val rv = f
    val duration = System.nanoTime - start
    (rv, duration)
  }

  /**
   * Returns a Map[String, Long] of JVM stats.
   */
  def getJvmStats(): Map[String, Long] = {
    val out = new mutable.HashMap[String, Long]
    val mem = ManagementFactory.getMemoryMXBean()

    val heap = mem.getHeapMemoryUsage()
    out += ("heap_committed" -> heap.getCommitted())
    out += ("heap_max" -> heap.getMax())
    out += ("heap_used" -> heap.getUsed())

    val nonheap = mem.getNonHeapMemoryUsage()
    out += ("nonheap_committed" -> nonheap.getCommitted())
    out += ("nonheap_max" -> nonheap.getMax())
    out += ("nonheap_used" -> nonheap.getUsed())

    val threads = ManagementFactory.getThreadMXBean()
    out += ("thread_daemon_count" -> threads.getDaemonThreadCount().toLong)
    out += ("thread_count" -> threads.getThreadCount().toLong)
    out += ("thread_peak_count" -> threads.getPeakThreadCount().toLong)

    val runtime = ManagementFactory.getRuntimeMXBean()
    out += ("start_time" -> runtime.getStartTime())
    out += ("uptime" -> runtime.getUptime())

    val os = ManagementFactory.getOperatingSystemMXBean()
    out += ("num_cpus" -> os.getAvailableProcessors().toLong)

    out
  }

  /**
   * Returns a Map[String, Long] of counters and their current values.
   */
  def getCounterStats(): Map[String, Long] = {
    immutable.HashMap(counterMap.map(x => (x._1, x._2.value.get)).toList: _*)
  }

  /**
   * Returns a Map[String, Long] of timings. If `reset` is true, the collected timings are
   * cleared, so the next call will return the stats about timings since now.
   */
  def getTimingStats(reset: Boolean): Map[String, Long] = {
    val out = new mutable.HashMap[String, Long]
    for ((key, timing) <- timingMap) {
      val (count, minimum, maximum, average) = timing.getCountMinMaxAvg(reset)
      out += (key + "_count" -> count.toLong)
      if (count > 0) {
        out += (key + "_min" -> minimum.toLong)
        out += (key + "_max" -> maximum.toLong)
        out += (key + "_avg" -> average.toLong)
      }
    }
    out
  }

  def getTimingStats(): Map[String, Long] = getTimingStats(true)

  /**
   * Returns a Map[String, Double] of current gauge readings.
   */
  def getGaugeStats(reset: Boolean): Map[String, Double] = {
    immutable.HashMap(gaugeMap.map(x => (x._1, x._2(reset))).toList: _*)
  }

  /**
   * Returns a formatted String containing Memory statistics of the form
   * name: value
   */
  def stats(reset: Boolean): String = {
    val out = new ListBuffer[String]()
    for ((key, value) <- getJvmStats()) {
      out += (key + ": " + value.toString)
    }
    for ((key, value) <- getCounterStats()) {
      out += (key + ": " + value.toString)
    }
    for ((key, value) <- getTimingStats(reset)) {
      out += (key + ": " + value.toString)
    }
    for ((key, value) <- getGaugeStats(reset)) {
      out += (key + ": " + value.toString)
    }
    out.mkString("\n")
  }

  def stats(): String = stats(true)
}

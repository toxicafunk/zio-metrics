package zio.metrics.prometheus

import io.prometheus.{ client => jPrometheus }

import zio._
import zio.clock._
import zio.duration.Duration

trait Metric

trait Counter extends Metric {
  def inc: UIO[Unit] = inc(1)
  def inc(amount: Double): UIO[Unit]
}
object Counter extends LabelledMetric[Registry, Throwable, Counter] {
  def unsafeLabeled(
    name: String,
    help: Option[String],
    labels: Seq[String]
  ): ZIO[Registry, Throwable, Seq[String] => Counter] =
    for {
      pCounter <- updateRegistry { r =>
                   ZIO.effect(
                     jPrometheus.Counter
                       .build()
                       .name(name)
                       .help(help.getOrElse(s"No help set for $name"))
                       .labelNames(labels: _*)
                       .register(r)
                   )
                 }
    } yield { labels: Seq[String] =>
      val child = pCounter.labels(labels: _*)
      new Counter {
        override def inc(amount: Double): UIO[Unit] = ZIO.effectTotal(child.inc(amount))
      }
    }
}

trait Timer {

  /** Returns how much time as elapsed since the timer was started. */
  def elapsed: UIO[Duration]

  /**
   * Records the duration since the timer was started in the associated metric and returns that
   * duration.
   */
  def stop: UIO[Duration]
}

trait TimerMetric {

  /** Starts a timer. When the timer is stopped, the duration is recorded in the metric. */
  def startTimer: UIO[Timer]

  /** A managed timer resource. */
  def timer: UManaged[Timer] = startTimer.toManaged(_.stop)

  /** Runs the given effect and records in the metric how much time it took to succeed or fail. */
  def observe[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] = timer.use(_ => zio)

  /** Wraps the function on an effect and records in the metric how much time it took to succeed or fail. */
  def observe_[A](f: () => A): Task[A] = timer.use(_ => ZIO.effect(f()))

  /**
   * Runs the given effect and records in the metric how much time it took to succeed. Do not
   * record failures.
   */
  def observeSuccess[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
    for {
      timer <- startTimer
      a     <- zio
      _     <- timer.stop
    } yield a

  def observe(amount: Duration): UIO[Unit]

  def observeArbitrary(value: Double): UIO[Unit]
}

private abstract class TimerMetricImpl(clock: Clock.Service) extends TimerMetric {
  override def startTimer: UIO[Timer] =
    clock.instant.map { startTime =>
      new Timer {
        def elapsed: zio.UIO[Duration] =
          clock.instant.map(Duration.fromInterval(startTime, _))
        def stop: zio.UIO[Duration] =
          elapsed.tap(observe)
      }
    }
}

trait Gauge extends Metric with TimerMetric {
  def get: UIO[Double]
  def set(value: Double): UIO[Unit]
  def inc: UIO[Unit] = inc(1)
  def inc(amount: Double): UIO[Unit]
  def dec: UIO[Unit] = dec(1)
  def dec(amount: Double): UIO[Unit]
  override def observe(amount: Duration): UIO[Unit] = set(amount.toMillis.toDouble)
  override def observeArbitrary(value: Double): UIO[Unit] = set(value)
}
object Gauge extends LabelledMetric[Registry with Clock, Throwable, Gauge] {
  def unsafeLabeled(
    name: String,
    help: Option[String],
    labels: Seq[String]
  ): ZIO[Registry with Clock, Throwable, Seq[String] => Gauge] =
    for {
      clock <- ZIO.service[Clock.Service]
      pGauge <- updateRegistry { r =>
                 ZIO.effect(
                   jPrometheus.Gauge
                     .build()
                     .name(name)
                     .help(help.getOrElse(s"No help set for $name"))
                     .labelNames(labels: _*)
                     .register(r)
                 )
               }
    } yield { labels: Seq[String] =>
      val child = pGauge.labels(labels: _*)
      new TimerMetricImpl(clock) with Gauge {
        override def get: UIO[Double]               = ZIO.effectTotal(child.get())
        override def set(value: Double): UIO[Unit]  = ZIO.effectTotal(child.set(value))
        override def inc(amount: Double): UIO[Unit] = ZIO.effectTotal(child.inc(amount))
        override def dec(amount: Double): UIO[Unit] = ZIO.effectTotal(child.dec(amount))
      }
    }
}

trait Buckets
object Buckets {
  object Default                                                    extends Buckets
  case class Simple(buckets: Seq[Double])                           extends Buckets
  case class Linear(start: Double, width: Double, count: Int)       extends Buckets
  case class Exponential(start: Double, factor: Double, count: Int) extends Buckets
}

trait Histogram extends Metric with TimerMetric
object Histogram extends LabelledMetricP[Registry with Clock, Throwable, Buckets, Histogram] {
  def unsafeLabeled(
    name: String,
    buckets: Buckets,
    help: Option[String],
    labels: Seq[String]
  ): ZIO[Registry with Clock, Throwable, Seq[String] => Histogram] =
    for {
      clock <- ZIO.service[Clock.Service]
      pHistogram <- updateRegistry { r =>
                     ZIO.effect {
                       val builder = jPrometheus.Histogram
                         .build()
                         .name(name)
                         .help(help.getOrElse(s"No help set for $name"))
                         .labelNames(labels: _*)
                       (
                         buckets match {
                           case Buckets.Default              => builder
                           case Buckets.Simple(bs)           => builder.buckets(bs: _*)
                           case Buckets.Linear(s, w, c)      => builder.linearBuckets(s, w, c)
                           case Buckets.Exponential(s, f, c) => builder.exponentialBuckets(s, f, c)
                         }
                       ).register(r)
                     }
                   }
    } yield { labels: Seq[String] =>
      val child = pHistogram.labels(labels: _*)
      new TimerMetricImpl(clock) with Histogram {
        override def observe(amount: Duration): UIO[Unit] =
          ZIO.effectTotal(child.observe(amount.toMillis.toDouble))

        override def observeArbitrary(value: Double): UIO[Unit] = ZIO.effectTotal(child.observe(value))
      }
    }
}

final case class Quantile(percentile: Double, tolerance: Double)

trait Summary extends Metric with TimerMetric
object Summary extends LabelledMetricP[Registry with Clock, Throwable, List[Quantile], Summary] {
  def unsafeLabeled(
    name: String,
    quantiles: List[Quantile],
    help: Option[String],
    labels: Seq[String]
  ): ZIO[Registry with Clock, Throwable, Seq[String] => Summary] =
    for {
      clock <- ZIO.service[Clock.Service]
      pHistogram <- updateRegistry { r =>
                     ZIO.effect {
                       val builder = jPrometheus.Summary
                         .build()
                         .name(name)
                         .help(help.getOrElse(s"No help set for $name"))
                         .labelNames(labels: _*)
                       quantiles.foldLeft(builder)((b, c) => b.quantile(c.percentile, c.tolerance)).register(r)
                     }
                   }
    } yield { labels: Seq[String] =>
      val child = pHistogram.labels(labels: _*)
      new TimerMetricImpl(clock) with Summary {
        override def observe(amount: Duration): UIO[Unit] =
          ZIO.effectTotal(child.observe(amount.toMillis.toDouble))

        override def observeArbitrary(value: Double): UIO[Unit] = ZIO.effectTotal(child.observe(value))
      }
    }
}

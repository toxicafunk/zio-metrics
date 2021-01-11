package zio.metrics

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.HTTPServer
import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.metrics.prometheus.LabelList.LNil
import zio.metrics.prometheus._
import zio.{Has, Layer, RIO, Runtime, Task, URLayer, ZLayer}

object MetricsLayer {

  type Metrics = Has[Metrics.Service]

  type CounterInstance   = Counter.Labelled[LabelList.LCons[LabelList.LCons[LNil]]]
  type HistogramInstance = Histogram.Labelled[LabelList.LCons[LabelList.LCons[LNil]]]

  object Metrics {
    trait Service {
      def getRegistry: RIO[Registry, CollectorRegistry]

      def inc(tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Unit]

      def inc(amount: Double, tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Unit]

      def time(f: () => Double, tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Double]
    }

    val live: URLayer[Registry, Metrics] = ZLayer.succeed(new Service {

      val rt: Runtime.Managed[Registry with Clock] = Runtime.unsafeFromLayer(Registry.liveWithDefaultMetrics ++ Clock.live)

      private val (myCounter, myHistogram) = rt.unsafeRun(
        for {
          c <- Counter("myCounter", None, "name" :: "method" :: LNil)
          h <- Histogram("myHistogram", Buckets.Default, None, "name" :: "method" :: LNil)
        } yield (c, h)
      )

      def getRegistry: RIO[Registry, CollectorRegistry] = collectorRegistry

      def inc(tags: LabelList.LCons[LabelList.LCons[LNil]]): zio.Task[Unit] =
        inc(1.0, tags)

      def inc(amount: Double, tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Unit] = {
        println(s"inc $amount")
        myCounter(tags).inc(amount)
      }

      def time(f: () => Double, tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Double] = {
        println("time")
        myHistogram(tags).observe_(f)
      }
    })

    val receiver: (CounterInstance, HistogramInstance) => Layer[Nothing, Metrics] =
      (counter, histogram) => ZLayer.succeed(
          new Service {
            def getRegistry: RIO[Registry, CollectorRegistry] =
              collectorRegistry

            def inc(tags: LabelList.LCons[LabelList.LCons[LNil]]): zio.Task[Unit] =
              inc(1.0, tags)

            def inc(amount: Double, tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Unit] =
              counter(tags).inc(amount)


            def time(f: () => Double, tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Double] =
              histogram(tags).observe_(f)
          }
      )

    val receiverHas: ZLayer[Has[(CounterInstance, HistogramInstance)], Nothing, Metrics] =
      ZLayer.fromFunction[Has[(CounterInstance, HistogramInstance)], Metrics.Service](
        f = minst =>
          new Service {
            def getRegistry: RIO[Registry, CollectorRegistry] =
              collectorRegistry

            def inc(tags: LabelList.LCons[LabelList.LCons[LNil]]): zio.Task[Unit] =
              inc(1.0, tags)

            def inc(amount: Double, tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Unit] =
              minst.get._1(tags).inc(amount)

            def time(f: () => Double, tags: LabelList.LCons[LabelList.LCons[LNil]]): Task[Double] =
              minst.get._2(tags).observe_(f)
          }
      )
  }

  val counter = Counter("PrometheusCounter", Some("Sample prometheus counter"), "class" :: "method" :: LNil)

  val histogram =
    Histogram("PrometheusHistogram", Buckets.Default, Some("Sample prometheus histogram"), "class" :: "method" :: LNil)

  /*val rLayer: ZLayer[Registry with Clock, Throwable, Metrics] = {
  //val rLayer: Layer[Nothing, Metrics] = {
    val tup: ZIO[Registry with Clock, Throwable, (CounterInstance, HistogramInstance)] = for {
      c <- counter
      h <- histogram
    } yield (c, h)
    tup.toLayer >>> Metrics.receiverHas

    //counter >>= (c => histogram >>= (h => Metrics.receiver(c,h)))
  }*/

  /*val chHas: ULayer[Has[(Counter, Histogram)]] = ZLayer.succeed[(Counter, Histogram)]((c, h))
  val rLayerHas: ZLayer[Any, Nothing, Metrics] = chHas >>> Metrics.receiverHas
  println(s"defining ReceiverHas RT: $rLayerHas")
  val combinedLayer: ZLayer[Any, Nothing, Metrics with Console] = rLayerHas ++ Console.live
  println(s"combined: $combinedLayer")
  val rtReceiverHas = Runtime.unsafeFromLayer(combinedLayer)*/

  println("defining Test program")
  import zio.metrics.prometheus.exporters._
  val exporterTest: RIO[
    Registry with Exporters with Metrics with Clock with Console,
    HTTPServer
  ] =
    http(9090).use(
      hs =>
      {
        println("Start")
        for {
          _ <- putStrLn("Exporters")
          m <- RIO.environment[Metrics]
          _ <- m.get.inc("RequestCounter" :: "get" :: LNil)
          _ <- m.get.inc("RequestCounter" :: "post" :: LNil)
          _ <- m.get.inc(2.0, "LoginCounter" :: "login" :: LNil)
          _ <- m.get.time(() => {
            Thread.sleep(2000); 2.0
          }, "histogram" :: "get" :: LNil)
          s  <- string004
          _  <- putStrLn(s)
          //hs <- httpM(9090)
        } yield hs
      }
    )

  type Env = Registry with Exporters with Console
  type MetricsEnv = Registry with Clock with Metrics with Exporters with Console

  /*val rtReceiver: Runtime.Managed[Registry with Clock with Metrics with Exporters with Console] =
    Runtime.unsafeFromLayer(Registry.liveWithDefaultMetrics ++ Clock.live >+> rLayer ++ Exporters.live ++ Console.live)*/

  /*val program: ZIO[Registry with Metrics with Exporters with Console, Throwable, Unit] =
    exporterTest >>= (server => putStrLn(s"Server port: ${server.getPort}"))*/

  /*val rt: Runtime.Managed[Registry with Exporters with Clock with Console] =
    Runtime.unsafeFromLayer(Registry.liveWithDefaultMetrics >+> Exporters.live ++ Clock.live ++ Console.live)*/

  def main(args: Array[String]): Unit = {
    //rt.unsafeRun(program.provideSomeLayer[Env](Metrics.live))

    //val hs = rtReceiver.unsafeRun(exporterTest)

    //val hs = Runtime.default.unsafeRun(exporterTest.provideLayer(Registry.liveWithDefaultMetrics ++ Clock.live >+> rLayer ++ Exporters.live ++ Console.live))

    val hs = Runtime.default.unsafeRun(exporterTest.provideLayer(Registry.liveWithDefaultMetrics >+> Exporters.live ++ Console.live ++ Clock.live ++ Metrics.live))
    println(hs.getPort)
    ()
  }
}

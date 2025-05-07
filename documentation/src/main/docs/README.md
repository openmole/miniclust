## MiniClust

MiniClust is a simple batch computing system, composed of worker coordinated via a central vanilla minio server.

Miniclust compute system comprise a vanilla minio server, and, one or several computing nodes pulling jobs from this server.
But it might be convenient to install it on top of K3S. Here are the instructions.

### Licences

The libraries that are uses to submit jobs on miniclust are under LGPLv3 and the miniclust compute node code in under regular GPLv3.

You can check the following docs:
 - [Submitting jobs](documentation/target/submit.md)

To install my project
```scala
libraryDependencies += "com" % "lib" % "@VERSION@"
```

```scala mdoc:passthrough
import miniclust.documentation.*
val schema = Schema.run
println(
  s"""```json
    |$schema
    |```
    |""".stripMargin)
```

### Deploy miniclust


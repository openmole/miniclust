# Submit jobs 

## Using the Scala client

To install my project
```scala
libraryDependencies += "org.openmole" % "miniclust" % "@VERSION@"
```

## Using another language

```scala mdoc:passthrough
import miniclust.documentation.*
val schema = Schema.run
println(
  s"""```json
    |$schema
    |```
    |""".stripMargin)
```


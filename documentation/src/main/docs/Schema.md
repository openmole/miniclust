# JSON schema

If you which to generate a client in your language, the JSON schema of the message that you can exchange with MiniClust is:
```scala mdoc:passthrough
import miniclust.documentation.*
val schema = Schema.run
println(
  s"""```json
    |$schema
    |```
    |""".stripMargin)
```


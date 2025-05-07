# Submit jobs 

## Using the Scala client

```scala
//> using scala "@SCALA_VERSION@"
//> using dep "org.openmole.miniclust::submit:@VERSION@" 
//> using repository "https://oss.sonatype.org/content/repositories/snapshots/"

import miniclust.submit.*

val url = args(0)
val user = args(1)
val password = args(2)

val server = Minio.Server(url, user, password, insecure = true)

val minio = Minio(server)
try
  val bucket = Minio.userBucket(minio, user)

  Minio.upload(minio, bucket, "Working great!", "file/test.txt")

  val run =
    Message.Submitted(
      Account(bucket.name),
      "cat test.txt",
      inputFile = Seq(InputFile("file/test.txt", "test.txt")),
      stdOut = Some("file/output.txt")
    )

  val id = submit(minio, bucket, run)

  var s: Message = run
  while
    s = status(minio, bucket, id)
    !s.finished
  do
    println(s)
    Thread.sleep(1000)

  println(s)
  println(Minio.content(minio, bucket, MiniClust.User.jobOutputPath(id, "file/output.txt")))
  clean(minio, bucket, id)
finally
  minio.close()
```

Save this file as /tmp/example.sc

And run it as:
```bash
scala run /tmp/example.sc -- https://url login password
```

## Using another language

MiniClust works by exchanging json files through the central minio server.

To submit a job you should upload a submit message to a bucket matching tagged with the tag `miniclust:submit` in the directory `/job/submit`. The name of the file should be hashed using blake3 and be named `blake3:hashvalue`.

TODO: document the protocol.

## The messages JSON schema

If you which to generate a client in another language here is the JSON schema of the messages:
```scala mdoc:passthrough
import miniclust.documentation.*
val schema = Schema.run
println(
  s"""```json
    |$schema
    |```
    |""".stripMargin)
```


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


To cache a file on the execution nodes you can define you can specify it in the InputFile class:
```scala
  val myFile = new java.io.File("/tmp/myfile")
  Minio.upload(minio, bucket, myFile, "file/myFile")

  val run =
    Message.Submitted(
      Account(bucket.name),
      "ls -la",
      inputFile = Seq(InputFile("file/myFile", "myFile", cacheKey = Some(Tool.hashFile(myFile))))
    )
```

The file will be put in the cache of the worker a reused for subsequent execution. The cache key must be the blake3 hash of the file (`blake3:hashvalue`).

## Using bash (or any other language)

MiniClust works by exchanging json files through the central minio server.

To interact with MiniClust, you use a bucket tagged with the tag `miniclust:submit`

Directories used in the user bucket:
- `/job/submit`: directory to submit a job, accept Submitted messages,
- `/job/cancel`: directory to cancel a job, accept Canceled messages,
- `/job/status`: directory to read the status of a job, exposes, Running, Failed, Completed and Canceled messages.
- `/job/output`: directory containing the output files, produced by a job.

### Submit a job

To submit a job, upload a valid Submitted json file in fn the directory `/job/submit`. The name of the file should be hashed using blake3 and be named `blake3:hashvalue`.

For instance, you can describe a simple job:
```scala mdoc:passthrough
import miniclust.message.*
val msg =
  MiniClust.generateMessage(
    Message.Submitted(
      "login",
      """echo Hello MiniClust""",
      stdOut = Some("output.txt")
    ),
    pretty = true,
  )

println(
  s"""```json
     |$msg
     |```""".stripMargin
)
```

Get the blake3 hash of the job:
```bash
b3sum test.json
```

Then copy the file in the submit directory and name it using the blake3 hash:
```bash
mc cp test.json minio/login/job/submit/blake3:0711b75d83c9956763326d36bbed042a18305902ebd9687a27e565117f535b76
```

### Check the status

Your job description stays in the submit directory until it is processed by a worker. As soon as it is the case
you can then check the status of you jobs in the status directory.

```bash
mc cat minio/login/job/status/blake3:0711b75d83c9956763326d36bbed042a18305902ebd9687a27e565117f535b76 | jq
```

Produces:
```json
{
  "version": "1",
  "id": "blake3:0711b75d83c9956763326d36bbed042a18305902ebd9687a27e565117f535b76",
  "type": "running"
}
```

Once the job is completed, the status looks like:
```json
{
  "version": "1",
  "id": "blake3:0711b75d83c9956763326d36bbed042a18305902ebd9687a27e565117f535b76",
  "type": "completed"
}
```

### Getting the output file

You can download the output of your job from the `/job/output` directory:
```bash
mc cat minio/login/job/output/blake3:0711b75d83c9956763326d36bbed042a18305902ebd9687a27e565117f535b76/output.txt
```

Displays:
```bash
Hello MiniClust
```

## JSON Schema

To implement a client for your language of choice, you can get the complete [JSON Schema of the MiniClust messages](Schema.md).
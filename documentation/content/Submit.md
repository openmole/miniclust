# Submit jobs 

## Using the Scala client

```scala
//> using scala "3.3.5"
//> using dep "org.openmole.miniclust::submit:1.0-SNAPSHOT" 
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
```json
{
  "$schema" : "http://json-schema.org/draft/2020-12/schema#",
  "$defs" : {
    "Canceled" : {
      "title" : "Canceled",
      "type" : "object",
      "required" : [
        "id",
        "canceled"
      ],
      "properties" : {
        "id" : {
          "type" : "string"
        },
        "canceled" : {
          "type" : "boolean"
        }
      }
    },
    "Completed" : {
      "title" : "Completed",
      "type" : "object",
      "required" : [
        "id"
      ],
      "properties" : {
        "id" : {
          "type" : "string"
        }
      }
    },
    "Failed" : {
      "title" : "Failed",
      "type" : "object",
      "required" : [
        "id",
        "message",
        "reason"
      ],
      "properties" : {
        "id" : {
          "type" : "string"
        },
        "message" : {
          "type" : "string"
        },
        "reason" : {
          "$ref" : "#/$defs/Reason"
        }
      }
    },
    "Reason" : {
      "title" : "Reason",
      "oneOf" : [
        {
          "$ref" : "#/$defs/Abandoned"
        },
        {
          "$ref" : "#/$defs/CompletionFailed"
        },
        {
          "$ref" : "#/$defs/ExecutionFailed"
        },
        {
          "$ref" : "#/$defs/Invalid"
        },
        {
          "$ref" : "#/$defs/PreparationFailed"
        },
        {
          "$ref" : "#/$defs/TimeExhausted"
        },
        {
          "$ref" : "#/$defs/UnexpectedError"
        }
      ]
    },
    "Abandoned" : {
      "title" : "Abandoned",
      "type" : "object"
    },
    "CompletionFailed" : {
      "title" : "CompletionFailed",
      "type" : "object"
    },
    "ExecutionFailed" : {
      "title" : "ExecutionFailed",
      "type" : "object"
    },
    "Invalid" : {
      "title" : "Invalid",
      "type" : "object"
    },
    "PreparationFailed" : {
      "title" : "PreparationFailed",
      "type" : "object"
    },
    "TimeExhausted" : {
      "title" : "TimeExhausted",
      "type" : "object"
    },
    "UnexpectedError" : {
      "title" : "UnexpectedError",
      "type" : "object"
    },
    "Running" : {
      "title" : "Running",
      "type" : "object",
      "required" : [
        "id"
      ],
      "properties" : {
        "id" : {
          "type" : "string"
        }
      }
    },
    "Submitted" : {
      "title" : "Submitted",
      "type" : "object",
      "required" : [
        "account",
        "command",
        "noise"
      ],
      "properties" : {
        "account" : {
          "$ref" : "#/$defs/Account"
        },
        "command" : {
          "type" : "string"
        },
        "inputFile" : {
          "type" : "array",
          "items" : {
            "$ref" : "#/$defs/InputFile"
          }
        },
        "outputFile" : {
          "type" : "array",
          "items" : {
            "$ref" : "#/$defs/OutputFile"
          }
        },
        "stdOut" : {
          "type" : [
            "string",
            "null"
          ]
        },
        "stdErr" : {
          "type" : [
            "string",
            "null"
          ]
        },
        "resource" : {
          "type" : "array",
          "items" : {
            "$ref" : "#/$defs/Resource"
          }
        },
        "noise" : {
          "type" : "string"
        }
      }
    },
    "Account" : {
      "title" : "Account",
      "type" : "object",
      "required" : [
        "bucket"
      ],
      "properties" : {
        "bucket" : {
          "type" : "string"
        }
      }
    },
    "InputFile" : {
      "title" : "InputFile",
      "type" : "object",
      "required" : [
        "remote",
        "local"
      ],
      "properties" : {
        "remote" : {
          "type" : "string"
        },
        "local" : {
          "type" : "string"
        },
        "cacheKey" : {
          "anyOf" : [
            {
              "$ref" : "#/$defs/Cache"
            },
            {
              "type" : "null"
            }
          ]
        }
      }
    },
    "Cache" : {
      "title" : "Cache",
      "type" : "object",
      "required" : [
        "hash",
        "extract"
      ],
      "properties" : {
        "hash" : {
          "type" : "string"
        },
        "extract" : {
          "type" : "boolean"
        }
      }
    },
    "OutputFile" : {
      "title" : "OutputFile",
      "type" : "object",
      "required" : [
        "local",
        "remote"
      ],
      "properties" : {
        "local" : {
          "type" : "string"
        },
        "remote" : {
          "type" : "string"
        }
      }
    },
    "Resource" : {
      "title" : "Resource",
      "oneOf" : [
        {
          "$ref" : "#/$defs/Core"
        },
        {
          "$ref" : "#/$defs/MaxTime"
        }
      ]
    },
    "Core" : {
      "title" : "Core",
      "type" : "object",
      "required" : [
        "core"
      ],
      "properties" : {
        "core" : {
          "type" : "integer",
          "format" : "int32"
        }
      }
    },
    "MaxTime" : {
      "title" : "MaxTime",
      "type" : "object",
      "required" : [
        "second"
      ],
      "properties" : {
        "second" : {
          "type" : "integer",
          "format" : "int32"
        }
      }
    }
  },
  "title" : "Message",
  "oneOf" : [
    {
      "$ref" : "#/$defs/Canceled"
    },
    {
      "$ref" : "#/$defs/Completed"
    },
    {
      "$ref" : "#/$defs/Failed"
    },
    {
      "$ref" : "#/$defs/Running"
    },
    {
      "$ref" : "#/$defs/Submitted"
    }
  ]
}
```



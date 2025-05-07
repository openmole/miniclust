# Submit jobs 

## Using the Scala client

To install my project
```scala
libraryDependencies += "org.openmole" % "miniclust" % "1.0-SNAPSHOT"
```

## Using another language

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



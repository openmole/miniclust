hash=$(b3sum --no-names job.json)
mc cat miniclust-test/user1/job/status/blake3:$hash | jq


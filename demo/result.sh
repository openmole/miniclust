hash=$(b3sum --no-names job.json)
mc cat miniclust-test/user1/job/output/blake3:$hash/output.txt 


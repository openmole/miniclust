mc alias set miniclust-test http://localhost:9900 user1 userpassword1
mc mb miniclust-test/user1
mc tag set miniclust-test/user1 "miniclust=submit"

hash=$(b3sum --no-names job.json)

mc cp job.json miniclust-test/user1/job/submit/blake3:$hash




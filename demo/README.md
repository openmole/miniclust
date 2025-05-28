
# MiniClust Demon

This projet demonstrate a miniclust service with a single worker node using docker compose.

## Clone the repo

```bash
git clone https://github.com/openmole/miniclust.git
cd demo
```

## Launch MiniClust

Run:
```bash
docker compose up -d
```

## Run a job

Run:
```
# Submit the job
./submit.sh

# Get the status of the job
./status.sh

# Get the result of job
./result.sh
```


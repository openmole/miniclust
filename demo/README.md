
# MiniClust Demon

This project demonstrate a MiniClust service with a single worker, using docker compose.

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

# Get the result of the job
./result.sh
```


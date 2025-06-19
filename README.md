# MiniClust

MiniClust is a lightweight multiuser batch computing system, composed of workers coordinated via a central vanilla minio server. It allows
destribution of bash command execution on a potentially large set of machines.

One or several workers pull jobs described in JSON files from the Minio server, and coordinate by writing files on the server.

The functionalities of MiniClust:
  - A vanilla minio server as a coordination point
  - User and worker accounts are minio accounts
  - Stateless workers
  - Optional caching of files on workers
  - Optional caching of archive extraction on workers
  - Workers just need outbound http access to participate
  - Workers can come and leave at any time
  - Workers are dead simple to deploy
  - Fair scheduling based on history at the worker level
  - Resources request for each job

## Documentation

You can check the following docs:
- [Demo Cluster](demo/README.md)
- [Submitting jobs](Submit.md)
- [Deploy a cluster](Deploy.md)
- [Run a worker with Docker](https://github.com/openmole/miniclust-worker)

## Licences

The libraries that are uses to submit jobs on MiniClust are under LGPLv3 and the MiniClust compute node code in under regular GPLv3.

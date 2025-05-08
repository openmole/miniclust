# MiniClust

MiniClust is a lightweight batch computing system, composed of worker coordinated via a central vanilla minio server.
One or several workers pull jobs described in JSON files from the Minio server, and coordinate by writing file on the server.

The functionalities of MiniClust:
  - A vanilla minio server as a coordination point
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
- [Submitting jobs](documentation/content/Submit.md)
- [Deploy a cluster](documentation/content/Deploy.md)
- [Run a worker with Docker](https://github.com/openmole/miniclust-worker)

## Licences

The libraries that are uses to submit jobs on MiniClust are under LGPLv3 and the MiniClust compute node code in under regular GPLv3.

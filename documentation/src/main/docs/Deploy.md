# Deploy Miniclust


## Demo service

You can try miniclust, by using [our demo project](demo/README.md). This project contains a server and a single worker all in the same docker compose.

## Using docker

The simplest way to deploy MiniClust is certainly to run a Minio server in docker

Here is an example of a production ready minio server:
```yaml
services:
  traefik:
    image: traefik:v3
    command:
      - "--api.dashboard=true"
      - "--entrypoints.web.address=:80"
      - "--entrypoints.websecure.address=:443"
      - "--providers.docker=true"
      - "--providers.docker.exposedbydefault=false"
      - "--certificatesresolvers.myresolver.acme.tlschallenge=true"
      - "--certificatesresolvers.myresolver.acme.email=romain.reuillon@iscpif.fr"
      - "--certificatesresolvers.myresolver.acme.storage=/letsencrypt/acme.json"
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - "/var/run/docker.sock:/var/run/docker.sock:ro"
      - "letsencrypt:/letsencrypt"
    restart: always

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minio
      MINIO_ROOT_PASSWORD: xxx
    volumes:
      - ./data:/data
    labels:
      - "traefik.enable=true"

      # Router for S3 API
      - "traefik.http.routers.minio-api.rule=Host(`babar.openmole.org`)"
      - "traefik.http.routers.minio-api.entrypoints=websecure"
      - "traefik.http.routers.minio-api.service=minio-api"
      - "traefik.http.routers.minio-api.tls.certresolver=myresolver"
      - "traefik.http.services.minio-api.loadbalancer.server.port=9000"

      # Router for Console UI
      - "traefik.http.routers.minio-console.rule=Host(`console-babar.openmole.org`)"
      - "traefik.http.routers.minio-console.entrypoints=websecure"
      - "traefik.http.routers.minio-console.service=minio-console"
      - "traefik.http.routers.minio-console.tls.certresolver=myresolver"
      - "traefik.http.services.minio-console.loadbalancer.server.port=9001"
    restart: always

volumes:
  minio-data:
  letsencrypt:
```


Then you should define at least 2 policies: 
 - one for the worker nodes that should be able to write in all user submission buckets and in the coordination bucket (call miniclust by default)
 - one for the users that should be able to use or create if does not exist a buket tagged with the tag: miniclust:submit

Here are a very permissive version of these 2 policies, aimed for minio servers dedicated to a single minclust cluster.

The worker policy:
```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "s3:*"
            ],
            "Resource": [
                "arn:aws:s3:::*"
            ]
        }
    ]
}
```

The user policy:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:*"
      ],
      "Resource": [
        "arn:aws:s3:::${aws:username}",
        "arn:aws:s3:::${aws:username}/*"
      ]
    }
  ]
}
```

You can now deploy MiniClust workers on the computers you want to federate in the cluster. To do that, you should:
 - create a user and attach it to the `worker` policy,
 - and then [run workers using Docker](https://github.com/openmole/miniclust-worker).

You can then create users with the `user` policy to let them submit jobs.

## On K3S

If you want to use kube, you can refer to [deploy miniclust on K3S](K3S.md).
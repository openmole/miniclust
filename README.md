## Miniclust

A simple batch computing system, composed of worker coordinated via a central vanilla minio server.

The following instructions propose a full deployment on K3S

### Prerequisites

- A machine accessible in ssh
- 2 domain name, 1 for the minio service and 1 for the mino console access

### Install K3S

Instantiate a machine and make sure to open all the ports in your security group if you are on openstack.

Deploy the server node:
```
ssh ${MASTER_USER}@${MASTER_HOST}
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="server" sh -s -
```

To prevent timeouts when uploading/downloading files you should create the following file `/var/lib/rancher/k3s/server/manifests/traefik-config.yaml` on the k3s server. 
The content of the file should be:
```
apiVersion: helm.cattle.io/v1
kind: HelmChartConfig
metadata:
  name: traefik
  namespace: kube-system
spec:
  valuesContent: |-
    additionalArguments:
      - "--entryPoints.web.transport.respondingTimeouts.readTimeout=0"
      - "--entryPoints.web.transport.respondingTimeouts.writeTimeout=0"
      - "--entryPoints.web.transport.respondingTimeouts.idleTimeout=0"
      - "--entryPoints.websecure.transport.respondingTimeouts.readTimeout=0"
      - "--entryPoints.websecure.transport.respondingTimeouts.writeTimeout=0"
      - "--entryPoints.websecure.transport.respondingTimeouts.idleTimeout=0"
```

And restart k3s: 
`sudo systemctl restart k3s`

Test that the server works. Copy the file `/etc/rancher/k3s/k3s.yaml` on your machine. In the file, replace the master IP address with `MASTER_HOST` address.

```
export KUBECONFIG=$PWD/k3s.yml
kubectl get node
```




### Deploy minio

Install the cert manager:
```
helm repo add jetstack https://charts.jetstack.io --force-update
helm install cert-manager jetstack/cert-manager --namespace cert-manager --create-namespace --version v1.17.2 --set crds.enabled=true
```

Then apply the following yaml:
```
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: yourmail@domain.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          class: traefik
```

Use following helm charts at https://github.com/minio/minio/tree/master/helm/minio

You should modify some parts of values.yaml

```yaml
rootUser: "minio"
rootPassword: "youradminpassword"
enabled: true
---
ingress:
  enabled: true
  ingressClassName: ~
  labels: {}
  annotations: 
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/force-ssl-redirect: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "10G"
  path: /
  pathType: Prefix
  hosts:
    - compute.domain.org
  tls: 
    - hosts:
        - compute.domain.org
      secretName: minio-tls
---
consoleIngress:
  enabled: true
  ingressClassName: ~
  labels: {}
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
    nginx.ingress.kubernetes.io/force-ssl-redirect: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "10G"
  path: /
  pathType: Prefix
  hosts:
    - console.domain.org
  tls:
    - hosts:
        - console.domain.org
      secretName: console-tls
---
nodeSelector:
  node-role.kubernetes.io/control-plane: "true"
---
policies:
  - name: userpolicy
    statements:
      - effect: Allow
        resources:
          - "arn:aws:s3:::${aws:username}"
          - "arn:aws:s3:::${aws:username}/*"
        actions:
          - "s3:*"
  - name: computepolicy
    statements:
      - effect: Allow
        resources:
          - "arn:aws:s3:::*"
        actions:
          - "s3:*"
---
users:
  - accessKey: compute
    secretKey: computepassword
    policy: computepolicy
```

Install minio:
```
helm repo add minio https://charts.min.io/
helm install --namespace minio --create-namespace -f values.yaml minio minio/minio 
```

To update minio if you change the values.yaml:
```yaml
helm upgrade -f values.yaml minio minio/minio  -n minio
```

### Deploy Miniclust

Change the fields password in:

``yaml
apiVersion: v1
kind: Namespace
metadata:
  name: miniclust
  labels:
    name: miniclust
---
apiVersion: v1
kind: Secret
metadata:
  name: compute-config-secret
  namespace: miniclust
stringData:
  config.yml: |
    minio:
      url: http://minio.minio.svc.cluster.local:9000
      user: compute
      password: computepassword
    compute:
     work-directory: /tmp
     cache: 50000
     sudo: job
---
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: miniclust-compute-daemon
  namespace: miniclust
spec:
  selector:
    matchLabels:
      app: miniclust-compute-daemon
  template:
    metadata:
      labels:
        app: miniclust-compute-daemon
    spec:
      initContainers:
      - name: set-permissions
        image: busybox
        securityContext:
          runAsUser: 0  # Temporarily use root for permission setting
        command:
          - sh
          - -c
          - cp /etc/miniclust/config.yml /tmp/config.yml && chmod 0400 /tmp/config.yml && chown 1001 /tmp/config.yml
        volumeMounts:
          - name: config-volume
            mountPath: "/etc/miniclust"
          - name: ephemeral-storage
            mountPath: /tmp
      containers:
      - name: minicluste-compute
        image: openmole/miniclust:1.0-SNAPSHOT
        imagePullPolicy: "Always"
        args:
          - "/tmp/config.yml"
        volumeMounts:
          - name: ephemeral-storage
            mountPath: /tmp
          - name: config-volume
            mountPath: "/etc/miniclust"
            readOnly: true
        securityContext:
          privileged: true
      volumes:
      - name: config-volume
        secret:
          secretName: compute-config-secret
          defaultMode: 0400  
      - name: ephemeral-storage
        emptyDir: {}
```

Then apply the manifest:
```yaml
kubectl apply -f manifest.yaml
```

### Adding nodes

You can now add node to the cluster by simply adding them to the k3s cluster.

On another machine:
```
TOKEN=youk3s:server:token
curl -sfL https://get.k3s.io | K3S_URL=https://server.domain.org:6443 K3S_TOKEN=$TOKEN sh -
```

You can also remove them if needed

### Using the cluster

You can now create user by creating new user with the userpolicy on the minio console.

TODO: describe how to submit, cancel, follow jobs and upload/download input an output files.
In miniclust, jobs can be submitted and piloted by putting and getting simple json files on the minio server. 
But for now you can check the example in scala in the folder submit.

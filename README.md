# VertxWebIssue2295
A small reproduction of https://github.com/vert-x3/vertx-web/issues/2295.

## Preconditions
* Java 11 installed
  * tested with Azul Zulu 11 JDK 11.60.19
* Docker installed
  * tested with Docker 20.10.21 (Docker Desktop 4.14.0) on Windows 10 Enterprise 10.0.19044
  * tested with Docker 20.10.21 on RedHat Enterprise Linux 8.7

## Execution
The reproduction is implemented as a JUnit test, i.e., in order to execute the reproduction just run the command
```
./gradlew cleanTest test
```
The following system properties are supported for parametrization:
* HTTP_SERVER_PORT - The port used by Vert.x HttpServer for listening. The system property is optional and the default value is `9090`.
* LOADBALANCER_PORT - The port used by Nginx loadbalancer for listening. The system property is optional and the default value is `9091`.
* LOG_ACTIVITY - Enables activity logging. The system property is optional and the default value is `false`. Beware of enabling activity logging since the test uses payloads > 1MB.
The parametrization can be done using the command
```
./gradlew cleanTest test -DHTTP_SERVER_PORT=9096 -DLOADBALANCER_PORT=9097 -DLOG_ACTIVITY=true
```

## Architecture
The test starts a Vert.x HttpServer listening to `0.0.0.0:${HTTP_SERVER_PORT}` and configures a BodyHandler which limits the payload size to 1MB. 
Additionally it starts a Nginx loadbalancer (Docker container) listening to `0.0.0.0:${LOADBALANCER_PORT}` and forwarding all requests to the Vert.x HttpServer. Payloads are limited to 10MB by the Nginx loadbalancer.
Afterwards the test creates a Vert.x WebClient and send the following requests to the Nginx loadbalancer:
```
POST /3             (no payload)
POST /2             (no payload)
POST /1             (no payload)
POST /test          (1024*1024+42 bytes payload)
POST /1             (no payload)
POST /2             (no payload)
POST /3             (no payload)
```
Finally it stops the Nginx loadbalancer as well as the Vert.x HttpServer again.
The whole test is repeated until it fails (1000 times at maximum).

# Deploying OpenDC

The server distribution is the whole self-hosted product: the API, the frontend it serves from the
same origin, and the launcher its local dispatcher runs simulations with.

## Run it from a checkout

```sh
./gradlew :opendc-web:opendc-web-server:installDist
cp .env.example .env                  # set OPENDC_DB_PASSWORD
docker compose up -d --build          # docker-compose.override.yml builds the image
deploy/smoke-test.sh                  # runs an experiment end to end
```

Then open <http://localhost:8080>. The stack signs nobody in (`OPENDC_AUTH_MODE=anonymous`), keeps
traces and results on a volume, and runs simulations inside the server container, held to
`OPENDC_MEMORY_LIMIT` (4 GB) of which launchers share `OPENDC_SIMULATION_MEMORY_MB` (2 GB).

To keep traces and results in a bucket and live progress in Redis instead, add the S3 overlay, which
brings up MinIO and Redis (set `MINIO_ROOT_PASSWORD` in `.env`):

```sh
docker compose -f docker-compose.yml -f docker-compose.override.yml -f docker-compose.s3.yml up -d
```

`docker compose down -v` stops everything and deletes its data.

## Run it without Docker

The distribution under `opendc-web/opendc-web-server/build/install/opendc-server` starts with
`bin/opendc-server` from that directory, whose `config/` points the server at the frontend and the
launcher beside it. It needs a PostgreSQL database named in `OPENDC_DB_URL`, `OPENDC_DB_USERNAME`
and `OPENDC_DB_PASSWORD`.

For development, run `./gradlew :opendc-web:opendc-web-server:quarkusDev` (H2, anonymous) and
`pnpm dev` in `opendc-web/opendc-web-frontend`, then open <http://localhost:3000>.

## Images

Both images package a distribution Gradle has already built, so building them needs no network:

```sh
./gradlew :opendc-web:opendc-web-server:installDist :opendc-web:opendc-web-launcher:installDist
docker build -f opendc-web/opendc-web-server/Dockerfile -t opendc-server .
docker build -f opendc-web/opendc-web-launcher/Dockerfile -t opendc-launcher .
```

The launcher image is what the Kubernetes dispatcher runs, one Job per execution; it has to be the
same version as the server, because the manifest it is handed is their contract.

## Hosting the frontend elsewhere

Build the export with `NEXT_PUBLIC_API_BASE_URL=https://api.example.org pnpm build`, host `out/`
with a fallback from `/page` to `/page.html` (nginx: `try_files $uri $uri.html $uri/ =404`), and let
the server accept the other origin with `QUARKUS_HTTP_CORS_ENABLED=true` and
`QUARKUS_HTTP_CORS_ORIGINS=https://app.example.org`.
